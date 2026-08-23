package com.intelram.shield.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intelram.shield.scan.RiskLevel
import com.intelram.shield.ui.theme.Amber
import com.intelram.shield.ui.theme.AmberSoft
import com.intelram.shield.ui.theme.Blue
import com.intelram.shield.ui.theme.BlueSoft
import com.intelram.shield.ui.theme.Border
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.Orange
import com.intelram.shield.ui.theme.OrangeSoft
import com.intelram.shield.ui.theme.Red
import com.intelram.shield.ui.theme.RedSoft

fun colorForRisk(level: RiskLevel): Color = when (level) {
    RiskLevel.CRITICAL -> Red
    RiskLevel.HIGH -> Orange
    RiskLevel.MEDIUM -> Amber
    RiskLevel.LOW -> Blue
    RiskLevel.CLEAN -> Green
}

fun softColorForRisk(level: RiskLevel): Color = when (level) {
    RiskLevel.CRITICAL -> RedSoft
    RiskLevel.HIGH -> OrangeSoft
    RiskLevel.MEDIUM -> AmberSoft
    RiskLevel.LOW -> BlueSoft
    RiskLevel.CLEAN -> GreenSoft
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val offset by animateDpAsState(if (checked) 20.dp else 2.dp, tween(180), label = "toggle")
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (checked) Green else Border)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = offset, top = 2.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun SeverityBadge(level: RiskLevel, modifier: Modifier = Modifier) {
    val color = colorForRisk(level)
    Text(
        text = level.label.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(softColorForRisk(level), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun IconTile(
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
