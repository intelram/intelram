package com.threadprotection.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.threadprotection.app.chat.BtChatConnState
import com.threadprotection.app.chat.BtDeviceInfo
import com.threadprotection.app.chat.ChatMode
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.PrimaryPillButton
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
                ModeOption("Bluetooth", state.chatMode == ChatMode.BLUETOOTH, Modifier.weight(1f)) { onSetChatMode(ChatMode.BLUETOOTH) }
                ModeOption("Internet", state.chatMode == ChatMode.INTERNET, Modifier.weight(1f)) { onSetChatMode(ChatMode.INTERNET) }
            }

            if (state.chatMode == ChatMode.INTERNET) {
                InfoBanner(
                    title = "Not available yet",
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
                    BtChatConnState.FAILED -> InfoBanner(
                        title = "Couldn't connect",
                        body = "That device didn't respond correctly — it may not have Thread Protection installed, or moved out of range.",
                        tint = palette.dangerTint08,
                        border = palette.dangerBorder30,
                    )
                    else -> Unit
                }

                val busy = state.btConnState == BtChatConnState.DISCOVERING || state.btConnState == BtChatConnState.CONNECTING || state.btConnState == BtChatConnState.HANDSHAKING
                PrimaryPillButton(
                    text = when (state.btConnState) {
                        BtChatConnState.DISCOVERING -> "Scanning…"
                        BtChatConnState.CONNECTING -> "Connecting…"
                        BtChatConnState.HANDSHAKING -> "Securing connection…"
                        else -> "Scan for nearby devices"
                    },
                    onClick = requestScan,
                    enabled = !busy,
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
                        if (state.btConnState == BtChatConnState.DISCOVERING) "Looking for nearby devices…" else "No nearby devices found yet. Make sure the other phone has Bluetooth on and Thread Protection open.",
                        style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = palette.muted,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.btDiscoveredDevices.forEach { device -> DeviceRow(device, onClick = { onConnect(device.address) }) }
                    }
                }
            }
        }
        BottomNavBar(active = NavTab.CHAT, onHome = onBack, onQr = onGoQr, onChat = {}, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

@Composable
private fun ModeOption(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
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
        Text(label, style = TpType.cardTitle.copy(fontSize = 16.sp), color = if (active) palette.onAccent else palette.fg2)
    }
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
            Text("💬", fontSize = 18.sp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(device.name, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
            Text(if (device.bonded) "Paired" else "Nearby", style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted)
        }
        Text("Connect", style = TpType.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = palette.accent)
    }
}
