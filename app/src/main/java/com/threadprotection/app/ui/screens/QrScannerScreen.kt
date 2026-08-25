package com.threadprotection.app.ui.screens

import android.Manifest
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.network.UrlVerdict
import com.threadprotection.app.network.Verdict
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.QrPhase
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.QrCameraPreview
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.components.TechnicalDetailsCard
import com.threadprotection.app.ui.components.sweepLineFraction
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QrScannerScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
    onDecoded: (String) -> Unit,
    onRescan: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val verdict = state.qrVerdict

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
                when (state.qrPhase) {
                    QrPhase.IDLE -> {
                        if (cameraPermission.status.isGranted) {
                            QrCameraPreview(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)), onDecoded = onDecoded)
                            ViewfinderCorners(palette.accent)
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Point at a QR code — it's checked automatically",
                                    style = TpType.caption.copy(fontSize = 13.sp),
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            ViewfinderCorners(palette.accent)
                            Column(
                                modifier = Modifier.padding(horizontal = 36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text("Point the camera at a QR code", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg2, textAlign = TextAlign.Center)
                                Text(
                                    "Every code is decoded on this phone, then checked against every intelligence feed you've configured.",
                                    style = TpType.caption.copy(fontSize = 14.5.sp),
                                    color = palette.muted2,
                                    textAlign = TextAlign.Center,
                                )
                                OutlinedPillButton(
                                    text = "Grant camera access",
                                    onClick = { cameraPermission.launchPermissionRequest() },
                                    borderColor = palette.accentBorder40,
                                    textColor = palette.accent,
                                )
                            }
                        }
                    }

                    QrPhase.SCANNING -> {
                        ViewfinderCorners(palette.accent)
                        val fraction = sweepLineFraction()
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .offset(y = maxHeight * fraction)
                                    .background(Brush.horizontalGradient(listOf(Color.Transparent, palette.accent, Color.Transparent))),
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
                                QrCheckStep("Decoded payload", state.qrProgress > 10)
                                QrCheckStep("On-device link analysis", state.qrProgress > 30)
                                QrCheckStep("Live threat-intel feeds", state.qrProgress > 55)
                                QrCheckStep("Combining verdicts", state.qrProgress > 80)
                            }
                        }
                    }

                    QrPhase.RESULT -> if (verdict != null) {
                        val vc = verdictColors(verdict.overall)
                        Column(
                            modifier = Modifier.padding(horizontal = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(62.dp).clip(CircleShape).background(vc.tint).border(BorderStroke(1.dp, vc.border), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(vc.glyph, style = TpType.cardTitleBold.copy(fontSize = 32.sp), color = vc.color)
                            }
                            Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(vc.tint).padding(horizontal = 12.dp, vertical = 5.dp)) {
                                Text(vc.label, style = TpType.badge.copy(letterSpacing = 1.35.sp), color = vc.color)
                            }
                            Text(vc.title, style = TpType.cardTitleBold.copy(fontSize = 19.5.sp), color = palette.fg, textAlign = TextAlign.Center)
                            Text(
                                verdict.url,
                                style = TpType.caption.copy(fontSize = 13.5.sp, fontFamily = FontFamily.Monospace),
                                color = palette.muted,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }

            if (state.qrPhase == QrPhase.RESULT && verdict != null) {
                QrResultDetails(verdict)
            }

            if (state.qrPhase == QrPhase.IDLE) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading("Try a code")
                    DemoData.qrSamples.chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { q ->
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
                    val vc = verdict?.let { verdictColors(it.overall) }
                    if (vc != null && verdict?.overall == Verdict.SAFE) {
                        PrimaryPillButton(text = "Open link", onClick = onRescan)
                    } else if (vc != null) {
                        OutlinedPillButton(text = "Don't open — go back", onClick = onRescan, borderColor = vc.border, textColor = vc.color)
                    }
                    OutlinedPillButton(text = "Scan another code", onClick = onRescan, borderColor = palette.line3)
                }
                QrPhase.SCANNING -> OutlinedPillButton(text = "Cancel", onClick = onRescan, borderColor = palette.line3)
                QrPhase.IDLE -> {}
            }
        }

        BottomNavBar(active = NavTab.QR, onHome = onBack, onQr = {}, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

private data class VerdictColors(val color: Color, val tint: Color, val border: Color, val label: String, val glyph: String, val title: String)

@Composable
private fun verdictColors(verdict: Verdict): VerdictColors {
    val palette = LocalTpPalette.current
    return when (verdict) {
        Verdict.SAFE -> VerdictColors(palette.accent, palette.accentTint12, palette.accentBorder35, "SAFE", "✓", "Looks safe to open")
        Verdict.SUSPICIOUS -> VerdictColors(palette.warn, palette.warnTint12, palette.warnBorder35, "SUSPICIOUS", "!", "Suspicious — proceed with care")
        Verdict.MALICIOUS -> VerdictColors(palette.danger, palette.dangerTint12, palette.dangerBorder35, "MALICIOUS", "✕", "Malicious — don't open this")
        Verdict.UNKNOWN -> VerdictColors(palette.muted, palette.mutedTint14, palette.line3, "UNKNOWN", "?", "Not enough data to verify")
    }
}

@Composable
private fun QrResultDetails(verdict: UrlVerdict) {
    val palette = LocalTpPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (verdict.onDeviceFlags.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("On-device analysis", style = TpType.sectionHeading.copy(fontSize = 12.5.sp), color = palette.muted)
                verdict.onDeviceFlags.forEach { flag ->
                    Text("• $flag", style = TpType.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = palette.fg2)
                }
            }
        }
        if (verdict.signals.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp)),
            ) {
                verdict.signals.forEachIndexed { i, signal ->
                    Column {
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(signal.source, style = TpType.cardTitle.copy(fontSize = 15.sp), color = palette.fg)
                            Text(signal.detail, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
        } else {
            Text(
                "No live threat-intel sources are configured yet — add free API keys in Settings for stronger results.",
                style = TpType.caption.copy(fontSize = 13.5.sp),
                color = palette.muted,
            )
        }
        Text("Matched by ${verdict.matchedBy} · ${verdict.confidence}% confidence", style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted2)
        verdict.technical?.let { tech -> TechnicalDetailsCard(tech) }
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
