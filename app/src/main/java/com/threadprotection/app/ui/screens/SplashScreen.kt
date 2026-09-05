package com.threadprotection.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import kotlinx.coroutines.delay

/** Real phase labels borrowed verbatim from `scan/DeviceScanner.kt`'s actual pipeline — cycling
 *  through them here previews the real scan about to run rather than inventing filler copy. Purely
 *  decorative ordering (the splash's own "checks" counter is a simulated warm-up, not a live scan;
 *  see [SplashScreen]'s doc), so if `DeviceScanner`'s phases ever change, update both. */
private val SPLASH_PHASES = listOf(
    "Building software inventory…",
    "Scanning apps & sideloaded APKs…",
    "Auditing app permissions…",
    "Checking connected hardware…",
    "Probing open ports & listeners…",
    "Verifying OS build & patch level…",
    "Checking live threat-intelligence feeds…",
)

/**
 * Branded launch splash: a faceted hexagon "gem" mark with a horizontal scan-line and glowing
 * center dot, a rotating radar sweep and breathing glow around it, plus a simulated readiness
 * check (cycling through the real upcoming scan phases) so the very first thing a user sees
 * already feels like the security product warming up. Purely vector-drawn (Canvas), so it stays
 * crisp at any density — no bitmap to scale up. Background follows the same night/day palette as
 * the rest of the app (see ThreadProtectionTheme), so it's automatically dark on a dark-mode
 * device and light otherwise.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val palette = LocalTpPalette.current
    var checks by remember { mutableIntStateOf(0) }
    val targetChecks = 1204
    val progress by animateFloatAsState(
        targetValue = checks / targetChecks.toFloat(),
        animationSpec = tween(220, easing = LinearEasing),
        label = "splashProgress",
    )
    val phase = SPLASH_PHASES[(progress * SPLASH_PHASES.size).toInt().coerceIn(0, SPLASH_PHASES.lastIndex)]

    val infiniteTransition = rememberInfiniteTransition(label = "splashMotion")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "radarSweep",
    )
    val haloPulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "haloPulse",
    )

    LaunchedEffect(Unit) {
        val stepDelayMs = 18L
        while (checks < targetChecks) {
            delay(stepDelayMs)
            checks = (checks + (8..20).random()).coerceAtMost(targetChecks)
        }
        delay(350)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                RadarSweepRing(size = 176.dp, angleDeg = sweepAngle, accent = palette.accent)
                HexagonGemIcon(size = 132.dp, haloPulse = haloPulse)
            }

            Text(
                text = "Threat Intelligence",
                style = TpType.splashTitle,
                color = palette.fg,
                textAlign = TextAlign.Center,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "SCANNING · ${"%,d".format(checks)} CHECKS",
                    style = TpType.splashCaption,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                )

                Crossfade(targetState = phase, label = "splashPhase") { label ->
                    Text(
                        text = label.uppercase(),
                        style = TpType.splashCaption.copy(fontSize = TpType.splashCaption.fontSize * 0.8f),
                        color = palette.accent,
                        textAlign = TextAlign.Center,
                    )
                }

                val barWidth = 180.dp
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.line),
                ) {
                    Box(
                        modifier = Modifier
                            .width(barWidth * progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(palette.accent, palette.accentHover))),
                    )
                }
            }
        }
    }
}

/**
 * The exact same branded visual as [SplashScreen] (hexagon gem, radar sweep, "Threat Intelligence"
 * title, progress bar) but driven entirely by the real, live scan already running in
 * `AppViewModel.runAutoScanOnSplash()` rather than a simulated counter. No internal timer — this
 * composable is purely reactive to whatever real progress the caller passes in; the ViewModel
 * itself decides when the real scan is done and moves off `Screen.SPLASH`.
 *
 * User-requested, explicitly and repeatedly: opening the app should show exactly one continuous
 * scanning screen — this one — and land straight on the homepage when it finishes, not hand off to
 * a second, separately-styled "now scanning" screen (`ScanningScreen`) first. See
 * `AppViewModel.runAutoScanOnSplash()`'s doc for the full flow.
 */
@Composable
fun RealScanSplashScreen(progressPct: Float, scannedCount: Int, phaseLabel: String?) {
    val palette = LocalTpPalette.current
    val smoothProgress by animateFloatAsState(
        targetValue = (progressPct / 100f).coerceIn(0f, 1f),
        animationSpec = tween(220, easing = LinearEasing),
        label = "realScanProgress",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "realScanMotion")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "realScanRadarSweep",
    )
    val haloPulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "realScanHaloPulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                RadarSweepRing(size = 176.dp, angleDeg = sweepAngle, accent = palette.accent)
                HexagonGemIcon(size = 132.dp, haloPulse = haloPulse)
            }

            Text(
                text = "Threat Intelligence",
                style = TpType.splashTitle,
                color = palette.fg,
                textAlign = TextAlign.Center,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "SCANNING · ${"%,d".format(scannedCount)} CHECKS",
                    style = TpType.splashCaption,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                )

                Crossfade(targetState = phaseLabel?.takeIf { it.isNotBlank() } ?: "Starting scan…", label = "realScanPhase") { label ->
                    Text(
                        text = label.uppercase(),
                        style = TpType.splashCaption.copy(fontSize = TpType.splashCaption.fontSize * 0.8f),
                        color = palette.accent,
                        textAlign = TextAlign.Center,
                    )
                }

                val barWidth = 180.dp
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.line),
                ) {
                    Box(
                        modifier = Modifier
                            .width(barWidth * smoothProgress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(palette.accent, palette.accentHover))),
                    )
                }
            }
        }
    }
}

/** A faint static ring plus one brighter arc that continuously rotates around it — the classic
 *  "radar sweep" read as scanning activity, framing the hexagon without competing with it. */
@Composable
private fun RadarSweepRing(size: Dp, angleDeg: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.width * 0.01f
        val radius = this.size.minDimension / 2f - strokeWidth
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = accent.copy(alpha = 0.16f), radius = radius, center = center, style = Stroke(width = strokeWidth))
        rotate(degrees = angleDeg, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        accent.copy(alpha = 0f),
                        accent.copy(alpha = 0f),
                        accent.copy(alpha = 0.85f),
                        accent.copy(alpha = 0f),
                    ),
                    center = center,
                ),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth * 1.8f),
            )
        }
    }
}

@Composable
private fun HexagonGemIcon(size: Dp, haloPulse: Float = 1f) {
    val rimLight = Color(0xFFF2F5F6)
    val rimDark = Color(0xFF4B535B)
    val underside = Color(0xFF0A1F16)
    val facetA = Color(0xFFB9C0C5)
    val facetB = Color(0xFF969DA3)
    val facetC = Color(0xFF6E767D)
    val facetD = Color(0xFF454C53)
    val facetE = Color(0xFF5D656C)
    val facetF = Color(0xFF848B92)
    val accent = Color(0xFF3DDC84)
    val dotCore = Color(0xFFFFFFFF)

    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h * 0.47f
        val r = w * 0.46f

        fun vertex(angleDeg: Double): Offset {
            val rad = Math.toRadians(angleDeg)
            return Offset(cx + (r * kotlin.math.cos(rad)).toFloat(), cy - (r * kotlin.math.sin(rad)).toFloat())
        }

        val v0 = vertex(90.0)
        val v1 = vertex(150.0)
        val v2 = vertex(210.0)
        val v3 = vertex(270.0)
        val v4 = vertex(330.0)
        val v5 = vertex(30.0)

        fun hexPath(vertices: List<Offset>, dy: Float = 0f): Path = Path().apply {
            moveTo(vertices[0].x, vertices[0].y + dy)
            for (i in 1 until vertices.size) lineTo(vertices[i].x, vertices[i].y + dy)
            close()
        }
        val outerVertices = listOf(v0, v5, v4, v3, v2, v1)

        // ambient halo behind everything — breathes with haloPulse rather than sitting static
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.30f * haloPulse), accent.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = r * 1.9f,
            ),
            radius = r * 1.9f,
            center = Offset(cx, cy),
        )

        // underside / thickness sliver, offset down, peeks out at the bottom
        drawPath(hexPath(outerVertices, dy = h * 0.055f), color = underside)

        // outer metallic rim
        drawPath(
            path = hexPath(outerVertices),
            brush = Brush.linearGradient(listOf(rimLight, rimDark)),
        )
        drawPath(hexPath(outerVertices), color = rimLight, style = Stroke(width = w * 0.012f))

        // inner faceted face (inset ~14% toward center)
        val insetFactor = 0.86f
        fun inset(p: Offset) = Offset(cx + (p.x - cx) * insetFactor, cy + (p.y - cy) * insetFactor)
        val i0 = inset(v0); val i1 = inset(v1); val i2 = inset(v2)
        val i3 = inset(v3); val i4 = inset(v4); val i5 = inset(v5)
        val center = Offset(cx, cy)

        fun facet(a: Offset, b: Offset, color: Color) {
            drawPath(Path().apply { moveTo(center.x, center.y); lineTo(a.x, a.y); lineTo(b.x, b.y); close() }, color = color)
        }
        facet(i0, i1, facetA)
        facet(i1, i2, facetB)
        facet(i2, i3, facetC)
        facet(i3, i4, facetD)
        facet(i4, i5, facetE)
        facet(i5, i0, facetF)

        // glow behind the beam / dot
        val beamHalfW = r * 0.86f
        val beamGlowHeight = h * 0.09f
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0f), accent.copy(alpha = 0.55f), accent.copy(alpha = 0f)),
                startX = cx - beamHalfW,
                endX = cx + beamHalfW,
            ),
            topLeft = Offset(cx - beamHalfW, cy - beamGlowHeight / 2),
            size = androidx.compose.ui.geometry.Size(beamHalfW * 2, beamGlowHeight),
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), accent.copy(alpha = 0f)), center = center, radius = r * 0.32f),
            radius = r * 0.32f,
            center = center,
        )

        // crisp horizontal scan beam
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    accent.copy(alpha = 0f),
                    accent.copy(alpha = 0.9f),
                    Color(0xFFEAFFF3),
                    accent.copy(alpha = 0.9f),
                    accent.copy(alpha = 0f),
                ),
                startX = cx - beamHalfW,
                endX = cx + beamHalfW,
            ),
            topLeft = Offset(cx - beamHalfW, cy - h * 0.017f),
            size = androidx.compose.ui.geometry.Size(beamHalfW * 2, h * 0.034f),
        )

        // center dot
        drawCircle(
            brush = Brush.radialGradient(listOf(dotCore, accent), center = center, radius = r * 0.11f),
            radius = r * 0.11f,
            center = center,
        )
    }
}
