package com.phoenix.phoenixvpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class TunnelConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnUiState(
    val connectionState: TunnelConnectionState = TunnelConnectionState.DISCONNECTED,
    val activeTunnelName: String? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val errorMessage: String? = null,
    /** True once a REAL handshake has landed for the current session (v1.40). */
    val handshakeLive: Boolean = false,
    /** Live handshake latency (ms from flow UP to handshake), not the stored list ms. */
    val livePingMs: Long? = null
)

class WireGuardManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statsJob: Job? = null
    private var autoTunnelJob: Job? = null
    private var periodicCheckJob: Job? = null

    private val backend: Backend by lazy {
        GoBackend(context.applicationContext)
    }

    private val _vpnState = MutableStateFlow(VpnUiState())
    val vpnState: StateFlow<VpnUiState> = _vpnState.asStateFlow()

    /**
     * Fires when the watchdog gives up on the current endpoint (rx stalled
     * through all re-handshakes). The UI layer fails over to the next best
     * endpoint. Never fires while the user is driving (manual off).
     */
    private val _endpointDeadEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val endpointDeadEvent: SharedFlow<Unit> = _endpointDeadEvent.asSharedFlow()

    private var currentTunnel: PhoenixTunnel? = null

    /** Pure handshake RTT (ms) from the last startTunnelAndAwaitHandshake —
     *  measured from tunnel-UP to handshake-complete, excluding setup. */
    @Volatile
    var lastHandshakeMs: Long? = null
        private set
    private var currentConfig: Config? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Serializes every backend.setState() call. GoBackend keeps its tunnel
     * state (currentTunnel / currentTunnelHandle / currentConfig) in plain
     * unsynchronized fields, so concurrent setState() calls from the
     * failover loop, the network callback, the watchdog and the auto-run
     * rules can interleave and wedge the backend (stuck "connecting",
     * occasional native crash).
     */
    private val backendMutex = Mutex()
    /**
     * elapsedRealtime() of the last completed setState(DOWN). Before the
     * next setState(UP) we wait out [DOWN_UP_SETTLE_MS]: GoBackend's DOWN
     * tears the old tunnel down via VpnService.stopSelf(), whose onDestroy()
     * only runs later on the main thread — and that delayed onDestroy()
     * nulls the backend's current tunnel fields. Bringing a new tunnel UP
     * before it lands lets the stale onDestroy() kill the fresh tunnel
     * (TUN_CREATION_ERROR / dead handshake).
     */
    private var lastDownAt = 0L

    // Auto-tunnel state — the master switch is purely user-controlled and is
    // NEVER changed programmatically.
    private var isAutoRunEnabled = false
    private var userOverride = false

    // Default tunnel used by the auto-run rules: the base .conf text plus the
    // currently selected endpoint. Pushed by the UI layer.
    private var defaultName: String? = null
    private var defaultConfigText: String? = null

    // Current underlying network (not the VPN network).
    private var currentUnderlyingNetwork: Network? = null
    /** Last-seen VALIDATED state of the underlying network (for edge detection). */
    private var wasValidated = false

    init {
        registerNetworkCallback()
    }

    class PhoenixTunnel(
        private val name: String,
        private val onStateChanged: (Tunnel.State) -> Unit
    ) : Tunnel {
        private val safeInterfaceName = sanitizeInterfaceName(name)
        override fun getName(): String = safeInterfaceName
        override fun onStateChange(newState: Tunnel.State) {
            onStateChanged(newState)
        }

        companion object {
            fun sanitizeInterfaceName(rawName: String): String {
                val filtered = rawName.filter {
                    it in 'a'..'z' || it in 'A'..'Z' || it == '_' || it == '-'
                        || it in '0'..'9'
                }
                val truncated = filtered.take(15).trimEnd('_', '-')
                return if (truncated.isEmpty() || Tunnel.isNameInvalid(truncated)) "wg0" else truncated
            }
        }
    }

    /** Tunnel interface name for an endpoint id ("1.2.3.4:500" -> "1234500"-ish). */
    fun tunnelNameFor(endpointId: String): String =
        PhoenixTunnel.sanitizeInterfaceName(endpointId)

    // ============================================================
    //  Public API
    // ============================================================

    fun setAutoRun(enabled: Boolean) {
        isAutoRunEnabled = enabled
        if (enabled) {
            userOverride = false
            startPeriodicCheck()
            scope.launch {
                checkAndApplyAutoTunnelRules(immediate = true)
            }
        } else {
            autoTunnelJob?.cancel()
            autoTunnelJob = null
            stopPeriodicCheck()
        }
    }

    fun setUserOverride(override: Boolean) {
        userOverride = override
    }

    fun isUserOverride(): Boolean = userOverride

    fun isAutoRunEnabled(): Boolean = isAutoRunEnabled

    /** Push the base .conf + selected endpoint the auto-run rules should use. */
    fun setDefaultTunnel(name: String?, configText: String?) {
        defaultName = name
        defaultConfigText = configText
    }

    /** Notification "ရပ်ရန်": manual-off semantics — override sticks, tunnel down. */
    fun handleNotificationStop() {
        userOverride = true
        scope.launch(Dispatchers.IO) {
            stopTunnel()
        }
    }

    fun checkVpnPermission(): Intent? {
        return VpnService.prepare(context)
    }

    suspend fun startTunnel(name: String, configContent: String) = withContext(Dispatchers.IO) {
        try {
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.CONNECTING,
                    activeTunnelName = name,
                    errorMessage = null
                )
            }

            VpnForegroundService.start(context)

            val parsedConfig = Config.parse(
                ByteArrayInputStream(configContent.toByteArray(Charsets.UTF_8))
            )
            currentConfig = parsedConfig

            val tunnel = PhoenixTunnel(name) { newState ->
                handleBackendState(name, newState)
            }
            currentTunnel = tunnel

            val resultState = setBackendState(tunnel, Tunnel.State.UP, parsedConfig)
            handleBackendState(name, resultState)
            // Service is already in the foreground (or was just re-delivered
            // via startForegroundService on the running service): mirror the
            // connected state into its notification.
            VpnForegroundService.updateTunnelState(context, connected = true)
            startStatsPolling(tunnel)
            acquireLocks()
        } catch (e: Exception) {
            // Failure cleanup is quiet: it must NOT set the user override
            // (that is reserved for an explicit manual-off). Auto-run stays
            // armed so a later retry can still fire.
            VpnForegroundService.stopQuiet(context)
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to connect tunnel"
                )
            }
        }
    }

    /**
     * Bring the tunnel DOWN.
     *
     * @param keepService when true, the foreground service (and its
     * notification) is kept alive while the tunnel goes down. Used for the
     * automatic no-internet stop: on internet return, [startTunnel] can then
     * re-establish the tunnel without a background startForegroundService()
     * call, which Android 12+ would reject with
     * ForegroundServiceStartNotAllowedException. The notification is updated
     * to the honest idle state. Manual paths keep the default false: a real
     * manual-off stops the service entirely.
     */
    suspend fun stopTunnel(keepService: Boolean = false) = withContext(Dispatchers.IO) {
        val tunnel = currentTunnel ?: return@withContext
        try {
            _vpnState.update { it.copy(connectionState = TunnelConnectionState.DISCONNECTING) }
            stopStatsPolling()
            releaseLocks()
            val resultState = setBackendState(tunnel, Tunnel.State.DOWN, null)
            handleBackendState(tunnel.name, resultState)
            currentTunnel = null
            currentConfig = null
            if (keepService) {
                VpnForegroundService.updateTunnelState(context, connected = false)
            } else {
                // Quiet stop: manual-off callers (notification "ရပ်ရန်",
                // power-button off) already set the user override
                // explicitly before calling. Internal callers (failover,
                // endpoint switch) must NOT poison it.
                VpnForegroundService.stopQuiet(context)
            }
        } catch (e: Exception) {
            if (!keepService) {
                VpnForegroundService.stopQuiet(context)
            }
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to stop tunnel"
                )
            }
        }
    }

    // ============================================================
    //  Connect with handshake wait (failover primitive)
    // ============================================================
    // Bring the tunnel UP for one endpoint and wait for the first handshake
    // response (rx > 0) up to [timeoutMs]. True = handshake completed: the
    // tunnel stays UP, stats polling runs, locks are held (same connected
    // state as [startTunnel]). False = timeout/failure: the tunnel is
    // brought back DOWN and the foreground service stopped, so the caller
    // can try the next endpoint. Never touches the auto-run rules.
    suspend fun startTunnelAndAwaitHandshake(
        name: String,
        configContent: String,
        timeoutMs: Long = 5000L
    ): Boolean = withContext(Dispatchers.IO) {
        var tunnel: PhoenixTunnel? = null
        // Reset at the start: early returns (e.g. setState timeout) must not
        // leave a stale value from a previous attempt.
        lastHandshakeMs = null
        // Stuck watchdog (v1.26): a single attempt can never legitimately
        // exceed the 15s setState timeout + 5s handshake poll + slack. Still
        // inside after 30s → wedged at the native level → non-fatal to
        // Crashlytics so it auto-reports with zero user action.
        val attemptJob = coroutineContext.job
        val attemptDone = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(STUCK_WATCHDOG_MS)
            if (!attemptJob.isCancelled && attemptDone.compareAndSet(false, true)) {
                withContext(Dispatchers.IO) { reportStuckAttempt(name) }
            }
        }
        attemptJob.invokeOnCompletion {
            attemptDone.set(true)
            watchdog.cancel()
        }
        try {
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.CONNECTING,
                    activeTunnelName = name,
                    errorMessage = null
                )
            }

            // The foreground service stays up across failover attempts
            // (started once here, stopped quietly only when every attempt
            // fails). Per-attempt stop/start churn used to poison the user
            // override via ACTION_STOP's manual-off semantics.
            VpnForegroundService.start(context)

            val parsedConfig = Config.parse(
                ByteArrayInputStream(configContent.toByteArray(Charsets.UTF_8))
            )
            currentConfig = parsedConfig

            // Empty state callback: _vpnState is driven manually below, so a
            // racy backend UP event can't mark us CONNECTED before the
            // handshake is confirmed (timestamp) or the first data byte
            // arrives.
            tunnel = PhoenixTunnel(name) { }
            currentTunnel = tunnel

            // Wall-clock baseline for handshake-timestamp detection: a
            // handshake timestamp at/after this proves the handshake
            // completed during THIS attempt (a stale value from an
            // earlier session can't pass). Captured before setState(UP)
            // so a handshake racing the call still counts.
            val attemptStartWallMs = System.currentTimeMillis()

            try {
                setBackendState(tunnel, Tunnel.State.UP, parsedConfig)
            } catch (te: TimeoutCancellationException) {
                // Wedged backend: fail THIS attempt cleanly so the caller
                // tries the next endpoint instead of hanging "connecting".
                Log.e(TAG, "setState UP timed out for $name")
                tunnel?.let { t ->
                    try {
                        setBackendState(t, Tunnel.State.DOWN, null)
                    } catch (_: Exception) {
                    }
                }
                handleBackendState(name, Tunnel.State.DOWN)
                currentTunnel = null
                currentConfig = null
                return@withContext false
            }

            var handshakeOk = false
            // Pure handshake baseline (wall-clock): tunnel is UP, handshake
            // init sent. Using the peer's handshake timestamp avoids the
            // 250ms poll quantization — this is the true network RTT.
            val tunnelUpWallMs = System.currentTimeMillis()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isActive) break
                val stats = try {
                    backend.getStatistics(tunnel)
                } catch (_: Exception) {
                    null
                }
                val rx = stats?.totalRx() ?: 0L
                // Fast path: handshake completed during THIS attempt — works
                // on idle lines with zero traffic (no need to wait for data).
                // Slow path: first data bytes arrived. Either proves the
                // endpoint is alive.
                var hsWallMs: Long? = null
                val hsDone = stats?.peers()?.any { k ->
                    val t = stats.peer(k)?.latestHandshakeEpochMillis() ?: 0L
                    if (t >= attemptStartWallMs) hsWallMs = t
                    t >= attemptStartWallMs
                } == true
                if (hsDone) {
                    // Pure handshake RTT, excluding tunnel setup.
                    hsWallMs?.let { lastHandshakeMs = it - tunnelUpWallMs }
                    handshakeOk = true
                    break
                }
                if (rx > 0) {
                    handshakeOk = true
                    break
                }
                delay(250)
            }

            if (!handshakeOk) {
                try {
                    setBackendState(tunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
                handleBackendState(name, Tunnel.State.DOWN)
                currentTunnel = null
                currentConfig = null
                return@withContext false
            }

            handleBackendState(name, Tunnel.State.UP)
            VpnForegroundService.updateTunnelState(context, connected = true)
            startStatsPolling(tunnel)
            acquireLocks()
            true
        } catch (e: Exception) {
            if (e is TimeoutCancellationException) {
                // A DOWN-path timeout (UP timeouts are handled above):
                // attempt failure, not a user cancellation.
                Log.e(TAG, "setState DOWN timed out for $name")
                currentTunnel = null
                currentConfig = null
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.DISCONNECTED,
                        activeTunnelName = null,
                        errorMessage = null
                    )
                }
                return@withContext false
            }
            if (e is kotlinx.coroutines.CancellationException) {
                // Cancelled mid-attempt (user tapped again): don't leak a
                // half-up tunnel behind a stuck CONNECTING. NonCancellable
                // so the DOWN actually runs despite the cancellation.
                withContext(NonCancellable) {
                    try {
                        tunnel?.let { setBackendState(it, Tunnel.State.DOWN, null) }
                    } catch (_: Exception) {
                    }
                }
                currentTunnel = null
                currentConfig = null
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.DISCONNECTED,
                        activeTunnelName = null,
                        errorMessage = null
                    )
                }
                throw e
            }
            Log.d(TAG, "connect $name failed: ${e.message}")
            try {
                currentTunnel?.let { setBackendState(it, Tunnel.State.DOWN, null) }
            } catch (_: Exception) {
            }
            currentTunnel = null
            currentConfig = null
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.DISCONNECTED,
                    activeTunnelName = null,
                    errorMessage = null
                )
            }
            false
        }
    }

    /**
     * Stuck watchdog reporter (v1.26): a connect attempt still inside after
     * [STUCK_WATCHDOG_MS] is wedged at the native level (no Kotlin timeout
     * can interrupt it). Records a non-fatal to Crashlytics with the recent
     * log lines attached, so the failure auto-reports with zero user action.
     * Never throws — diagnostics must not break the VPN path.
     */
    private fun reportStuckAttempt(name: String) {
        try {
            val c = FirebaseCrashlytics.getInstance()
            c.setCustomKey("stuck_endpoint", name)
            c.setCustomKey("stuck_phase", "connect_attempt")
            c.log("Stuck watchdog fired for $name — recent log:")
            readRecentLogLines().forEach { c.log(it) }
            c.recordException(RuntimeException("VPN connect stuck >30s: $name"))
            Log.e(TAG, "Stuck watchdog: recorded non-fatal for $name")
        } catch (_: Exception) {
        }
    }

    private fun readRecentLogLines(): List<String> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.lines()
                .filter { it.contains("PhoenixVpnManager") || it.contains("GoBackend") || it.contains("WireGuard") }
                .takeLast(60)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Replace the Endpoint line in the [Peer] section with the given value. */
    fun swapEndpoint(confText: String, endpoint: String): String {
        // The fetched .confs carry a single peer; replacing every Endpoint
        // line keeps multi-peer files consistent too. (No $ anchor: `.`
        // already stops at end-of-line, and `$` would clash with Kotlin
        // string templates.)
        return confText.replace(
            Regex("(?im)^\\s*Endpoint\\s*=.*"),
            "Endpoint = $endpoint"
        )
    }

    /**
     * The ONLY way to call backend.setState(). Serializes callers, enforces
     * the DOWN→UP settle, and hard-bounds setState(UP) with a timeout so a
     * wedged backend can never leave the UI stuck at "connecting" forever —
     * it becomes a clean attempt failure instead.
     */
    private suspend fun setBackendState(
        tunnel: PhoenixTunnel,
        state: Tunnel.State,
        config: Config?
    ): Tunnel.State = backendMutex.withLock {
        if (state == Tunnel.State.UP) {
            val waitMs = DOWN_UP_SETTLE_MS - (SystemClock.elapsedRealtime() - lastDownAt)
            if (waitMs > 0) delay(waitMs)
        }
        try {
            withTimeout(SET_STATE_TIMEOUT_MS) {
                backend.setState(tunnel, state, config)
            }
        } finally {
            if (state == Tunnel.State.DOWN) lastDownAt = SystemClock.elapsedRealtime()
        }
    }

    // ============================================================
    //  Backend State
    // ============================================================

    private fun handleBackendState(tunnelName: String, state: Tunnel.State) {
        when (state) {
            Tunnel.State.UP -> {
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.CONNECTED,
                        activeTunnelName = tunnelName,
                        errorMessage = null
                    )
                }
            }
            Tunnel.State.DOWN -> {
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.DISCONNECTED,
                        activeTunnelName = null
                    )
                }
            }
            Tunnel.State.TOGGLE -> { /* intermediate */ }
        }
    }

    private fun startStatsPolling(tunnel: PhoenixTunnel) {
        stopStatsPolling()
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastRxProgressAt = System.currentTimeMillis()
        watchdogRebinds = 0
        tunnelUpWallMs = System.currentTimeMillis()
        _vpnState.update { it.copy(handshakeLive = false, livePingMs = null) }
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val stats: Statistics? = backend.getStatistics(tunnel)
                    if (stats != null) {
                        val rx = stats.totalRx()
                        val tx = stats.totalTx()
                        _vpnState.update {
                            val landed = handshakeLanded(stats)
                            it.copy(
                                rxBytes = rx, txBytes = tx,
                                handshakeLive = it.handshakeLive || landed,
                                livePingMs = if (landed && it.livePingMs == null) {
                                    (latestHandshakeEpoch(stats) - tunnelUpWallMs)
                                        .coerceAtLeast(0L)
                                } else {
                                    it.livePingMs
                                }
                            )
                        }
                        watchdogCheck(rx, tx)
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
        startBirthWake(tunnel)
    }

    /** Newest peer handshake time (epoch ms) in [stats]; 0 when none landed. */
    private fun latestHandshakeEpoch(stats: Statistics): Long =
        stats.peers().maxOfOrNull { k ->
            stats.peer(k)?.latestHandshakeEpochMillis() ?: 0L
        } ?: 0L

    /**
     * True when a REAL handshake has landed for the current flow: a
     * peer handshake timestamp at/after this flow's UP (1s grace for a
     * handshake that raced the UP stamp). A stale timestamp from an
     * earlier session can never pass — it predates the flow.
     */
    private fun handshakeLanded(stats: Statistics): Boolean {
        val hs = latestHandshakeEpoch(stats)
        return hs > 0L && hs >= tunnelUpWallMs - 1000L
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
        wakeJob?.cancel()
        wakeJob = null
    }

    private var wakeJob: Job? = null

    /** Wall-clock stamp of the current flow's UP (re-stamped by the wake). */
    private var tunnelUpWallMs: Long = 0L

    /**
     * Birth wake (v1.39, user spec 2026-10-24; cadence v1.40): a fresh
     * tunnel whose handshake never lands is not a dead endpoint — it
     * is a dead NAT flow, and retransmits on that flow never recover;
     * only a full restart draws a new one (the user's manual off/on
     * toggle, automated). The UI keeps its fire-and-forget "connected";
     * behind it this loop re-draws flows — full DOWN→UP restarts on
     * a steady ~3s cycle (1.8s wait + ~1s restart/settle overhead —
     * the rhythm of the user's own toggles, and never as slow as 5s;
     * user, 2026-10-24) — until a REAL handshake lands
     * ([handshakeLanded]) or data flows. It stands down the moment
     * the tunnel is warm, when the user takes over (userOverride), or
     * when polling stops (tunnel down / replaced). There is no
     * give-up here and no endpoint is ever declared dead: the
     * endpoint is alive, the flow was not. Mid-session stalls remain
     * the watchdog's job.
     */
    private fun startBirthWake(tunnel: PhoenixTunnel) {
        wakeJob?.cancel()
        wakeJob = scope.launch(Dispatchers.IO) {
            var stage = 0
            while (isActive) {
                delay(1800L)
                if (userOverride) return@launch
                if (currentTunnel !== tunnel) return@launch
                val stats = try {
                    backend.getStatistics(tunnel)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (stats.totalRx() > 0L || handshakeLanded(stats)) {
                    return@launch // warm — a real handshake landed; job done
                }
                if (_vpnState.value.connectionState != TunnelConnectionState.CONNECTED) continue
                val config = currentConfig ?: return@launch
                // Baseline BEFORE the restart (the await path's pattern):
                // a handshake racing the UP counts as this flow's.
                tunnelUpWallMs = System.currentTimeMillis()
                try {
                    setBackendState(tunnel, Tunnel.State.DOWN, null)
                    setBackendState(tunnel, Tunnel.State.UP, config)
                    Log.d(TAG, "Birth wake: no handshake yet — flow re-roll ${stage + 1}")
                } catch (_: Exception) {
                }
                stage++
            }
        }
    }

    // ============================================================
    //  Watchdog — "connected but dead" detection
    // ============================================================
    // The tunnel reports CONNECTED while no traffic arrives (dead/blocked
    // endpoint, stale NAT). When rx stalls for WATCHDOG_STALL_MS while tx
    // keeps growing (the user is actually trying to use the line), force a
    // real tunnel restart (fresh socket/flow) with the same config.
    // Bounded to WATCHDOG_MAX_REBINDS
    // attempts per stall episode; rx movement resets the episode. A fully
    // idle tunnel is never disturbed. When the episode is exhausted the
    // endpoint is declared dead (endpointDeadEvent) so the UI can fail over
    // to the next best endpoint. This never changes the endpoint by itself
    // and never touches the auto-run master switch.

    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastRxProgressAt: Long = 0L
    private var watchdogRebinds: Int = 0

    private suspend fun watchdogCheck(rx: Long, tx: Long) {
        val now = System.currentTimeMillis()
        if (rx != lastRxBytes) {
            lastRxBytes = rx
            lastTxBytes = tx
            lastRxProgressAt = now
            watchdogRebinds = 0
            return
        }
        // Birth phase: a tunnel that has never received anything is not
        // "stalled" — the birth wake owns it until the first handshake.
        if (rx == 0L) return
        // rx stalled. If tx is also flat the tunnel is simply idle — leave it.
        if (tx == lastTxBytes) return
        lastTxBytes = tx
        if (_vpnState.value.connectionState != TunnelConnectionState.CONNECTED) return
        if (userOverride) return // user is driving — don't fight them
        if (now - lastRxProgressAt < WATCHDOG_STALL_MS) return
        if (watchdogRebinds >= WATCHDOG_MAX_REBINDS) {
            // Episode exhausted: declare the endpoint dead exactly once per
            // episode (lastRxProgressAt keeps advancing only on rebind, so
            // this fires once until rx moves again).
            lastRxProgressAt = now
            _endpointDeadEvent.tryEmit(Unit)
            Log.d(TAG, "Watchdog: endpoint declared dead after $WATCHDOG_MAX_REBINDS rebinds")
            return
        }
        watchdogRebinds++
        lastRxProgressAt = now
        try {
            val config = currentConfig ?: return
            val tunnel = currentTunnel ?: return
            // REAL restart (2026-10-23): a same-refs setState(UP) was a
            // documented no-op in GoBackend — the socket (and its dead
            // NAT flow) survived, so the "rebind" never drew a new flow.
            // DOWN destroys the tunnel's UDP socket; UP recreates it
            // with a fresh source port = a new flow, exactly like the
            // user's manual off/on toggle. Serialized like every other
            // setState.
            setBackendState(tunnel, Tunnel.State.DOWN, null)
            setBackendState(tunnel, Tunnel.State.UP, config)
            Log.d(TAG, "Watchdog: rx stalled ${WATCHDOG_STALL_MS}ms while tx grew — " +
                    "flow re-roll $watchdogRebinds/$WATCHDOG_MAX_REBINDS")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog re-roll failed", e)
        }
    }

    // ============================================================
    //  Locks
    // ============================================================

    /**
     * 6h tunnel health-check signal for the background refresh. True when the
     * watchdog has confirmed the live tunnel is degraded — rx stalled a full
     * WATCHDOG_STALL_MS while tx kept growing — and is re-handshaking, but
     * hasn't declared the endpoint dead yet. Read-only: no watchdog behavior
     * is changed. Idle tunnels (tx flat) are never reported degraded, and a
     * user override is always respected.
     */
    fun isTunnelDegraded(): Boolean =
        _vpnState.value.connectionState == TunnelConnectionState.CONNECTED &&
            !userOverride &&
            watchdogRebinds > 0

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PhoenixVPN:TunnelWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "PhoenixVPN:TunnelWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    // ============================================================
    //  Network Callback
    // ============================================================

    private fun registerNetworkCallback() {
        try {
            unregisterNetworkCallback()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            connectivityManager = cm

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentUnderlyingNetwork = network
                    // No debounce here: onCapabilitiesChanged will signal
                    // VALIDATED, which is the prompt reconnect trigger.
                }

                override fun onLost(network: Network) {
                    if (currentUnderlyingNetwork == network) {
                        currentUnderlyingNetwork = null
                    }
                    wasValidated = false
                    onNetworkLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    currentUnderlyingNetwork = network
                    val validatedNow = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    val justValidated = validatedNow && !wasValidated
                    wasValidated = validatedNow
                    if (justValidated) {
                        onNetworkValidated()
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister", e)
        } finally {
            networkCallback = null
            connectivityManager = null
        }
    }

    /**
     * Internet-LOST path: short ~1s debounce (flap protection), then the
     * auto-tunnel rules are applied (they stop the tunnel: no usable net).
     *
     * NOTE: unlike the legacy build, this does NOT clear userOverride here.
     * A manual VPN-off must stick through network events; only an explicit
     * user action (tap connect / enable auto-run) clears it.
     */
    private fun onNetworkLost() {
        scheduleRulesCheck(delayMs = 1000L)
    }

    /**
     * Internet-GAINED path: fired when NET_CAPABILITY_VALIDATED newly appears
     * on the underlying (non-VPN) network. No 3s debounce — the rules run
     * promptly (≤500ms) so auto-reconnect lands within 2–3s of the OS
     * declaring the network usable.
     */
    private fun onNetworkValidated() {
        scheduleRulesCheck(delayMs = 500L)
    }

    private fun scheduleRulesCheck(delayMs: Long) {
        autoTunnelJob?.cancel()
        autoTunnelJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)

            // Rebind if connected (Xiaomi-specific stalls). Serialized through
            // setBackendState; note this is a no-op when the tunnel and
            // config are unchanged (GoBackend short-circuits same refs).
            val tunnel = currentTunnel
            val config = currentConfig
            if (tunnel != null && config != null) {
                try {
                    setBackendState(tunnel, Tunnel.State.UP, config)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebind tunnel", e)
                }
            }

            checkAndApplyAutoTunnelRules()
        }
    }

    /**
     * Auto-tunnel rules:
     *   1. Stop on no usable internet (even Wi-Fi without internet).
     *   2. Respect user override (manual-off sticks).
     *   3. Auto-connect on Wi-Fi / Mobile data when enabled.
     * The master switch itself is NEVER changed here.
     */
    private suspend fun checkAndApplyAutoTunnelRules(immediate: Boolean = false) {
        if (!isAutoRunEnabled) return

        val caps = getUnderlyingNetworkCapabilities()

        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        Log.d(TAG, "AutoTunnel Check: internet=$hasInternet, validated=$isValidated, " +
                "wifi=$isWifi, cell=$isCellular, vpnState=${_vpnState.value.connectionState}")

        // ----- Rule 1: Stop on no usable internet -----
        if (!hasInternet || !isValidated) {
            if (_vpnState.value.connectionState == TunnelConnectionState.CONNECTED ||
                _vpnState.value.connectionState == TunnelConnectionState.CONNECTING
            ) {
                Log.d(TAG, "No usable network → stopping tunnel (service kept alive)")
                stopTunnel(keepService = true)
            }
            return
        }

        // ----- Rule 2: Respect user override -----
        if (userOverride && !immediate) {
            Log.d(TAG, "User override active → skip auto-connect")
            return
        }

        // ----- Rule 3: Auto-connect on Wi-Fi / Mobile data -----
        if ((isWifi || isCellular) &&
            _vpnState.value.connectionState != TunnelConnectionState.CONNECTED &&
            _vpnState.value.connectionState != TunnelConnectionState.CONNECTING
        ) {
            val name = defaultName
            val configText = defaultConfigText
            if (name != null && configText != null) {
                Log.d(TAG, "Auto-connecting via ${if (isWifi) "Wi-Fi" else "Mobile Data"}")
                userOverride = false
                startTunnel(name, configText)
            } else {
                Log.d(TAG, "No default tunnel set → skip auto-connect")
            }
        }
    }

    /**
     * Capabilities of the non-VPN underlying network, so the real Wi-Fi/Data
     * state is visible even while the VPN is up.
     */
    private fun getUnderlyingNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null

        currentUnderlyingNetwork?.let { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return caps
            }
        }

        val allNetworks = cm.allNetworks
        for (network in allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                currentUnderlyingNetwork = network
                return caps
            }
        }

        val activeCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (activeCaps != null &&
            activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            return activeCaps
        }

        return null
    }

    // ============================================================
    //  Periodic Check
    // ============================================================

    private fun startPeriodicCheck() {
        stopPeriodicCheck()
        periodicCheckJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15000)
                if (isAutoRunEnabled) {
                    try {
                        checkAndApplyAutoTunnelRules()
                    } catch (e: Exception) {
                        Log.e(TAG, "Periodic check error", e)
                    }
                }
            }
        }
    }

    private fun stopPeriodicCheck() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
    }

    companion object {
        private const val TAG = "PhoenixVpnManager"

        /** rx stall window before the watchdog forces a re-handshake. */
        private const val WATCHDOG_STALL_MS = 12_000L
        /** Max re-handshakes per stall episode (resets when rx moves again). */
        private const val WATCHDOG_MAX_REBINDS = 3
        /**
         * DOWN→UP settle: lets a torn-down VpnService's delayed onDestroy()
         * finish before the next establish(), so it can't kill the fresh
         * tunnel. Applies only right after a DOWN; a fresh UP waits nothing.
         */
        private const val DOWN_UP_SETTLE_MS = 500L
        /**
         * Hard backstop for backend.setState(): converts a wedged backend
         * into a clean attempt failure instead of a forever-"connecting" UI.
         * Normal UP completes in well under a second (local tun setup).
         */
        private const val SET_STATE_TIMEOUT_MS = 15_000L

        /**
         * Stuck watchdog for a single connect attempt (v1.26). One attempt
         * can never legitimately exceed the 15s setState timeout + 5s
         * handshake poll + slack — beyond this the backend is wedged at
         * the native level and the UI would sit at "connecting" forever.
         */
        private const val STUCK_WATCHDOG_MS = 30_000L

        @Volatile
        private var INSTANCE: WireGuardManager? = null

        fun getInstance(context: Context): WireGuardManager {
            return INSTANCE ?: synchronized(this) {
                val instance = WireGuardManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
