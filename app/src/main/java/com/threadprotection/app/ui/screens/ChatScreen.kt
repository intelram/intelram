package com.threadprotection.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.threadprotection.app.chat.BtChatConnState
import com.threadprotection.app.chat.BtDeviceInfo
import com.threadprotection.app.chat.BtDeviceKind
import com.threadprotection.app.chat.ChatMode
import com.threadprotection.app.chat.SignalEstimate
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * Nearby-chat entry point — README: "chatting services will not going to work on internet, it
 * should work with Bluetooth... whosoever has application installed only those people can chat."
 * Bluetooth mode is real (discovery + a real RFCOMM connection + post-quantum handshake — see
 * BluetoothChatManager). Internet mode is honestly labelled as not available: reaching an
 * arbitrary other installed device by a random ID over the internet needs a relay server to
 * route messages between phones sitting behind NAT, which doesn't exist for this app.
 */
@Composable
fun ChatScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onSetChatMode: (ChatMode) -> Unit,
    onStartDiscovery: () -> Unit,
    onConnect: (String) -> Unit,
    onGoHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val context = LocalContext.current
    val scanPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) onStartDiscovery()
    }
    val requestScan: () -> Unit = {
        val granted = scanPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) onStartDiscovery() else permissionLauncher.launch(scanPermissions)
    }
    var showMaintenancePopup by remember { mutableStateOf(false) }

    if (showMaintenancePopup) {
        AlertDialog(
            onDismissRequest = { showMaintenancePopup = false },
            confirmButton = { TextButton(onClick = { showMaintenancePopup = false }) { Text("Got it") } },
            icon = { Icon(Icons.Filled.Public, contentDescription = null, tint = palette.warn) },
            title = { Text("Internet chat is under maintenance", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg) },
            text = {
                Text(
                    "This mode isn't ready yet — it needs a relay server to find and route messages between phones, which doesn't exist for this app yet. Bluetooth chat with nearby devices works right now.",
                    style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                    color = palette.muted,
                )
            },
            containerColor = palette.card,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Chat", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "Message someone nearby who also has Thread Protection installed — end-to-end encrypted with post-quantum cryptography (ML-KEM-768 + AES-256-GCM), no server involved.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeOption("Bluetooth", Icons.Filled.Bluetooth, state.chatMode == ChatMode.BLUETOOTH, Modifier.weight(1f)) {
                    onSetChatMode(ChatMode.BLUETOOTH)
                }
                ModeOption("Internet", Icons.Filled.Public, state.chatMode == ChatMode.INTERNET, Modifier.weight(1f)) {
                    onSetChatMode(ChatMode.INTERNET)
                    showMaintenancePopup = true
                }
            }

            if (state.chatMode == ChatMode.INTERNET) {
                InfoBanner(
                    title = "Under maintenance",
                    body = "Reaching any other installed device over the internet needs a relay server to find and route messages between phones — most phones have no public address of their own. That server doesn't exist for this app yet. Switch to Bluetooth to chat with someone nearby right now.",
                    tint = palette.warnTint06,
                    border = palette.warnBorder20,
                )
            } else {
                when (state.btConnState) {
                    BtChatConnState.BT_UNAVAILABLE -> InfoBanner(
                        title = "Bluetooth is off",
                        body = "Turn on Bluetooth in your phone's quick settings, then come back here.",
                        tint = palette.dangerTint08,
                        border = palette.dangerBorder30,
                    )
                    BtChatConnState.NO_PERMISSION -> InfoBanner(
                        title = "Bluetooth permission needed",
                        body = "Tap \"Scan for nearby devices\" again and allow the permission this app just asked for.",
                        tint = palette.warnTint06,
                        border = palette.warnBorder20,
                    )
                    BtChatConnState.BLE_UNSUPPORTED -> InfoBanner(
                        title = "Bluetooth Low Energy not available",
                        body = "This device's Bluetooth hardware doesn't support the BLE scanning nearby-device discovery needs.",
                        tint = palette.dangerTint08,
                        border = palette.dangerBorder30,
                    )
                    BtChatConnState.SCAN_FAILED -> InfoBanner(
                        title = "Scan failed",
                        body = "The Bluetooth scan couldn't start — try again in a moment.",
                        tint = palette.warnTint06,
                        border = palette.warnBorder20,
                    )
                    BtChatConnState.FAILED -> InfoBanner(
                        title = "Couldn't connect",
                        body = "That device didn't respond correctly — it may not have Thread Protection installed, or moved out of range.",
                        tint = palette.dangerTint08,
                        border = palette.dangerBorder30,
                    )
                    else -> Unit
                }
                if (state.btCanAdvertise == false) {
                    Text(
                        "This phone's Bluetooth hardware can't broadcast its own presence — you can still find and message other nearby devices, but they may not be able to find you.",
                        style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                        color = palette.muted,
                    )
                }

                val busy = state.btConnState == BtChatConnState.DISCOVERING || state.btConnState == BtChatConnState.CONNECTING || state.btConnState == BtChatConnState.HANDSHAKING
                RadarScanner(
                    connState = state.btConnState,
                    devicesFound = state.btDiscoveredDevices.size,
                    enabled = !busy,
                    onClick = requestScan,
                )

                HistoryEntryRow(count = state.chatHistory.size, onClick = onGoHistory)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading("Nearby devices")
                    if (state.btConnState == BtChatConnState.DISCOVERING) {
                        PulsingDot()
                        Text("live", style = TpType.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = palette.accent)
                    }
                }
                if (state.btDiscoveredDevices.isEmpty()) {
                    Text(
                        if (state.btConnState == BtChatConnState.DISCOVERING) "Looking for nearby devices…" else "No nearby devices found yet. Tap the scanner above — make sure the other phone has Bluetooth on and Thread Protection open.",
                        style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = palette.muted,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.btDiscoveredDevices
                            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                            .forEach { device -> DeviceRow(device, onClick = { onConnect(device.address) }) }
                    }
                }
            }
        }
        BottomNavBar(active = NavTab.CHAT, onHome = onBack, onQr = onGoQr, onChat = {}, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

@Composable
private fun ModeOption(label: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    val contentColor = if (active) palette.onAccent else palette.fg2
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(BorderStroke(1.5.dp, if (active) palette.accent else palette.line3), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(label, style = TpType.cardTitle.copy(fontSize = 16.sp), color = contentColor, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * The scan trigger, redesigned as a radar: expanding pulse rings while discovering (driven by the
 * *real* `BtChatConnState.DISCOVERING` state, not a decorative loop that runs regardless), a
 * Bluetooth glyph at the center, and a live "N devices found" readout tied to the actual size of
 * `btDiscoveredDevices` as it grows in real time.
 */
@Composable
private fun RadarScanner(connState: BtChatConnState, devicesFound: Int, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    val scanning = connState == BtChatConnState.DISCOVERING
    val busyLabel = when (connState) {
        BtChatConnState.DISCOVERING -> "SCANNING…"
        BtChatConnState.CONNECTING -> "CONNECTING…"
        BtChatConnState.HANDSHAKING -> "SECURING CONNECTION…"
        else -> "TAP TO SCAN"
    }
    val transition = rememberInfiniteTransition(label = "radar")
    val ring1 by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ring1")
    val ring2 by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, delayMillis = 900, easing = LinearEasing)), label = "ring2")

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(168.dp).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (scanning) {
                RadarRing(progress = ring1, color = palette.accent)
                RadarRing(progress = ring2, color = palette.accent)
            }
            Box(modifier = Modifier.size(104.dp).border(BorderStroke(1.5.dp, palette.accentBorder35), CircleShape))
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(palette.accentHover, palette.accent))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(34.dp))
            }
        }
        Text(busyLabel, style = TpType.badge.copy(fontSize = 13.sp), color = palette.accent, textAlign = TextAlign.Center)
        Text(
            "$devicesFound device${if (devicesFound == 1) "" else "s"} found",
            style = TpType.caption.copy(fontSize = 12.5.sp),
            color = palette.muted,
        )
    }
}

@Composable
private fun RadarRing(progress: Float, color: Color) {
    Box(
        modifier = Modifier
            .size(78.dp + (90.dp * progress))
            .alpha((1f - progress) * 0.55f)
            .border(BorderStroke(1.5.dp, color), CircleShape),
    )
}

@Composable
private fun InfoBanner(title: String, body: String, tint: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color) {
    val palette = LocalTpPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tint)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
        Text(body, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.5.sp), color = palette.muted)
    }
}

/** Compact "History" entry point — README: paired/previously-chatted devices are compressed
 *  behind one row here, opening the full list (ChatHistoryScreen) to pick someone and reconnect. */
@Composable
private fun HistoryEntryRow(count: Int, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card2)
            .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(palette.accentTint16),
            contentAlignment = Alignment.Center,
        ) {
            Text("🕘", fontSize = 18.sp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("History", style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
            Text(
                if (count == 0) "No conversations yet" else "$count device${if (count == 1) "" else "s"} you've chatted with",
                style = TpType.caption.copy(fontSize = 12.5.sp),
                color = palette.muted,
            )
        }
        Text("›", style = TpType.cardTitleBold.copy(fontSize = 20.sp), color = palette.muted)
    }
}

@Composable
private fun PulsingDot() {
    val palette = LocalTpPalette.current
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(modifier = Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(palette.accent))
}

@Composable
private fun DeviceRow(device: BtDeviceInfo, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(palette.accentTint16),
            contentAlignment = Alignment.Center,
        ) {
            Icon(device.kind.icon(), contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(device.name, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = palette.muted, modifier = Modifier.size(12.dp))
                Text(SignalEstimate.distanceLabel(device.rssi), style = TpType.caption.copy(fontSize = 12.sp), color = palette.muted)
                SignalBars(bars = SignalEstimate.bars(device.rssi))
            }
        }
        Text("Connect", style = TpType.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = palette.accent)
    }
}

private fun BtDeviceKind.icon(): ImageVector = when (this) {
    BtDeviceKind.PHONE -> Icons.Filled.PhoneAndroid
    BtDeviceKind.COMPUTER -> Icons.Filled.Laptop
    BtDeviceKind.AUDIO -> Icons.Filled.Headset
    BtDeviceKind.WEARABLE -> Icons.Filled.Watch
    BtDeviceKind.GENERIC -> Icons.Filled.Devices
}

/** Real signal-strength bars — filled count comes from the device's actual RSSI reading via
 *  SignalEstimate.bars(), same bucketing phones use for their own Wi-Fi/cellular icons. */
@Composable
private fun SignalBars(bars: Int) {
    val palette = LocalTpPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + i * 3).dp)
                    .background(if (i <= bars) palette.accent else palette.line3, RoundedCornerShape(1.dp)),
            )
        }
    }
}
