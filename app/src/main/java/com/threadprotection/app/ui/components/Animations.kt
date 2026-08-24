package com.threadprotection.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** opacity 1 → .25 → 1, README animation `blink`. */
@Composable
fun blinkAlpha(durationMs: Int = 1600): Float {
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs / 2, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blinkAlpha",
    )
    return alpha
}

@Composable
fun BlinkingDot(size: Dp, color: Color, modifier: Modifier = Modifier, durationMs: Int = 1600) {
    val alpha = blinkAlpha(durationMs)
    Box(modifier = modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

/** Two rings scaling 1 → 1.9 with fading opacity, offset 1.2s — README animation `pulse`. */
@Composable
fun PulsingRings(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size)) {
        PulseRing(size, color, delayMs = 0)
        PulseRing(size, color, delayMs = 1200)
    }
}

@Composable
private fun PulseRing(size: Dp, color: Color, delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "pulseRing")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMs, StartOffsetType.FastForward),
        ),
        label = "pulseProgress",
    )
    val scale = 1f + progress * 0.9f
    val alpha = 0.5f * (1f - progress)
    Canvas(modifier = Modifier.fillMaxSize().scale(scale).alpha(alpha)) {
        drawCircle(color = color, style = Stroke(width = 1.dp.toPx()))
    }
}

/** Three orbiting glow dots around the sign-in / onboarding shield — README animation `spin`. */
@Composable
fun OrbitDots(containerSize: Dp, modifier: Modifier = Modifier, colors: Triple<Color, Color, Color>) {
    Box(modifier = modifier.size(containerSize)) {
        OrbitDot(containerSize, 10.dp, colors.first, durationMs = 7000, delayMs = 0)
        OrbitDot(containerSize, 7.dp, colors.second, durationMs = 11000, delayMs = -3000)
        OrbitDot(containerSize, 6.dp, colors.third, durationMs = 9000, delayMs = -5000)
    }
}

@Composable
private fun OrbitDot(containerSize: Dp, dotSize: Dp, color: Color, durationMs: Int, delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(-delayMs, StartOffsetType.FastForward),
        ),
        label = "orbitAngle",
    )
    Box(modifier = Modifier.size(containerSize).rotate(angle)) {
        Box(
            modifier = Modifier
                .offset(x = containerSize / 2 - dotSize / 2, y = -dotSize / 2)
                .size(dotSize)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** Rotating radar wedge inside the scan ring — README animation `spin` (2.2s). */
@Composable
fun RadarSweep(modifier: Modifier = Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing)),
        label = "radarAngle",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        rotateScope(angle) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to color.copy(alpha = 0.28f),
                    0.25f to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(size.width / 2, size.height / 2),
                ),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = true,
            )
        }
    }
}

/** Vertical scan line sweeping 6% → 92% → 6% of the viewfinder — README animation `sweepline`. */
@Composable
fun sweepLineFraction(): Float {
    val transition = rememberInfiniteTransition(label = "sweepline")
    val fraction by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweepFraction",
    )
    return fraction
}
