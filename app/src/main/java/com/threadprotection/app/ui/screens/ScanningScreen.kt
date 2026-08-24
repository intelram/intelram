package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.state.ScanPhaseState
import com.threadprotection.app.ui.components.ConicProgressRing
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.RadarSweep
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import kotlin.math.floor

@Composable
fun ScanningScreen(
    progress: Float,
    scannedCount: Int,
    phase: ScanPhaseState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current

    Column(
        modifier = modifier.fillMaxSize().padding(top = 28.dp, start = 24.dp, end = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ConicProgressRing(
                size = 220.dp,
                inset = 14.dp,
                progressFraction = progress / 100f,
                activeColor = palette.accent,
                trackColor = palette.line,
                innerBackground = palette.bg2,
                animate = false,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    RadarSweep(color = palette.accent, modifier = Modifier.size(192.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${floor(progress).toInt()}%", style = TpType.scoreNumber.copy(fontSize = 51.sp), color = palette.accent)
                        Text("${String.format("%,d", scannedCount)} items checked", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                    }
                }
            }
            Column(
                modifier = Modifier.padding(top = 28.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        phase.label.ifBlank { "Starting scan…" },
                        style = TpType.cardTitle.copy(fontSize = 19.5.sp),
                        color = palette.fg,
                        textAlign = TextAlign.Center,
                    )
                    Text(phase.meta, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted, textAlign = TextAlign.Center)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until phase.total).forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        i < phase.index -> palette.accent
                                        i == phase.index -> palette.fg
                                        else -> palette.line3
                                    },
                                ),
                        )
                    }
                }
            }
        }
        OutlinedPillButton(text = "Cancel scan", onClick = onCancel, borderColor = palette.line3)
    }
}
