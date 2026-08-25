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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** Drills into the "Open ports" system-audit tile — every listening socket `/proc/net/tcp[6]` actually reported this scan. */
@Composable
fun OpenPortsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val ports = state.scanData.ports
    val probed = state.scanData.portsProbed

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Open ports", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "Every TCP socket this phone is actively listening on, and which app owns it — read from the real socket table, not a guess.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            if (!probed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.mutedTint14)
                        .border(BorderStroke(1.dp, palette.line3), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Restricted on this Android version", style = TpType.cardTitle.copy(fontSize = 15.5.sp), color = palette.fg)
                    Text(
                        "Android 10+ sandboxes /proc/net/tcp to an app's own connections for non-privileged apps — this isn't something Thread Protection can see around, and it isn't pretending to.",
                        style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                        color = palette.muted,
                    )
                }
            } else if (ports.isEmpty()) {
                Text(
                    if (state.hasScanned) "No listening ports found." else "Run a scan from the dashboard to check open ports.",
                    style = TpType.body.copy(fontSize = 15.sp),
                    color = palette.muted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ports.forEach { p ->
                        val risky = p.port == 5555
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (risky) palette.dangerTint08 else palette.card)
                                .border(BorderStroke(1.dp, if (risky) palette.dangerBorder30 else palette.line), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (risky) palette.danger else palette.accent))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Port ${p.port}", style = TpType.cardTitleBold.copy(fontSize = 16.sp, fontFamily = FontFamily.Monospace), color = palette.fg)
                                Text(p.ownerLabel, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted)
                            }
                            if (risky) {
                                Text("ADB", style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = palette.danger)
                            }
                        }
                    }
                }
            }

            OutlinedPillButton(text = "Open Developer options", onClick = onOpenDeveloperOptions, borderColor = palette.line3)
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}
