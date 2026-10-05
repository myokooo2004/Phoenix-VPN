package com.phoenix.phoenixvpn.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.phoenixvpn.network.ConfigService
import com.phoenix.phoenixvpn.network.EndpointInfo
import com.phoenix.phoenixvpn.network.EndpointService
import com.phoenix.phoenixvpn.vpn.EndpointStore
import com.phoenix.phoenixvpn.vpn.TunnelConnectionState
import com.phoenix.phoenixvpn.vpn.WireGuardManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Micro-progress phases of the tap-to-connect flow (honest UI). */
enum class SetupPhase { IDLE, FETCHING_CONFIG, FETCHING_ENDPOINTS }

data class EndpointRow(
    val id: String,          // "ip:port"
    val ms: Long?,           // publisher ms
    val isBest: Boolean,
    val isCurrent: Boolean,
    val isManual: Boolean
)

data class PhoenixUiState(
    val phase: SetupPhase = SetupPhase.IDLE,
    val statusLine: String? = null,   // micro-progress / "new #1 available — tap to switch"
    val rows: List<EndpointRow> = emptyList(),
    val currentId: String? = null,
    val currentMs: Long? = null,
    val updatedAgo: String = "never",
    val stale: Boolean = false,
    val autoRun: Boolean = false,
    val newBestAvailable: Boolean = false,
    val sheetOpen: Boolean = false,
    val manualDialogOpen: Boolean = false,
    /** null = ADD mode; non-null = EDIT mode for this endpoint id. */
    val editTargetId: String? = null,
    val aboutOpen: Boolean = false,
    val message: String? = null,
    val connectedAt: Long = 0L,
    val manualId: String? = null
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val manager = WireGuardManager.getInstance(application)
    private val store = EndpointStore(application)

    val vpnState: StateFlow<com.phoenix.phoenixvpn.vpn.VpnUiState> = manager.vpnState

    private val _ui = MutableStateFlow(PhoenixUiState())
    val ui: StateFlow<PhoenixUiState> = _ui.asStateFlow()

    /** Fired when the flow needs the OS VPN-permission dialog; Activity launches it. */
    private val _permissionRequest = MutableStateFlow<Intent?>(null)
    val permissionRequest: StateFlow<Intent?> = _permissionRequest.asStateFlow()
    private var permissionDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Battery-exemption flow: HomeScreen's strip asks, MainActivity launches the
     * system screen via ActivityResultLauncher. On return (after a short delay
     * for the setting to propagate), [batteryCheckTick] bumps and the strip
     * re-checks — no reliance on winning a resume race.
     */
    private val _batteryExemptionRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batteryExemptionRequest: SharedFlow<Unit> = _batteryExemptionRequest
    private val _batteryCheckTick = MutableStateFlow(0)
    val batteryCheckTick: StateFlow<Int> = _batteryCheckTick.asStateFlow()

    fun requestBatteryExemption() {
        _batteryExemptionRequest.tryEmit(Unit)
    }

    fun onBatteryExemptionReturned() {
        _batteryCheckTick.value += 1
    }

    /**
     * Battery-exemption grant persistence. On HyperOS/Xiaomi
     * PowerManager.isIgnoringBatteryOptimizations() is unreliable — it can
     * return false even after the user granted the exemption, so a config
     * change (e.g. dark/light mode switch) recreates the activity, the fresh
     * check reads false, and the strip wrongly reappears. Once ANY check
     * observes the exemption as granted, we persist it locally; the strip
     * then stays hidden for this install (survives config changes and
     * process death). Clearing app data resets it.
     */
    private val uiPrefs =
        application.getSharedPreferences("phoenix_vpn_ui", Context.MODE_PRIVATE)
    private val _batteryExemptionConfirmed =
        MutableStateFlow(uiPrefs.getBoolean("battery_exemption_confirmed", false))
    val batteryExemptionConfirmed: StateFlow<Boolean> =
        _batteryExemptionConfirmed.asStateFlow()

    fun confirmBatteryExemption() {
        if (!_batteryExemptionConfirmed.value) {
            _batteryExemptionConfirmed.value = true
            uiPrefs.edit().putBoolean("battery_exemption_confirmed", true).apply()
        }
    }

    private var connectJob: Job? = null
    private var tickerJob: Job? = null
    private var lastBackgroundFetchAt: Long = 0L

    init {
        // Restore persisted state into the manager (auto-run rules need it).
        val conf = store.baseConf
        val cur = store.currentEndpoint ?: store.effectiveList().firstOrNull()?.id
            ?: EndpointStore.BUNDLED.first().id
        if (conf != null) {
            pushDefaultTunnel(conf, cur)
        }
        _ui.update {
            it.copy(
                autoRun = store.autoRun,
                currentId = cur,
                manualId = store.manualEndpoint
            )
        }
        manager.setAutoRun(store.autoRun)
        refreshUiRows()
        refreshUpdatedAgo()

        // Uptime ticker + derived UI while connected.
        var lastConnState = TunnelConnectionState.DISCONNECTED
        viewModelScope.launch {
            vpnState.collect { st ->
                val cur = st.connectionState
                if (cur == TunnelConnectionState.CONNECTED &&
                    lastConnState != TunnelConnectionState.CONNECTED
                ) {
                    // Fresh connect: drop the transient micro-progress text.
                    // (newBestAvailable survives — it clears when the user
                    // switches to the new #1.)
                    _ui.update { it.copy(statusLine = null) }
                }
                lastConnState = cur
                if (cur == TunnelConnectionState.CONNECTED) {
                    if (_ui.value.connectedAt == 0L) {
                        _ui.update { it.copy(connectedAt = SystemClock.elapsedRealtime()) }
                    }
                    startTicker()
                } else {
                    stopTicker()
                    if (cur == TunnelConnectionState.DISCONNECTED) {
                        _ui.update { it.copy(connectedAt = 0L) }
                    }
                }
                // Keep currentMs in sync with the selected endpoint.
                val curId = _ui.value.currentId
                _ui.update { it.copy(currentMs = msFor(curId)) }
            }
        }

        // Endpoint-dead failover: watchdog exhausted the current endpoint.
        viewModelScope.launch {
            manager.endpointDeadEvent.collect {
                failover("Endpoint stopped responding — switching…")
            }
        }

        // Silent background refresh of the published list.
        viewModelScope.launch {
            delay(4000)
            backgroundRefresh()
        }

        // Pre-fetch the WireGuard .conf on app start if missing, so the
        // first connect doesn't stall on "Fetching config…" (pguard is
        // slow from Myanmar). Silent: no phase UI, no toast — connectFlow
        // retries with UI feedback if this fails or is skipped.
        if (store.baseConf.isNullOrBlank()) {
            viewModelScope.launch {
                if (!hasValidatedInternet()) return@launch
                try {
                    val fetched = ConfigService.fetchWireGuardConfig().getOrNull()
                    if (fetched != null) {
                        store.baseConf = fetched.content
                    }
                } catch (_: Exception) {
                    // Silent — connectFlow fetches with UI if still missing.
                }
            }
        }
    }

    // ============================================================
    //  Power button
    // ============================================================

    fun onPowerTap() {
        // Tapping during the setup flow cancels it (fetch/connect stages).
        if (_ui.value.phase != SetupPhase.IDLE) {
            cancelConnectFlow()
            return
        }
        when (vpnState.value.connectionState) {
            TunnelConnectionState.CONNECTED,
            TunnelConnectionState.CONNECTING -> manualDisconnect()
            else -> connectFlow()
        }
    }

    private fun cancelConnectFlow() {
        connectJob?.cancel()
        _ui.update { it.copy(phase = SetupPhase.IDLE, statusLine = null) }
    }

    private fun manualDisconnect() {
        connectJob?.cancel()
        _ui.update { it.copy(phase = SetupPhase.IDLE, statusLine = null) }
        // Manual-off sticks: network events must NOT clear this.
        manager.setUserOverride(true)
        viewModelScope.launch {
            manager.stopTunnel()
        }
    }

    /**
     * Tap-to-connect flow:
     *   internet → fetch .conf (if missing) → fetch endpoints.json (if stale)
     *   → VPN permission → connect with failover (publisher order, first
     *   handshake within ~5s wins; no pre-verification, so the VPN icon
     *   never flickers).
     * Any step may abort back to disconnected with a toast reason.
     */
    fun connectFlow() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            try {
                // 1. Internet first — no point asking for anything without it.
                if (!hasValidatedInternet()) {
                    message("No internet connection")
                    return@launch
                }

                // 2. .conf
                var conf = store.baseConf
                if (conf.isNullOrBlank()) {
                    setPhase(SetupPhase.FETCHING_CONFIG, "Fetching config…")
                    conf = ConfigService.fetchWireGuardConfig()
                        .getOrElse {
                            message("Config fetch failed — check connection")
                            return@launch
                        }.content
                    store.baseConf = conf
                }

                // 3. Endpoints (only when the cache is stale).
                if (store.isCacheStale(now())) {
                    setPhase(SetupPhase.FETCHING_ENDPOINTS, "Fetching endpoints…")
                    val res = EndpointService.fetch()
                    if (res.isSuccess) {
                        val f = res.getOrThrow()
                        store.saveFetched(f, now())
                    }
                    // Failure: keep cache / bundled silently — never fatal.
                }
                refreshUpdatedAgo()

                // 4. VPN permission — needed for connect.
                // Requested just-in-time, after the fetches.
                if (manager.checkVpnPermission() != null) {
                    val granted = awaitPermission()
                    if (!granted) {
                        message("VPN permission denied")
                        return@launch
                    }
                }

                // 5. Connect with failover in publisher order: published
                // top-10 → bundled → manual. First handshake (~5s each)
                // wins and stays connected.
                setPhase(SetupPhase.IDLE, "Connecting…")
                manager.setUserOverride(false)
                refreshUiRows()
                if (!connectWithFailover(conf, excludeId = null)) {
                    message("No working endpoint found")
                }
            } finally {
                if (vpnState.value.connectionState != TunnelConnectionState.CONNECTED &&
                    vpnState.value.connectionState != TunnelConnectionState.CONNECTING
                ) {
                    _ui.update { it.copy(phase = SetupPhase.IDLE, statusLine = null) }
                } else {
                    _ui.update { it.copy(phase = SetupPhase.IDLE) }
                }
            }
        }
    }

    private suspend fun connectTo(endpointId: String, conf: String) {
        val cfg = manager.swapEndpoint(conf, endpointId)
        manager.setUserOverride(false)
        store.currentEndpoint = endpointId
        pushDefaultTunnel(conf, endpointId)
        refreshUiRows()
        _ui.update { it.copy(currentMs = msFor(endpointId)) }
        manager.startTunnel(manager.tunnelNameFor(endpointId), cfg)
        if (vpnState.value.connectionState == TunnelConnectionState.ERROR) {
            message(vpnState.value.errorMessage ?: "Connection failed")
            _ui.update { it.copy(statusLine = null) }
        }
    }

    /**
     * Try each candidate endpoint in order, waiting up to ~5s for the first
     * handshake rx. The first handshake wins and stays connected.
     * Order: stored current endpoint (explicit user choice / last winner)
     * first, then published list (publisher order) → bundled → manual,
     * deduped. Returns true on the first working endpoint, false if all
     * failed.
     */
    private suspend fun connectWithFailover(conf: String, excludeId: String?): Boolean {
        val seen = LinkedHashSet<String>()
        val candidates = mutableListOf<EndpointInfo>()
        fun add(e: EndpointInfo) {
            if (seen.add(e.id)) candidates.add(e)
        }
        store.effectiveList().forEach { add(it) }
        EndpointStore.BUNDLED.forEach { add(it) }
        store.manualEndpoint?.let { raw ->
            val i = raw.lastIndexOf(':')
            if (i > 0) {
                val port = raw.substring(i + 1).toIntOrNull() ?: 500
                add(EndpointInfo(raw.substring(0, i), port, null, null))
            }
        }
        // Honor an explicit selection / last working endpoint first.
        val cur = store.currentEndpoint
        if (cur != null && cur != excludeId) {
            val idx = candidates.indexOfFirst { it.id == cur }
            if (idx > 0) {
                val e = candidates.removeAt(idx)
                candidates.add(0, e)
            }
        }
        for (e in candidates) {
            currentCoroutineContext().job.ensureActive()
            if (e.id == excludeId) continue
            _ui.update { it.copy(statusLine = "Connecting… ${e.id}") }
            val cfg = manager.swapEndpoint(conf, e.id)
            val ok = try {
                manager.startTunnelAndAwaitHandshake(manager.tunnelNameFor(e.id), cfg, 5000L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
            if (ok) {
                store.currentEndpoint = e.id
                pushDefaultTunnel(conf, e.id)
                refreshUiRows()
                _ui.update { it.copy(currentMs = e.ms) }
                return true
            }
        }
        return false
    }

    /** Fail over to the next endpoint when the current one dies. */
    private fun failover(reason: String) {
        if (manager.isUserOverride()) return
        val st = vpnState.value.connectionState
        if (st != TunnelConnectionState.CONNECTED && st != TunnelConnectionState.CONNECTING) return
        val cur = _ui.value.currentId
        val conf = store.baseConf ?: return
        message(reason)
        viewModelScope.launch {
            try {
                manager.stopTunnel()
            } catch (_: Exception) {
            }
            if (!connectWithFailover(conf, excludeId = cur)) {
                message("No working endpoint found")
            }
        }
    }

    // ============================================================
    //  Endpoint sheet / manual / auto-run
    // ============================================================

    fun openSheet() = _ui.update { it.copy(sheetOpen = true) }
    fun closeSheet() = _ui.update { it.copy(sheetOpen = false) }

    fun selectEndpoint(id: String) {
        closeSheet()
        store.currentEndpoint = id
        _ui.update { it.copy(newBestAvailable = false) }
        val conf = store.baseConf
        if (conf == null) {
            // No conf yet — the full flow picks up the stored selection.
            connectFlow()
            return
        }
        if (id == _ui.value.currentId &&
            vpnState.value.connectionState == TunnelConnectionState.CONNECTED
        ) return
        pushDefaultTunnel(conf, id)
        refreshUiRows()
        if (vpnState.value.connectionState == TunnelConnectionState.CONNECTED ||
            vpnState.value.connectionState == TunnelConnectionState.CONNECTING
        ) {
            viewModelScope.launch {
                try { manager.stopTunnel() } catch (_: Exception) {}
                connectTo(id, conf)
            }
        } else {
            _ui.update { it.copy(currentMs = msFor(id)) }
        }
    }

    fun openManualDialog() = _ui.update { it.copy(manualDialogOpen = true, editTargetId = null) }
    fun openEditDialog(id: String) = _ui.update { it.copy(manualDialogOpen = true, editTargetId = id) }
    fun closeManualDialog() = _ui.update { it.copy(manualDialogOpen = false, editTargetId = null) }

    fun saveManualEndpoint(raw: String) {
        val v = raw.trim()
        if (!isValidEndpoint(v)) {
            message("Use host:port (e.g. 8.34.70.118:500)")
            return
        }
        val target = _ui.value.editTargetId
        if (target == null) {
            // ADD mode — single manual slot.
            store.manualEndpoint = v
            _ui.update { it.copy(manualId = v) }
            message("Manual endpoint saved")
        } else if (target == _ui.value.manualId) {
            // EDIT mode on the manual endpoint.
            store.manualEndpoint = v
            _ui.update { it.copy(manualId = v) }
            message("Manual endpoint updated")
        } else {
            // EDIT mode on an auto-fetched row — persisted display override.
            store.editedEndpoints = store.editedEndpoints + (target to v)
            message("Endpoint updated")
        }
        closeSheet()
        closeManualDialog()
        refreshUiRows()
    }

    fun deleteEndpoint(id: String) {
        if (id == _ui.value.manualId) {
            store.manualEndpoint = null
            _ui.update { it.copy(manualId = null) }
        } else {
            val d = store.deletedEndpoints
            d.add(id)
            store.deletedEndpoints = d
            // Drop any pending edit for the deleted row.
            store.editedEndpoints = store.editedEndpoints - id
        }
        refreshUiRows()
        message("Endpoint deleted")
    }

    fun setAutoRun(enabled: Boolean) {
        // The master switch is purely user-controlled; code never flips it.
        store.autoRun = enabled
        _ui.update { it.copy(autoRun = enabled) }
        manager.setAutoRun(enabled)
    }

    fun openAbout() = _ui.update { it.copy(aboutOpen = true) }
    fun closeAbout() = _ui.update { it.copy(aboutOpen = false) }

    fun refreshEndpointsNow() {
        viewModelScope.launch {
            message("Refreshing endpoints…")
            val res = EndpointService.fetch()
            if (res.isSuccess) {
                val f = res.getOrThrow()
                store.saveFetched(f, now())
                refreshUpdatedAgo()
                refreshUiRows()
                message("Endpoints updated")
            } else {
                message("Refresh failed — keeping current list")
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ============================================================
    //  Permission handshake with the Activity
    // ============================================================

    private suspend fun awaitPermission(): Boolean {
        val intent = manager.checkVpnPermission() ?: return true
        permissionDeferred = CompletableDeferred()
        _permissionRequest.value = intent
        return try {
            permissionDeferred!!.await()
        } catch (_: Exception) {
            false
        } finally {
            permissionDeferred = null
            _permissionRequest.value = null
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        permissionDeferred?.complete(granted)
    }

    // ============================================================
    //  Background refresh
    // ============================================================

    private suspend fun CoroutineScope.backgroundRefresh() {
        while (isActive) {
            try {
                val now = now()
                if (now - lastBackgroundFetchAt > EndpointStore.CACHE_TTL_MS &&
                    store.isCacheStale(now) && hasValidatedInternet()
                ) {
                    lastBackgroundFetchAt = now
                    val res = EndpointService.fetch()
                    if (res.isSuccess) {
                        val f = res.getOrThrow()
                        store.saveFetched(f, now())
                        refreshUpdatedAgo()
                        val connected =
                            vpnState.value.connectionState == TunnelConnectionState.CONNECTED
                        if (connected && manager.isTunnelDegraded()) {
                            // 6h health check: the live tunnel is degraded —
                            // the watchdog confirmed the rx stall (tx kept
                            // growing) and is re-handshaking, but hasn't
                            // declared the endpoint dead yet. Proactively
                            // fail over to the best endpoint from the fresh
                            // list. isTunnelDegraded() already respects
                            // userOverride; failover() re-checks it too.
                            failover("Line degraded — switching to best endpoint…")
                        }
                        // NOTE: the old "new #1 available — tap to switch"
                        // banner is intentionally gone. A healthy live tunnel
                        // is never disturbed; a degraded one is switched
                        // automatically. Disconnected behavior is unchanged
                        // (no auto-connect).
                        refreshUiRows()
                    }
                }
            } catch (_: Exception) {
            }
            delay(60_000)
        }
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private fun pushDefaultTunnel(conf: String, endpointId: String) {
        manager.setDefaultTunnel(
            manager.tunnelNameFor(endpointId),
            manager.swapEndpoint(conf, endpointId)
        )
    }

    private fun msFor(id: String?): Long? {
        if (id == null) return null
        return store.effectiveList().firstOrNull { it.id == id }?.ms
    }

    private fun refreshUiRows() {
        val deleted = store.deletedEndpoints
        val edited = store.editedEndpoints
        val top3 = store.effectiveList()
            .filter { it.id !in deleted }
            .map { e ->
                val newId = edited[e.id]
                if (newId != null) {
                    val parts = newId.split(":")
                    e.copy(ip = parts[0], port = parts.getOrNull(1)?.toIntOrNull() ?: e.port)
                } else e
            }
            .take(3)
        val cur = store.currentEndpoint ?: top3.firstOrNull()?.id
        val manual = store.manualEndpoint
        val rows = top3.mapIndexed { i, e ->
            EndpointRow(
                id = e.id,
                ms = e.ms,
                isBest = i == 0,
                isCurrent = e.id == cur,
                isManual = false
            )
        }
        _ui.update {
            it.copy(
                rows = rows,
                currentId = cur,
                currentMs = msFor(cur),
                manualId = manual
            )
        }
    }

    private fun refreshUpdatedAgo() {
        val ts = store.publishedUpdatedAt
        val now = now()
        _ui.update {
            it.copy(
                updatedAgo = agoString(ts, now),
                stale = ts > 0 && now - ts > EndpointStore.STALE_MS
            )
        }
    }

    private fun setPhase(phase: SetupPhase, statusLine: String?) {
        _ui.update { it.copy(phase = phase, statusLine = statusLine) }
    }

    private fun message(m: String) {
        _ui.update { it.copy(message = m) }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun hasValidatedInternet(): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidEndpoint(v: String): Boolean {
        // host:port — host may be IPv4, hostname, or [IPv6] (bracketed).
        val i = v.lastIndexOf(':')
        if (i <= 0 || i == v.length - 1) return false
        val port = v.substring(i + 1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        val host = v.substring(0, i).trim().trim('[', ']')
        return host.isNotEmpty() && !host.contains(' ')
    }

    private fun agoString(ts: Long, now: Long): String {
        if (ts <= 0L) return "not yet"
        val m = (now - ts) / 60000
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 60 * 24 -> "${m / 60}h ago"
            else -> "${m / (60 * 24)}d ago"
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                // Re-emit to refresh the uptime label.
                _ui.update { it.copy() }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
