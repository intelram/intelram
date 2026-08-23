package com.intelram.shield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intelram.shield.scan.RiskLevel
import com.intelram.shield.ui.theme.RiskClean
import com.intelram.shield.ui.theme.RiskCritical
import com.intelram.shield.ui.theme.RiskHigh
import com.intelram.shield.ui.theme.RiskLow
import com.intelram.shield.ui.theme.RiskMedium

fun colorForRisk(level: RiskLevel) = when (level) {
    RiskLevel.CRITICAL -> RiskCritical
    RiskLevel.HIGH -> RiskHigh
    RiskLevel.MEDIUM -> RiskMedium
    RiskLevel.LOW -> RiskLow
    RiskLevel.CLEAN -> RiskClean
}

@Composable
fun RiskBadge(level: RiskLevel, modifier: Modifier = Modifier) {
    val color = colorForRisk(level)
    Text(
        text = level.label.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
