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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

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
    val errorMessage: String? = null
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
    private var currentConfig: Config? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

            val resultState = backend.setState(tunnel, Tunnel.State.UP, parsedConfig)
            handleBackendState(name, resultState)
            // Service is already in the foreground (or was just re-delivered
            // via startForegroundService on the running service): mirror the
            // connected state into its notification.
            VpnForegroundService.updateTunnelState(context, connected = true)
            startStatsPolling(tunnel)
            acquireLocks()
        } catch (e: Exception) {
            VpnForegroundService.stop(context)
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
            val resultState = backend.setState(tunnel, Tunnel.State.DOWN, null)
            handleBackendState(tunnel.name, resultState)
            currentTunnel = null
            currentConfig = null
            if (keepService) {
                VpnForegroundService.updateTunnelState(context, connected = false)
            } else {
                VpnForegroundService.stop(context)
            }
        } catch (e: Exception) {
            if (!keepService) {
                VpnForegroundService.stop(context)
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

            // Empty state callback: _vpnState is driven manually below, so a
            // racy backend UP event can't mark us CONNECTED before the first
            // handshake byte arrives.
            val tunnel = PhoenixTunnel(name) { }
            currentTunnel = tunnel

            backend.setState(tunnel, Tunnel.State.UP, parsedConfig)

            var handshakeOk = false
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isActive) break
                val rx = try {
                    backend.getStatistics(tunnel)?.totalRx() ?: 0L
                } catch (_: Exception) {
                    0L
                }
                if (rx > 0) {
                    handshakeOk = true
                    break
                }
                delay(250)
            }

            if (!handshakeOk) {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
                handleBackendState(name, Tunnel.State.DOWN)
                currentTunnel = null
                currentConfig = null
                VpnForegroundService.stop(context)
                return@withContext false
            }

            handleBackendState(name, Tunnel.State.UP)
            VpnForegroundService.updateTunnelState(context, connected = true)
            startStatsPolling(tunnel)
            acquireLocks()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d(TAG, "connect $name failed: ${e.message}")
            try {
                currentTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
            } catch (_: Exception) {
            }
            currentTunnel = null
            currentConfig = null
            VpnForegroundService.stop(context)
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
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val stats: Statistics? = backend.getStatistics(tunnel)
                    if (stats != null) {
                        val rx = stats.totalRx()
                        val tx = stats.totalTx()
                        _vpnState.update {
                            it.copy(rxBytes = rx, txBytes = tx)
                        }
                        watchdogCheck(rx, tx)
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    // ============================================================
    //  Watchdog — "connected but dead" detection
    // ============================================================
    // The tunnel reports CONNECTED while no traffic arrives (dead/blocked
    // endpoint, stale NAT). When rx stalls for WATCHDOG_STALL_MS while tx
    // keeps growing (the user is actually trying to use the line), force a
    // re-handshake with the same config. Bounded to WATCHDOG_MAX_REBINDS
    // attempts per stall episode; rx movement resets the episode. A fully
    // idle tunnel is never disturbed. When the episode is exhausted the
    // endpoint is declared dead (endpointDeadEvent) so the UI can fail over
    // to the next best endpoint. This never changes the endpoint by itself
    // and never touches the auto-run master switch.

    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastRxProgressAt: Long = 0L
    private var watchdogRebinds: Int = 0

    private fun watchdogCheck(rx: Long, tx: Long) {
        val now = System.currentTimeMillis()
        if (rx != lastRxBytes) {
            lastRxBytes = rx
            lastTxBytes = tx
            lastRxProgressAt = now
            watchdogRebinds = 0
            return
        }
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
            backend.setState(tunnel, Tunnel.State.UP, config)
            Log.d(TAG, "Watchdog: rx stalled ${WATCHDOG_STALL_MS}ms while tx grew — " +
                    "re-handshake attempt $watchdogRebinds/$WATCHDOG_MAX_REBINDS")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog rebind failed", e)
        }
    }

    // ============================================================
    //  Locks
    // ============================================================

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

            // Rebind if connected (Xiaomi-specific stalls).
            val tunnel = currentTunnel
            val config = currentConfig
            if (tunnel != null && config != null) {
                try {
                    backend.setState(tunnel, Tunnel.State.UP, config)
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
        private const val WATCHDOG_STALL_MS = 60_000L
        /** Max re-handshakes per stall episode (resets when rx moves again). */
        private const val WATCHDOG_MAX_REBINDS = 3

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
