package com.threadprotection.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.Severity
import com.threadprotection.app.ui.theme.TpType
import com.threadprotection.app.ui.theme.severityColor
import com.threadprotection.app.ui.theme.severityTint

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Text(text = text.uppercase(), style = TpType.sectionHeading, color = palette.muted, modifier = modifier)
}

@Composable
fun SeverityBadge(label: String, color: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = label, style = TpType.badge, color = color)
    }
}

@Composable
fun SeverityBadgeFor(sev: Severity, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    val label = when (sev) {
        Severity.CRITICAL -> "CRITICAL"
        Severity.HIGH -> "HIGH"
        Severity.MEDIUM -> "MEDIUM"
        Severity.LOW -> "LOW"
        Severity.FIXED -> "FIXED"
    }
    SeverityBadge(label, palette.severityColor(sev), palette.severityTint(sev), modifier)
}

@Composable
fun BackCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.5.dp, palette.line4), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.fg2)
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = TpType.cardTitleBold.copy(fontSize = 18.sp), color = palette.accent, textAlign = TextAlign.Center)
        Text(label, style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = palette.muted, textAlign = TextAlign.Center)
    }
}

enum class NavTab { HOME, QR, BRAIN, SETTINGS }

@Composable
fun BottomNavBar(
    active: NavTab,
    onHome: () -> Unit,
    onQr: () -> Unit,
    onBrain: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Row(modifier = Modifier.fillMaxWidth().background(palette.bg2)) {
            NavTabItem("Home", active == NavTab.HOME, onHome, Modifier.weight(1f))
            NavTabItem("QR", active == NavTab.QR, onQr, Modifier.weight(1f))
            NavTabItem("AI brain", active == NavTab.BRAIN, onBrain, Modifier.weight(1f))
            NavTabItem("Settings", active == NavTab.SETTINGS, onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavTabItem(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(top = 15.dp, bottom = 17.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) palette.accent else Color.Transparent),
        )
        Text(
            text = label,
            style = TpType.caption.copy(fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = if (active) palette.accent else palette.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
