package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.Category
import com.threadprotection.app.scan.DeviceInfo
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.DetailRow
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** Drills into the "Operating system" system-audit tile — real `Build.*` fields, read on-device right now. */
@Composable
fun OperatingSystemScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val info = remember { DeviceInfo.current() }
    val osFinding = state.scanData.findings.firstOrNull { it.cat == Category.OS }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Operating system", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "This device's real build and patch information, read straight from Android.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            if (osFinding != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.warnTint06)
                        .border(BorderStroke(1.dp, palette.warnBorder20), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(osFinding.name, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
                    Text(osFinding.advice, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.5.sp), color = palette.muted)
                }
            } else if (state.hasScanned) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.accentTint08)
                        .border(BorderStroke(1.dp, palette.accentBorder25), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                ) {
                    Text("Security patch is recent — nothing to flag here.", style = TpType.cardTitle.copy(fontSize = 15.5.sp), color = palette.fg)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.card2)
                    .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Device & build", style = TpType.cardTitle.copy(fontSize = 15.sp), color = palette.fg)
                DetailRow("Manufacturer", info.manufacturer)
                DetailRow("Model", info.model)
                DetailRow("Android version", "${info.androidVersion} (API ${info.sdkInt})")
                DetailRow("Security patch", info.securityPatch)
                DetailRow("Kernel", info.kernelVersion)
                DetailRow("Build ID", info.buildFingerprint)
            }

            OutlinedPillButton(text = "Check for system updates", onClick = onCheckForUpdates, borderColor = palette.line3)
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}
