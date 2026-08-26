package com.threadprotection.app.ui.screens

import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.state.ScanFeedEntry
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
    feed: List<ScanFeedEntry> = emptyList(),
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current

    Column(
        modifier = modifier.fillMaxSize().padding(top = 22.dp, start = 24.dp, end = 24.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConicProgressRing(
            size = 168.dp,
            inset = 11.dp,
            progressFraction = progress / 100f,
            activeColor = palette.accent,
            trackColor = palette.line,
            innerBackground = palette.bg2,
            animate = false,
        ) {
            Box(contentAlignment = Alignment.Center) {
                RadarSweep(color = palette.accent, modifier = Modifier.size(144.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${floor(progress).toInt()}%", style = TpType.scoreNumber.copy(fontSize = 38.sp), color = palette.accent)
                    Text("${String.format("%,d", scannedCount)} checked", style = TpType.caption.copy(fontSize = 12.sp), color = palette.muted)
                }
            }
        }
        Column(
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    phase.label.ifBlank { "Starting scan…" },
                    style = TpType.cardTitle.copy(fontSize = 17.5.sp),
                    color = palette.fg,
                    textAlign = TextAlign.Center,
                )
                Text(phase.meta, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted, textAlign = TextAlign.Center)
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
        LiveScanFeed(feed = feed, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp, bottom = 12.dp))
        OutlinedPillButton(text = "Cancel scan", onClick = onCancel, borderColor = palette.line3)
    }
}

/**
 * A genuinely live readout — every row is a real item this device's scan pipeline just checked
 * (an installed package, a listening port, a piece of connected hardware), reported the instant
 * `DeviceScanner` reaches it. Nothing here is pre-scripted or fabricated; it's just the scan's own
 * progress callback rendered as a scrolling feed instead of a single line of text.
 */
@Composable
private fun LiveScanFeed(feed: List<ScanFeedEntry>, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    val infinite = rememberInfiniteTransition(label = "liveDot")
    val dotAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse),
        label = "liveDotAlpha",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = dotAlpha)),
            )
            Text("LIVE — reading your device now", style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = palette.muted)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(feed, key = { it.id }) { entry ->
                Text(
                    entry.text,
                    style = TpType.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = if (entry.id == feed.firstOrNull()?.id) palette.fg else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (feed.isEmpty()) {
                item {
                    Text("Preparing live feed…", style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted)
                }
            }
        }
    }
}
