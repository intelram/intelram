package com.threadprotection.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Conic-gradient progress ring (`conic-gradient(<color> <deg>, <line> <deg>)`) with a punched-out
 * inner circle — README §Animations "Progress rings are conic gradients". Used for both the
 * dashboard score ring (200dp, inset 14dp) and the scan ring (220dp, inset 14dp).
 */
@Composable
fun ConicProgressRing(
    size: Dp,
    inset: Dp,
    progressFraction: Float,
    activeColor: Color,
    trackColor: Color,
    innerBackground: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    innerContent: @Composable () -> Unit = {},
) {
    // Scanning screen updates progress dozens of times a second; an extra spring on top
    // of the state's own animation just adds lag, so it opts out via `animate = false`.
    val animated = if (animate) {
        val target by animateFloatAsState(progressFraction.coerceIn(0f, 1f), tween(300), label = "ringProgress")
        target
    } else {
        progressFraction.coerceIn(0f, 1f)
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = inset.toPx()
            val diameter = min(this.size.width, this.size.height) - strokeWidth
            val topLeft = Offset((this.size.width - diameter) / 2, (this.size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            val sweep = 360f * animated
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
            if (sweep > 0f) {
                drawArc(
                    color = activeColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(inset)
                .fillMaxSize()
                .clip(CircleShape)
                .background(innerBackground),
            contentAlignment = Alignment.Center,
        ) {
            innerContent()
        }
    }
}
