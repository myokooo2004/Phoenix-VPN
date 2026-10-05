package com.phoenix.phoenixvpn.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoenix.phoenixvpn.ui.theme.Amber
import com.phoenix.phoenixvpn.ui.theme.Bg
import com.phoenix.phoenixvpn.ui.theme.Border
import com.phoenix.phoenixvpn.ui.theme.CardBorder
import com.phoenix.phoenixvpn.ui.theme.CardSurface
import com.phoenix.phoenixvpn.ui.theme.Green
import com.phoenix.phoenixvpn.ui.theme.HeroRingIdle
import com.phoenix.phoenixvpn.ui.theme.PaleYellow
import com.phoenix.phoenixvpn.ui.theme.SheetBorder
import com.phoenix.phoenixvpn.ui.theme.Surface
import com.phoenix.phoenixvpn.ui.theme.Teal
import com.phoenix.phoenixvpn.ui.theme.TextDim
import com.phoenix.phoenixvpn.ui.theme.TextMuted
import com.phoenix.phoenixvpn.ui.theme.TextPrimary
import com.phoenix.phoenixvpn.ui.theme.TextSecondary
import com.phoenix.phoenixvpn.vpn.TunnelConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class HeroState { IDLE, WORKING, CONNECTED, ERROR }

// ---------------------------------------------------------------------------
// Static backdrop: dotted world map + teal glow (v1.20).
// Pure decoration — draws once into the render node, never animates, no
// per-frame redraw, no bitmaps, no blur. Does not participate in layout
// measurement, so it cannot affect the foreground UI.
// ---------------------------------------------------------------------------
private const val MAP_COLS = 60

private val WORLD_MAP = arrayOf(
    "                                                            ",
    "                     ####                                   ",
    "    ##########      ######                                  ",
    "  ##############     #####     ###   ####                   ",
    " ################     ###   ##################              ",
    " #################          #######################        ",
    "  ################          #########################      ",
    "  ################           #######################      ",
    "   ##############            #####################         ",
    "    ############              ###################          ",
    "     ##########       ##        ################           ",
    "      ########       ####        ##############            ",
    "      #######       ######        ############             ",
    "       ######      ########        ##########   ##         ",
    "       ######      #########        ########  #####        ",
    "        #####     ###########        ######   ######       ",
    "        #####     ############        ####     #####       ",
    "         ####     #############        ###      ####       ",
    "         #####    ##############        ##       ###       ",
    "          ####    ###############        #        ##       ",
    "          #####   ################                             ",
    "          #####   #################                            ",
    "           ####    #################           ####           ",
    "           ####    ##################         ######         ",
    "            ###     ##################         ####          ",
    "            ###      #################          ##          ",
    "             ##      ################                             ",
    "             ##       ##############                             ",
    "              #        ###########                              ",
    "                        ########                               ",
)

@Composable
private fun WorldMapBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Teal radial glow behind the hero power button area.
        val glowCenter = Offset(size.width * 0.5f, size.height * 0.30f)
        val glowRadius = size.width * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Teal.copy(alpha = 0.10f),
                    Teal.copy(alpha = 0.0f)
                ),
                center = glowCenter,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = glowCenter
        )
        // Dotted world map silhouette.
        val rows = WORLD_MAP.size
        val cellW = size.width / MAP_COLS
        val cellH = size.height / rows
        val dotR = minOf(cellW, cellH) * 0.24f
        val dotColor = Color.White.copy(alpha = 0.09f)
        for (r in 0 until rows) {
            val line = WORLD_MAP[r].padEnd(MAP_COLS, ' ').take(MAP_COLS)
            for (c in 0 until MAP_COLS) {
                if (line[c] == '#') {
                    drawCircle(
                        color = dotColor,
                        radius = dotR,
                        center = Offset((c + 0.5f) * cellW, (r + 0.5f) * cellH)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: VpnViewModel) {
    val ui by viewModel.ui.collectAsState()
    val vpn by viewModel.vpnState.collectAsState()

    val heroState = when {
        vpn.connectionState == TunnelConnectionState.CONNECTED -> HeroState.CONNECTED
        vpn.connectionState == TunnelConnectionState.ERROR -> HeroState.ERROR
        vpn.connectionState == TunnelConnectionState.CONNECTING ||
            vpn.connectionState == TunnelConnectionState.DISCONNECTING ||
            ui.phase != SetupPhase.IDLE -> HeroState.WORKING
        else -> HeroState.IDLE
    }

    val (statusText, statusColor, dotColor, dotGlow) = when (heroState) {
        HeroState.CONNECTED -> Quad("Connected", Green, Green, true)
        HeroState.WORKING -> Quad(
            ui.statusLine ?: "Connecting…", Teal, Teal, true
        )
        HeroState.ERROR -> Quad(
            vpn.errorMessage ?: "Error", Color(0xFFE57373), Color(0xFFE57373), false
        )
        HeroState.IDLE -> Quad("Disconnected", TextSecondary, TextMuted, false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // Static backdrop behind everything (draws once, no layout impact).
        WorldMapBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 18.dp, bottom = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                AppBar(viewModel)
            }
            // Battery-protection strip: full-bleed, only when not exempted.
            BatteryStrip(viewModel)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
            ) {
            Spacer(Modifier.height(26.dp))

            // Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .then(if (dotGlow) Modifier.shadow(12.dp, CircleShape) else Modifier)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText.uppercase(),
                    color = statusColor,
                    fontSize = 15.sp,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(30.dp))

            // Hero power button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HeroButton(heroState) { viewModel.onPowerTap() }
            }

            // Hint
            when (heroState) {
                HeroState.IDLE -> GlowHint("TAP TO CONNECT", Teal, glow = true)
                HeroState.CONNECTED -> GlowHint("TAP TO DISCONNECT", TextDim, glow = false)
                else -> Spacer(Modifier.height(34.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                val connected = heroState == HeroState.CONNECTED
                Stat(
                    value = if (connected && ui.currentMs != null) "${ui.currentMs}" else "–",
                    unit = if (connected && ui.currentMs != null) " ms" else null,
                    label = "Ping",
                    dim = !connected
                )
                Spacer(Modifier.width(34.dp))
                Stat(
                    value = if (connected) formatUptime(ui.connectedAt) else "–",
                    unit = null,
                    label = "Uptime",
                    dim = !connected
                )
                Spacer(Modifier.width(34.dp))
                Stat(
                    value = if (connected) formatBytes(vpn.rxBytes + vpn.txBytes).first else "–",
                    unit = if (connected) " ${formatBytes(vpn.rxBytes + vpn.txBytes).second}" else null,
                    label = "Data",
                    dim = !connected
                )
            }

            Spacer(Modifier.height(22.dp))

            // Endpoint chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .shadow(6.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardSurface)
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .clickable { viewModel.openSheet() }
                    .padding(15.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ui.currentId ?: "—",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (ui.newBestAvailable) {
                    Text(
                        text = "NEW",
                        color = Color(0xFF0B0C0E),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Teal)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = ui.currentMs?.let { "$it ms" } ?: "",
                    color = Teal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Text(text = "›", color = TextMuted, fontSize = 18.sp)
            }

            // Freshness + refresh pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Endpoints updated ${ui.updatedAgo}",
                    color = if (ui.stale) Amber else TextDim,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Teal.copy(alpha = 0.12f))
                        .border(1.dp, Teal.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .clickable { viewModel.refreshEndpointsNow() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh endpoints",
                        tint = Teal,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Refresh",
                        color = Teal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (ui.newBestAvailable && ui.statusLine != null && heroState == HeroState.CONNECTED) {
                Text(
                    text = ui.statusLine!!,
                    color = Teal,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable { viewModel.openSheet() },
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            // Auto VPN row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .border(1.dp, Color(0xFF16181A), RoundedCornerShape(0.dp))
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Auto VPN", color = Color(0xFFCFD2D6), fontSize = 15.sp)
                    Text(
                        text = "Connect when internet is available",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = ui.autoRun,
                    onCheckedChange = { viewModel.setAutoRun(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Teal,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF2A2D2E),
                        uncheckedThumbColor = TextMuted
                    )
                )
            }

            // Footer — just below the Auto VPN card.
            Text(
                text = "Developed by ဖီးနစ် (ထူးကြီး)",
                color = PaleYellow,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
            } // end padded content column
        }

        // Message snackbar
        ui.message?.let { msg ->
            LaunchedEffect(msg) {
                delay(3000)
                viewModel.clearMessage()
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp, start = 16.dp, end = 16.dp),
                containerColor = Surface,
                contentColor = TextPrimary
            ) { Text(msg) }
        }
    }

    // Endpoint sheet — floating card (all 4 corners rounded)
    if (ui.sheetOpen) {
        Dialog(
            onDismissRequest = { viewModel.closeSheet() },
            properties = DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The dialog content fills the screen, so
                    // dismissOnClickOutside never fires on its own —
                    // taps outside the card dismiss explicitly here.
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { viewModel.closeSheet() },
                contentAlignment = Alignment.Center
            ) {
                FloatingEndpointSheet(
                    onDismiss = { viewModel.closeSheet() },
                    header = {
                        Text(text = "Endpoints", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Auto-fetched · publisher order · updated ${ui.updatedAgo}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    },
                    rows = {
                        ui.rows.forEach { row ->
                            EndpointSheetRow(
                                row = row,
                                onTap = { viewModel.selectEndpoint(row.id) },
                                onEdit = { viewModel.openEditDialog(row.id) },
                                onDelete = { viewModel.deleteEndpoint(row.id) }
                            )
                        }
                    },
                    footer = {
                        ManualSheetRow(
                            manualId = ui.manualId,
                            isCurrent = ui.manualId != null && ui.manualId == ui.currentId,
                            onSelect = { ui.manualId?.let { viewModel.selectEndpoint(it) } },
                            onAdd = { viewModel.openManualDialog() },
                            onEdit = { ui.manualId?.let { viewModel.openEditDialog(it) } },
                            onDelete = { ui.manualId?.let { viewModel.deleteEndpoint(it) } }
                        )
                    }
                )
            }
        }
    }

    // Edit Endpoint dialog (ADD mode from "+ Manual endpoint", EDIT mode from row pencil)
    if (ui.manualDialogOpen) {
        val target = ui.editTargetId
        var text by remember(target) { mutableStateOf(target ?: "") }
        Dialog(onDismissRequest = { viewModel.closeManualDialog() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Surface)
                    .border(1.dp, SheetBorder, RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Text(
                    text = "Edit Endpoint",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Endpoint အသစ်ထည့်ပါ (ဥပမာ - 162.159.192.20:500)",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Endpoint", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                Text(
                    text = "မှတ်ချက် - Save လုပ်လိုက်ပါက Name ကိုလည်း Endpoint ရဲ့ IP အလိုအလျောက် ချိန်းပါမည်။",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.closeManualDialog() }) {
                        Text("CANCEL", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.saveManualEndpoint(text) }) {
                        Text("SAVE", color = Color(0xFFE5484D), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // About dialog
    if (ui.aboutOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.closeAbout() },
            containerColor = Surface,
            title = { Text("ဖီးနစ် VPN", color = TextPrimary) },
            text = {
                Text(
                    "Lightweight WireGuard VPN.\nEndpoints are auto-fetched in publisher order.\n\nDeveloped by ဖီးနစ် (ထူးကြီး)",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.closeAbout() }) {
                    Text("OK", color = Teal)
                }
            }
        )
    }
}

private data class Quad(val text: String, val color: Color, val dot: Color, val glow: Boolean)

@Composable
private fun AppBar(viewModel: VpnViewModel) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ဖီးနစ် VPN",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            modifier = Modifier.weight(1f)
        )
        Box {
            Text(
                text = "⋯",
                color = TextMuted,
                fontSize = 20.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable { menuOpen = true }.padding(4.dp)
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Surface
            ) {
                DropdownMenuItem(
                    text = { Text("Refresh endpoints", color = TextPrimary) },
                    onClick = {
                        menuOpen = false
                        viewModel.refreshEndpointsNow()
                    }
                )
                DropdownMenuItem(
                    text = { Text("About", color = TextPrimary) },
                    onClick = {
                        menuOpen = false
                        viewModel.openAbout()
                    }
                )
                DropdownMenuItem(
                    text = { Text("App info", color = TextPrimary) },
                    onClick = {
                        menuOpen = false
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
            }
        }
    }
}

/**
 * Slim battery-protection strip below the app bar, visible only when the app
 * is NOT exempted from battery optimization. On every resume the exemption is
 * checked immediately; if not yet granted, re-checks run at +1s/+2s/+3s/+5s
 * (stop early on grant) so slow setting propagation or a missing
 * ActivityResult callback (HyperOS/Xiaomi) can't leave the strip stuck.
 * Tapping the strip forces an immediate re-check. The launcher +
 * viewModel.batteryCheckTick path is kept as a backup for devices where the
 * callback does fire.
 *
 * Grant persistence: HyperOS/Xiaomi reports isIgnoringBatteryOptimizations()
 * unreliably — a config change (dark/light mode switch) recreates the
 * activity, the fresh check reads false, and the strip wrongly reappears.
 * So every observed grant is persisted via viewModel.confirmBatteryExemption()
 * and the strip stays hidden once confirmed for this install.
 */
@Composable
private fun BatteryStrip(viewModel: VpnViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val checkTick by viewModel.batteryCheckTick.collectAsState()
    val confirmed by viewModel.batteryExemptionConfirmed.collectAsState()
    val scope = rememberCoroutineScope()

    fun isExempted(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    // Any observed grant is persisted so the strip never reappears for this
    // install, even if a later check (e.g. after a config change) misreads.
    fun refreshExemption(): Boolean {
        val ok = isExempted()
        if (ok) viewModel.confirmBatteryExemption()
        return ok
    }

    var exempted by remember { mutableStateOf(refreshExemption()) }

    // Retry window: on some devices (HyperOS/Xiaomi) the ActivityResult
    // callback for ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS never fires
    // and the setting propagates slowly, so a single ON_RESUME check races
    // ("needs 2 taps"). Re-check at +1/+2/+3/+5s after every resume; stop
    // early as soon as the exemption is observed.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (refreshExemption()) {
                    exempted = true
                } else {
                    scope.launch {
                        for (waitMs in longArrayOf(1000L, 2000L, 3000L, 5000L)) {
                            delay(waitMs)
                            if (refreshExemption()) {
                                exempted = true
                                break
                            }
                        }
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Fired ~1s after the exemption screen returns: the setting has had time
    // to propagate, so this re-check is the reliable one.
    LaunchedEffect(checkTick) {
        if (checkTick > 0) {
            exempted = refreshExemption()
        }
    }

    if (exempted || confirmed) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101214))
                // Tapping the strip itself forces an immediate re-check — covers
                // grants done manually through system Settings.
                .clickable { exempted = refreshExemption() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🔋", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Background protection off — VPN may be killed",
                color = Color(0xFF9AA0A6),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { viewModel.requestBatteryExemption() }
            ) {
                Text(
                    text = "Fix",
                    color = Teal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1C1E20))
        )
    }
}

@Composable
private fun HeroButton(state: HeroState, onTap: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val ring = when (state) {
        HeroState.CONNECTED -> Green
        HeroState.WORKING -> Teal
        HeroState.ERROR -> Color(0xFFE57373)
        HeroState.IDLE -> HeroRingIdle
    }
    val iconColor = when (state) {
        HeroState.CONNECTED -> Green
        HeroState.WORKING -> Teal.copy(alpha = if (state == HeroState.WORKING) alpha else 1f)
        HeroState.ERROR -> Color(0xFFE57373)
        HeroState.IDLE -> TextMuted
    }

    Box(
        modifier = Modifier
            .size(190.dp)
            .shadow(
                when (state) {
                    HeroState.CONNECTED -> 42.dp
                    HeroState.WORKING -> 22.dp
                    else -> 22.dp
                },
                CircleShape,
                ambientColor = when (state) {
                    HeroState.CONNECTED -> Green.copy(alpha = 0.28f)
                    HeroState.WORKING -> Teal.copy(alpha = 0.10f)
                    else -> Teal.copy(alpha = 0.10f)
                },
                spotColor = Color.Transparent
            )
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    0f to Color(0xFF141619),
                    1f to Color(0xFF0B0C0E)
                )
            )
            .border(2.dp, ring, CircleShape)
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PowerSettingsNew,
            contentDescription = "Power",
            tint = iconColor,
            modifier = Modifier.size(64.dp)
        )
    }
}

@Composable
private fun GlowHint(text: String, color: Color, glow: Boolean) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.SemiBold,
        style = if (glow) TextStyle(
            shadow = Shadow(color = Teal.copy(alpha = 0.65f), blurRadius = 14f)
        ) else TextStyle.Default,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun Stat(value: String, unit: String?, label: String, dim: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.text.BasicText(
            text = androidx.compose.ui.text.AnnotatedString.Builder().apply {
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = if (dim) TextDim else TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light
                    )
                )
                append(value)
                if (unit != null) {
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(color = TextMuted, fontSize = 14.sp)
                    )
                    append(unit)
                }
            }.toAnnotatedString()
        )
        Text(
            text = label.uppercase(),
            color = TextMuted,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Floating bottom-sheet card: side + bottom margins, all 4 corners rounded
 * (24dp), elevated. Drag handle on top; downward drag past threshold
 * dismisses, otherwise snaps back.
 */
@Composable
private fun FloatingEndpointSheet(
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit
) {
    val sheetShape = RoundedCornerShape(24.dp)
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Max 72% of screen height so the card fits any phone size. The whole
    // card scrolls when content overflows. Deliberately NO weight()
    // anywhere: weight + verticalScroll + heightIn(max) breaks measurement
    // in Compose and lets the card overflow the screen (v1.14 bug).
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // Centered in the dialog (not bottom-anchored), so no system-bar
            // inset handling is needed — the 72% max height keeps it far
            // from status/nav bars on any phone size (v1.16).
            .heightIn(max = maxSheetHeight)
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .shadow(12.dp, sheetShape)
            .clip(sheetShape)
            .background(Surface)
            .border(1.dp, SheetBorder, sheetShape)
            // Consume taps on the card itself so they don't reach the
            // dialog's outside-tap dismiss (rows keep their own onTap).
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header: drag handle + title. Dragging down on the header
        // dismisses (kept here so it never fights the card scroll).
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 10.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetY.value > 120f) onDismiss()
                                else offsetY.animateTo(0f, tween(200))
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f))
                            }
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A3F45))
            )
            header()
        }
        // Endpoint rows.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            rows()
        }
        // Footer: manual endpoint row.
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            footer()
        }
    }
}

@Composable
private fun EndpointSheetRow(
    row: EndpointRow,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cardShape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .shadow(4.dp, cardShape)
            .clip(cardShape)
            .background(CardSurface)
            .border(1.dp, CardBorder, cardShape)
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.id,
            color = TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        // Fixed 56dp BEST slot — ms column stays aligned.
        Box(modifier = Modifier.width(56.dp)) {
            if (row.isBest) {
                Text(
                    text = "BEST",
                    color = Color(0xFF0B0C0E),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Teal)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
        // Fixed 26dp check slot.
        Box(modifier = Modifier.width(26.dp)) {
            if (row.isCurrent) {
                Text(text = "✓", color = Green, fontSize = 16.sp)
            }
        }
        Text(
            text = row.ms?.let { "$it ms" } ?: "",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Edit endpoint",
            tint = Teal,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp)
                .clickable { onEdit() }
        )
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Delete endpoint",
            tint = TextMuted,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp)
                .clickable { onDelete() }
        )
    }
}

@Composable
private fun ManualSheetRow(
    manualId: String?,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (manualId != null) onSelect() else onAdd() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (manualId != null) {
            Text(
                text = manualId,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.width(56.dp))
            Box(modifier = Modifier.width(26.dp)) {
                if (isCurrent) Text(text = "✓", color = Green, fontSize = 16.sp)
            }
            Text(
                text = "✎",
                color = Teal,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onEdit() }.padding(4.dp)
            )
            Text(
                text = "🗑",
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onDelete() }.padding(4.dp)
            )
        } else {
            Text(text = "＋", color = Teal, fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Text(text = "Manual endpoint", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

private fun formatUptime(connectedAt: Long): String {
    if (connectedAt == 0L) return "–"
    val s = (SystemClock.elapsedRealtime() - connectedAt) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

private fun formatBytes(bytes: Long): Pair<String, String> {
    if (bytes <= 0) return "0" to "B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val g = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    val v = bytes / Math.pow(1024.0, g.toDouble())
    return "%.1f".format(v) to units[g]
}
