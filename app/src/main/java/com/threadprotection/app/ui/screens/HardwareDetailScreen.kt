package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** Drills into the "Connected hardware" system-audit tile — every device `HardwareWatcher` actually found this scan. */
@Composable
fun HardwareDetailScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val devices = state.liveHwDevices
    val bad = devices.count { !it.ok }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Connected hardware", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "USB, Bluetooth, SIM and power state, read straight from Android's hardware managers right now.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            if (devices.isEmpty()) {
                Text(
                    if (state.hasScanned) "No connected hardware detected." else "Run a scan from the dashboard to check connected hardware.",
                    style = TpType.body.copy(fontSize = 15.sp),
                    color = palette.muted,
                )
            } else {
                Text(
                    if (bad > 0) "$bad of ${devices.size} device${if (devices.size == 1) "" else "s"} need attention" else "All ${devices.size} devices look normal",
                    style = TpType.cardTitle.copy(fontSize = 16.5.sp),
                    color = if (bad > 0) palette.danger else palette.accent,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.card)
                                .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (device.ok) palette.accent else palette.danger))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(device.name, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
                                Text(device.detail, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted)
                            }
                            Text(
                                if (device.ok) "OK" else "Check",
                                style = TpType.caption.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = if (device.ok) palette.accent else palette.danger,
                            )
                        }
                    }
                }
            }

            OutlinedPillButton(text = "Open Bluetooth settings", onClick = onOpenBluetoothSettings, borderColor = palette.line3)
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}
