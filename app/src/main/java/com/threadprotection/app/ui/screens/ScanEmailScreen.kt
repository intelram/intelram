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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.network.EmailVerdict
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

/**
 * Manual email check — paste (or Android-share, see MainActivity's ACTION_SEND handling) the
 * sender and body of an email you're unsure about. Runs the same live pipeline as Scan a website
 * for every link found in the body, plus sender-domain checks (age, DMARC, Cloudflare's threat
 * resolver) and on-device brand-impersonation rules. This app has no Gmail integration and never
 * reads an inbox — see EmailInspector's doc for why that's a deliberate line, not a gap.
 */
@Composable
fun ScanEmailScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onSenderChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
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
                Text("Scan an email", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "Paste the sender's address and the message before you click anything or reply — real domain age, DMARC and threat-blocklist checks on the sender, plus a full live check of every link inside.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            TextField(
                value = state.emailSender,
                onValueChange = onSenderChange,
                singleLine = true,
                label = { Text("Sender's address (optional but recommended)", color = palette.muted3, fontSize = 12.sp) },
                placeholder = { Text("alerts@example.com", color = palette.muted3) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                colors = emailFieldColors(),
            )
            TextField(
                value = state.emailBody,
                onValueChange = onBodyChange,
                singleLine = false,
                label = { Text("Message text", color = palette.muted3, fontSize = 12.sp) },
                placeholder = { Text("Paste the email's subject and body here…", color = palette.muted3) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(14.dp)),
                colors = emailFieldColors(),
            )
            PrimaryPillButton(
                text = "Check this email",
                onClick = onCheck,
                enabled = state.emailBody.isNotBlank() && !state.emailChecking,
            )

            if (state.emailChecking) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            }

            state.emailVerdict?.let { verdict -> EmailVerdictCard(verdict) }
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

@Composable
private fun emailFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LocalTpPalette.current.card2,
    unfocusedContainerColor = LocalTpPalette.current.card2,
    focusedTextColor = LocalTpPalette.current.fg,
    unfocusedTextColor = LocalTpPalette.current.fg,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    cursorColor = LocalTpPalette.current.accent,
)

private data class EmailVerdictColors(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
    val tint: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
)

@Composable
private fun EmailVerdictCard(verdict: EmailVerdict) {
    val palette = LocalTpPalette.current
    val vc = when (verdict.overall) {
        Verdict.SAFE -> EmailVerdictColors("SAFE", palette.accent, palette.accentTint12, palette.accentBorder35)
        Verdict.SUSPICIOUS -> EmailVerdictColors("SUSPICIOUS", palette.warn, palette.warnTint12, palette.warnBorder35)
        Verdict.MALICIOUS -> EmailVerdictColors("MALICIOUS", palette.danger, palette.dangerTint12, palette.dangerBorder35)
        Verdict.UNKNOWN -> EmailVerdictColors("UNKNOWN", palette.muted, palette.mutedTint14, palette.line3)
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
        Text(
            verdict.senderDomain ?: "No sender address given",
            style = TpType.caption.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            color = palette.fg2,
        )
        if (verdict.onDeviceFlags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                verdict.onDeviceFlags.forEach { flag ->
                    Text("• $flag", style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp), color = palette.fg2)
                }
            }
        }
        verdict.signals.forEach { signal ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(signal.source, style = TpType.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = palette.fg)
                Text(signal.detail, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Text("${verdict.confidence}% confidence", style = TpType.caption.copy(fontSize = 12.sp), color = palette.muted2)
    }

    if (verdict.links.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Text(
                "Links in this message (${verdict.links.size})",
                style = TpType.cardTitle.copy(fontSize = 14.sp),
                color = palette.fg,
            )
            verdict.links.forEach { link -> EmailLinkRow(link) }
        }
    }
}

@Composable
private fun EmailLinkRow(link: UrlVerdict) {
    val palette = LocalTpPalette.current
    val (color, tint, label) = when (link.overall) {
        Verdict.SAFE -> Triple(palette.accent, palette.accentTint12, "SAFE")
        Verdict.SUSPICIOUS -> Triple(palette.warn, palette.warnTint12, "SUSPICIOUS")
        Verdict.MALICIOUS -> Triple(palette.danger, palette.dangerTint12, "MALICIOUS")
        Verdict.UNKNOWN -> Triple(palette.muted, palette.mutedTint14, "UNKNOWN")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(tint).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(label, style = TpType.badge.copy(fontSize = 10.sp), color = color)
            }
        }
        Text(
            link.url,
            style = TpType.caption.copy(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace),
            color = palette.fg2,
            maxLines = 2,
        )
        Text("Matched by ${link.matchedBy}", style = TpType.caption.copy(fontSize = 11.5.sp), color = palette.muted2)
        link.technical?.let { tech -> TechnicalDetailsCard(tech) }
    }
}
