package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.network.UrlVerdict
import com.threadprotection.app.network.Verdict
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.TechnicalDetailsCard
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** Manual URL check — the same live pipeline (ThreatIntelRepository) the QR scanner uses, for any link you paste in. */
@Composable
fun ScanWebsiteScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Scan a website", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "Paste any link before you open it — on-device analysis, live domain/IP/certificate checks, PhishTank, and every extra feed you've configured in Settings, all at once.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            TextField(
                value = state.websiteUrl,
                onValueChange = onUrlChange,
                singleLine = true,
                placeholder = { Text("https://example.com", color = palette.muted3) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.card2,
                    unfocusedContainerColor = palette.card2,
                    focusedTextColor = palette.fg,
                    unfocusedTextColor = palette.fg,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = palette.accent,
                ),
            )
            PrimaryPillButton(
                text = "Check this link",
                onClick = onCheck,
                enabled = state.websiteUrl.isNotBlank() && !state.websiteChecking,
            )

            if (state.websiteChecking) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            }

            state.websiteVerdict?.let { verdict -> WebsiteVerdictCard(verdict) }
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

private data class WebsiteVerdictColors(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
    val tint: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
)

@Composable
private fun WebsiteVerdictCard(verdict: UrlVerdict) {
    val palette = LocalTpPalette.current
    val vc = when (verdict.overall) {
        Verdict.SAFE -> WebsiteVerdictColors("SAFE", palette.accent, palette.accentTint12, palette.accentBorder35)
        Verdict.SUSPICIOUS -> WebsiteVerdictColors("SUSPICIOUS", palette.warn, palette.warnTint12, palette.warnBorder35)
        Verdict.MALICIOUS -> WebsiteVerdictColors("MALICIOUS", palette.danger, palette.dangerTint12, palette.dangerBorder35)
        Verdict.UNKNOWN -> WebsiteVerdictColors("UNKNOWN", palette.muted, palette.mutedTint14, palette.line3)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(vc.tint)
            .border(BorderStroke(1.dp, vc.border), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(vc.color))
            Text(vc.label, style = TpType.badge, color = vc.color)
        }
        Text(verdict.url, style = TpType.caption.copy(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = palette.fg2, maxLines = 3)
        if (verdict.onDeviceFlags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                verdict.onDeviceFlags.forEach { flag ->
                    Text("• $flag", style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp), color = palette.fg2)
                }
            }
        }
        verdict.signals.forEach { signal ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(signal.source, style = TpType.caption.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = palette.fg)
                Text(signal.detail, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Text("${verdict.confidence}% confidence · matched by ${verdict.matchedBy}", style = TpType.caption.copy(fontSize = 12.sp), color = palette.muted2)
    }
    verdict.technical?.let { tech -> TechnicalDetailsCard(tech) }
}
