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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.phoenix.phoenixvpn.ui.theme.Green
import com.phoenix.phoenixvpn.ui.theme.HeroRingIdle
import com.phoenix.phoenixvpn.ui.theme.PaleYellow
import com.phoenix.phoenixvpn.ui.theme.Surface
import com.phoenix.phoenixvpn.ui.theme.Teal
import com.phoenix.phoenixvpn.ui.theme.TextDim
import com.phoenix.phoenixvpn.ui.theme.TextMuted
import com.phoenix.phoenixvpn.ui.theme.TextPrimary
import com.phoenix.phoenixvpn.ui.theme.TextSecondary
import com.phoenix.phoenixvpn.vpn.TunnelConnectionState
import kotlinx.coroutines.delay

private enum class HeroState { IDLE, WORKING, CONNECTED, ERROR }

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
            .padding(top = 18.dp, bottom = 28.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                AppBar(viewModel)
            }
            // Battery-protection strip: full-bleed, only when not exempted.
            BatteryStrip()
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(14.dp))
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

            // Freshness
            Text(
                text = "Endpoints updated ${ui.updatedAgo}",
                color = if (ui.stale) Amber else TextDim,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                textAlign = TextAlign.Center
            )
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

            // Footer
            Text(
                text = "Developed by ဖီးနစ် (ထူးကြီး)",
                color = PaleYellow,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
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

    // Endpoint bottom sheet
    if (ui.sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSheet() },
            sheetState = sheetState,
            containerColor = Surface,
            scrimColor = Color(0x99000000)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(text = "Endpoints", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Auto-fetched · verified on your line · updated ${ui.updatedAgo}",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                ui.rows.forEach { row ->
                    EndpointSheetRow(row) { viewModel.selectEndpoint(row.id) }
                }
                ManualSheetRow(
                    manualId = ui.manualId,
                    isCurrent = ui.manualId != null && ui.manualId == ui.currentId,
                    onSelect = { ui.manualId?.let { viewModel.selectEndpoint(it) } },
                    onEdit = { viewModel.openManualDialog() }
                )
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    // Manual endpoint dialog
    if (ui.manualDialogOpen) {
        var text by remember { mutableStateOf(ui.manualId ?: "") }
        AlertDialog(
            onDismissRequest = { viewModel.closeManualDialog() },
            containerColor = Surface,
            title = { Text("Manual endpoint", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Single slot — used when selected, survives updates.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("8.34.70.118:500", color = TextDim) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveManualEndpoint(text) }) {
                    Text("Save", color = Teal)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeManualDialog() }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    // About dialog
    if (ui.aboutOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.closeAbout() },
            containerColor = Surface,
            title = { Text("ဖီးနစ် VPN", color = TextPrimary) },
            text = {
                Text(
                    "Lightweight WireGuard VPN.\nEndpoints are auto-fetched and verified on your line.\n\nDeveloped by ဖီးနစ် (ထူးကြီး)",
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
 * is NOT exempted from battery optimization. Re-checked on every resume.
 */
@Composable
private fun BatteryStrip() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun isExempted(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    var exempted by remember { mutableStateOf(isExempted()) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempted = isExempted()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (exempted) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101214))
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
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
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
        Text(
            text = "⏻",
            color = iconColor,
            fontSize = 64.sp
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

@Composable
private fun EndpointSheetRow(row: EndpointRow, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.id,
            color = TextPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        // Fixed 56dp BEST slot — ms column stays aligned.
        Box(modifier = Modifier.width(56.dp)) {
            if (row.isBest) {
                Text(
                    text = "BEST",
                    color = Color(0xFF0B0C0E),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Teal)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        // Fixed 26dp check slot.
        Box(modifier = Modifier.width(26.dp)) {
            if (row.isCurrent) {
                Text(text = "✓", color = Green, fontSize = 18.sp)
            }
        }
        Text(
            text = row.ms?.let { "$it ms" } ?: "",
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ManualSheetRow(
    manualId: String?,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (manualId != null) onSelect() else onEdit() }
            .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (manualId != null) {
            Text(
                text = manualId,
                color = TextPrimary,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.width(56.dp))
            Box(modifier = Modifier.width(26.dp)) {
                if (isCurrent) Text(text = "✓", color = Green, fontSize = 18.sp)
            }
            Text(
                text = "✎",
                color = Teal,
                fontSize = 16.sp,
                modifier = Modifier.clickable { onEdit() }.padding(4.dp)
            )
        } else {
            Text(text = "＋", color = Teal, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text(text = "Manual endpoint", color = TextSecondary, fontSize = 15.sp)
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
