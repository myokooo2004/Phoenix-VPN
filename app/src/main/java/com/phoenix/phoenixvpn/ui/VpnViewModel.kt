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
import com.phoenix.phoenixvpn.vpn.VerifiedEndpoint
import com.phoenix.phoenixvpn.vpn.WireGuardManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Micro-progress phases of the tap-to-connect flow (honest UI). */
enum class SetupPhase { IDLE, FETCHING_CONFIG, FETCHING_ENDPOINTS, VERIFYING }

data class EndpointRow(
    val id: String,          // "ip:port"
    val ms: Long?,           // own verified ms, else publisher ms
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

    private var connectJob: Job? = null
    private var tickerJob: Job? = null
    private var lastBackgroundFetchAt: Long = 0L

    init {
        // Restore persisted state into the manager (auto-run rules need it).
        val conf = store.baseConf
        val cur = store.currentEndpoint ?: store.best3.firstOrNull()?.id
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
                    // switches to the new #1 or a re-verify refreshes best-3.)
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
    }

    // ============================================================
    //  Power button
    // ============================================================

    fun onPowerTap() {
        // Tapping during the setup flow cancels it (fetch/verify stages).
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
     *   → VPN permission → verify 10 (if needed) → connect best #1.
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
                var fetchedNew = false
                if (store.isCacheStale(now())) {
                    setPhase(SetupPhase.FETCHING_ENDPOINTS, "Fetching endpoints…")
                    val res = EndpointService.fetch()
                    if (res.isSuccess) {
                        val f = res.getOrThrow()
                        val oldTop = store.effectiveList().firstOrNull()?.id
                        store.saveFetched(f, now())
                        fetchedNew = f.endpoints.firstOrNull()?.id != oldTop
                    }
                    // Failure: keep cache / bundled silently — never fatal.
                }
                refreshUpdatedAgo()

                // 4. VPN permission — needed for handshake verify AND connect.
                // Requested just-in-time, after the fetches.
                if (manager.checkVpnPermission() != null) {
                    val granted = awaitPermission()
                    if (!granted) {
                        message("VPN permission denied")
                        return@launch
                    }
                }

                // 5. Verify (only when the list is new or nothing verified yet).
                if (fetchedNew || store.needsVerify(now())) {
                    setPhase(SetupPhase.VERIFYING, "Verifying…")
                    val candidates = store.effectiveList()
                    val verified: List<VerifiedEndpoint> =
                        manager.verifyEndpoints(conf, candidates.map { it.ip to it.port })
                    if (verified.isEmpty()) {
                        message("No working endpoint found")
                        return@launch
                    }
                    val best3 = verified.take(3).map {
                        EndpointInfo(it.ip, it.port, it.ms, null)
                    }
                    val oldTop = store.best3.firstOrNull()?.id
                    store.best3 = best3
                    store.best3VerifiedAt = now()
                    // Fresh verification: best-3 is current again.
                    _ui.update { it.copy(newBestAvailable = false) }
                    if (oldTop != null && oldTop != best3.first().id &&
                        vpnState.value.connectionState == TunnelConnectionState.CONNECTED
                    ) {
                        _ui.update { it.copy(newBestAvailable = true) }
                    }
                }
                refreshUiRows()

                // 6. Connect.
                setPhase(SetupPhase.IDLE, "Connecting…")
                manager.setUserOverride(false)
                val target = pickTarget()
                connectTo(target, conf)
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

    /** Fail over to the next best endpoint when the current one dies. */
    private fun failover(reason: String) {
        if (manager.isUserOverride()) return
        val st = vpnState.value.connectionState
        if (st != TunnelConnectionState.CONNECTED && st != TunnelConnectionState.CONNECTING) return
        val cur = _ui.value.currentId
        val next = store.best3.firstOrNull { it.id != cur }?.id
            ?: EndpointStore.BUNDLED.firstOrNull { it.id != cur }?.id
            ?: return
        message(reason)
        val conf = store.baseConf ?: return
        viewModelScope.launch {
            try {
                manager.stopTunnel()
            } catch (_: Exception) {
            }
            connectTo(next, conf)
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

    fun openManualDialog() = _ui.update { it.copy(manualDialogOpen = true) }
    fun closeManualDialog() = _ui.update { it.copy(manualDialogOpen = false) }

    fun saveManualEndpoint(raw: String) {
        val v = raw.trim()
        if (!isValidEndpoint(v)) {
            message("Use host:port (e.g. 8.34.70.118:500)")
            return
        }
        store.manualEndpoint = v
        closeSheet()
        closeManualDialog()
        refreshUiRows()
        _ui.update { it.copy(manualId = v) }
        message("Manual endpoint saved")
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

    private suspend fun backgroundRefresh() {
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
                        val oldTop = store.effectiveList().firstOrNull()?.id
                        store.saveFetched(f, now())
                        refreshUpdatedAgo()
                        val newTop = f.endpoints.firstOrNull()?.id
                        if (oldTop != null && newTop != null && oldTop != newTop &&
                            vpnState.value.connectionState == TunnelConnectionState.CONNECTED
                        ) {
                            // Never auto-switch a live tunnel; invite a tap.
                            _ui.update {
                                it.copy(
                                    newBestAvailable = true,
                                    statusLine = "new #1 available — tap to switch"
                                )
                            }
                        }
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

    /** Which endpoint to connect to: current if known, else best #1. */
    private fun pickTarget(): String {
        val cur = store.currentEndpoint
        val manual = store.manualEndpoint
        if (cur != null) {
            // Manual selection always wins.
            if (cur == manual) return cur
            if (store.best3.any { it.id == cur }) return cur
            if (EndpointStore.BUNDLED.any { it.id == cur }) return cur
        }
        return store.best3.firstOrNull()?.id
            ?: EndpointStore.BUNDLED.first().id
    }

    private fun msFor(id: String?): Long? {
        if (id == null) return null
        store.best3.firstOrNull { it.id == id }?.ms?.let { return it }
        return store.effectiveList().firstOrNull { it.id == id }?.ms
    }

    private fun refreshUiRows() {
        val best = store.best3.ifEmpty { EndpointStore.BUNDLED }
        val cur = store.currentEndpoint ?: best.firstOrNull()?.id
        val manual = store.manualEndpoint
        val rows = best.mapIndexed { i, e ->
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
