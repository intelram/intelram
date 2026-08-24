package com.threadprotection.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.data.QrVerdict
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.QrPhase
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.components.sweepLineFraction
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

@Composable
fun QrScannerScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
    onRescan: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val sample = DemoData.qrSamples.getOrElse(state.qrIndex) { DemoData.qrSamples[0] }
    val verdictColor = when (sample.verdict) {
        QrVerdict.SAFE -> palette.accent
        QrVerdict.WARN -> palette.warn
        QrVerdict.DANGER -> palette.danger
    }
    val verdictTint = when (sample.verdict) {
        QrVerdict.SAFE -> palette.accentTint12
        QrVerdict.WARN -> palette.warnTint12
        QrVerdict.DANGER -> palette.dangerTint12
    }
    val verdictBorder = when (sample.verdict) {
        QrVerdict.SAFE -> palette.accentBorder35
        QrVerdict.WARN -> palette.warnBorder35
        QrVerdict.DANGER -> palette.dangerBorder35
    }
    val verdictLabel = when (sample.verdict) {
        QrVerdict.SAFE -> "SAFE"
        QrVerdict.WARN -> "SUSPICIOUS"
        QrVerdict.DANGER -> "MALICIOUS"
    }
    val glyph = when (sample.verdict) {
        QrVerdict.SAFE -> "✓"
        QrVerdict.WARN -> "!"
        QrVerdict.DANGER -> "✕"
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("QR code scanner", style = TpType.screenTitle, color = palette.fg)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.bg0)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ViewfinderCorners(palette.accent)

                when (state.qrPhase) {
                    QrPhase.IDLE -> Column(
                        modifier = Modifier.padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Point the camera at a QR code", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg2, textAlign = TextAlign.Center)
                        Text(
                            "Every code is checked against all connected intelligence feeds before it opens.",
                            style = TpType.caption.copy(fontSize = 14.5.sp),
                            color = palette.muted2,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrPhase.SCANNING -> {
                        val fraction = sweepLineFraction()
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .offset(y = maxHeight * fraction)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, palette.accent, Color.Transparent),
                                        ),
                                    ),
                            )
                        }
                        Column(
                            modifier = Modifier.padding(horizontal = 34.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text("Checking code… ${state.qrProgress.coerceAtMost(100)}%", style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.accent)
                            Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)).background(palette.line)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((state.qrProgress.coerceAtMost(100)) / 100f)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(palette.accent),
                                )
                            }
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                QrCheckStep("Decoded payload", state.qrProgress > 15)
                                QrCheckStep("URL reputation · Safe Browsing", state.qrProgress > 40)
                                QrCheckStep("Live phishing feeds · OpenPhish", state.qrProgress > 65)
                                QrCheckStep("Host & certificate check", state.qrProgress > 90)
                            }
                        }
                    }

                    QrPhase.RESULT -> Column(
                        modifier = Modifier.padding(horizontal = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(verdictTint)
                                .border(BorderStroke(1.dp, verdictBorder), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(glyph, style = TpType.cardTitleBold.copy(fontSize = 32.sp), color = verdictColor)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(verdictTint)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(verdictLabel, style = TpType.badge.copy(letterSpacing = 1.35.sp), color = verdictColor)
                        }
                        Text(sample.title, style = TpType.cardTitleBold.copy(fontSize = 19.5.sp), color = palette.fg, textAlign = TextAlign.Center)
                        Text(
                            sample.url,
                            style = TpType.caption.copy(fontSize = 14.5.sp, fontFamily = FontFamily.Monospace),
                            color = palette.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (state.qrPhase == QrPhase.RESULT) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        sample.detail,
                        style = TpType.body.copy(fontSize = 16.sp, lineHeight = 25.6.sp),
                        color = palette.fg2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(palette.accent))
                        Text("Matched by ${sample.feed}", style = TpType.caption, color = palette.muted)
                    }
                }
            }

            if (state.qrPhase == QrPhase.IDLE) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading("Try a code")
                    val rows = DemoData.qrSamples.chunked(2)
                    rows.forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEachIndexed { i, q ->
                                val index = DemoData.qrSamples.indexOf(q)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(palette.card)
                                        .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
                                        .clickable { onPick(index) }
                                        .padding(horizontal = 14.dp, vertical = 13.dp),
                                ) {
                                    Text(q.label, style = TpType.cardTitle.copy(fontSize = 15.5.sp), color = palette.fg2)
                                }
                            }
                            if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.qrPhase) {
                QrPhase.RESULT -> {
                    if (sample.verdict == QrVerdict.SAFE) {
                        com.threadprotection.app.ui.components.PrimaryPillButton(text = sample.action, onClick = onRescan)
                    } else {
                        OutlinedPillButton(text = sample.action, onClick = onRescan, borderColor = verdictBorder, textColor = verdictColor)
                    }
                    OutlinedPillButton(text = "Scan another code", onClick = onRescan, borderColor = palette.line3)
                }
                QrPhase.SCANNING -> OutlinedPillButton(text = "Cancel", onClick = onRescan, borderColor = palette.line3)
                QrPhase.IDLE -> {}
            }
        }

        BottomNavBar(active = NavTab.QR, onHome = onBack, onQr = {}, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

@Composable
private fun ViewfinderCorners(color: Color) {
    Box(modifier = Modifier.fillMaxSize()) {
        val s = 34.dp
        Box(modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(s)) { CornerBracket(color, top = true, start = true) }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(s)) { CornerBracket(color, top = true, start = false) }
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(s)) { CornerBracket(color, top = false, start = true) }
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(s)) { CornerBracket(color, top = false, start = false) }
    }
}

/** Draws an L-shaped bracket (two edges of a rounded corner) — README's QR viewfinder corners. */
@Composable
private fun CornerBracket(color: Color, top: Boolean, start: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 3.dp.toPx()
        val radius = 8.dp.toPx()
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            when {
                top && start -> {
                    moveTo(0f, h)
                    lineTo(0f, radius)
                    quadraticBezierTo(0f, 0f, radius, 0f)
                    lineTo(w, 0f)
                }
                top && !start -> {
                    moveTo(0f, 0f)
                    lineTo(w - radius, 0f)
                    quadraticBezierTo(w, 0f, w, radius)
                    lineTo(w, h)
                }
                !top && start -> {
                    moveTo(0f, 0f)
                    lineTo(0f, h - radius)
                    quadraticBezierTo(0f, h, radius, h)
                    lineTo(w, h)
                }
                else -> {
                    moveTo(w, 0f)
                    lineTo(w, h - radius)
                    quadraticBezierTo(w, h, w - radius, h)
                    lineTo(0f, h)
                }
            }
        }
        drawPath(path, color = color, style = Stroke(width = strokeWidth))
    }
}

@Composable
private fun QrCheckStep(name: String, done: Boolean) {
    val palette = LocalTpPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (done) palette.accent else palette.line3))
        Text(name, style = TpType.caption.copy(fontSize = 14.sp), color = if (done) palette.fg2 else palette.muted3)
    }
}
