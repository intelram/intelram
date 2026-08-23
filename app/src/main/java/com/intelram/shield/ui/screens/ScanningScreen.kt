package com.intelram.shield.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intelram.shield.ui.components.SecondaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.Surface

private val checklist = listOf(
    22 to "App Malware Scan",
    40 to "Privacy & Permissions",
    58 to "Wi-Fi & Network Check",
    88 to "System & Software Audit",
)

@Composable
fun ScanningScreen(progress: Int, stepLabel: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 24.dp)
            .padding(top = 52.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Scanning your device", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(24.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RadarRings()
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .background(Surface, shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$progress%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stepLabel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "This usually takes under a minute — feel free to keep using your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                checklist.forEach { (threshold, label) ->
                    ChecklistRow(label = label, done = progress >= threshold, active = progress < threshold && (checklist.firstOrNull { progress < it.first }?.first == threshold))
                }
            }
        }

        Spacer(Modifier.weight(1f))
        SecondaryButton(text = "Cancel Scan", onClick = onCancel)
    }
}

@Composable
private fun ChecklistRow(label: String, done: Boolean, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GreenDark, modifier = Modifier.size(20.dp))
            active -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp, color = GreenDark)
            else -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = InkFaint.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RadarRings() {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "radarProgress",
    )
    Canvas(modifier = Modifier.size(168.dp)) {
        val maxRadius = size.minDimension / 2
        listOf(0f, 0.33f, 0.66f).forEach { offset ->
            val p = (progress + offset) % 1f
            drawCircle(
                color = Green.copy(alpha = (1f - p) * 0.5f),
                radius = maxRadius * (0.35f + p * 0.65f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
