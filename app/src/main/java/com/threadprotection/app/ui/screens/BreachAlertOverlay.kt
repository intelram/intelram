package com.threadprotection.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.StoredBreachAlert
import com.threadprotection.app.network.BreachAdvisor
import com.threadprotection.app.network.BreachAdvisor.toRecord
import com.threadprotection.app.network.BreachKind
import com.threadprotection.app.network.BreachRecord
import com.threadprotection.app.network.BreachSeverity
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.SubtlePillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * The popup for a breach the user hasn't seen yet — from the background monitor or an in-app
 * check. Everything in it comes from the breach record itself; the steps come from
 * [BreachAdvisor.actionsFor], driven by what that breach actually exposed.
 */
@Composable
fun BreachAlertOverlay(
    alert: StoredBreachAlert,
    onSeeReport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val breaches = BreachAdvisor.prioritized(alert.breaches.map { it.toRecord() })
    val shown = breaches.take(3)
    val worst = breaches.firstOrNull()
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    BackHandler(enabled = true, onBack = onDismiss)

    val (badge, title) = when {
        alert.firstLook -> "DATA BREACH ALERT" to
            "Your email appears in ${alert.totalBreaches} known data breach${if (alert.totalBreaches == 1) "" else "es"}"
        breaches.size == 1 -> "NEW DATA BREACH" to "Your email was found in the ${breaches.first().name} breach"
        else -> "NEW DATA BREACHES" to "Your email was found in ${breaches.size} new breaches"
    }

    Box(
        modifier = modifier.fillMaxSize().background(palette.alertScrim).padding(18.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInVertically(animationSpec = tween(220)) { it / 10 } + fadeIn(animationSpec = tween(220)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.bg)
                    .border(BorderStroke(1.5.dp, palette.danger), RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(palette.dangerTint12),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("!", style = TpType.cardTitleBold.copy(fontSize = 28.sp), color = palette.danger)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(badge, style = TpType.badge, color = palette.danger)
                        Text(title, style = TpType.cardTitleBold.copy(fontSize = 19.sp, lineHeight = 24.sp), color = palette.fg)
                    }
                }
                Text(
                    alert.email,
                    style = TpType.caption.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    color = palette.muted,
                )

                shown.forEach { BreachSummaryCard(it, descriptionLines = 4) }
                // A first-look alert carries only the worst few of all the breaches found.
                val more = (if (alert.firstLook) alert.totalBreaches else breaches.size) - shown.size
                if (more > 0) {
                    Text(
                        "+ $more more in the full report",
                        style = TpType.caption.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.muted,
                    )
                }

                if (worst != null) {
                    Text("What to do now", style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
                    BreachActionList(BreachAdvisor.actionsFor(worst).take(4))
                }

                PrimaryPillButton(text = "See full report & protect my email", onClick = onSeeReport)
                SubtlePillButton(text = "Dismiss", onClick = onDismiss)
            }
        }
    }
}

// ── Pieces shared with DataBreachScreen ────────────────────────────────────────────────────

internal data class SeverityStyle(val label: String, val color: Color, val tint: Color)

@Composable
internal fun severityStyle(s: BreachSeverity): SeverityStyle {
    val palette = LocalTpPalette.current
    return when (s) {
        BreachSeverity.CRITICAL -> SeverityStyle("CRITICAL", palette.danger, palette.dangerTint14)
        BreachSeverity.HIGH -> SeverityStyle("HIGH", palette.warn2, palette.warn2Tint14)
        BreachSeverity.MEDIUM -> SeverityStyle("MEDIUM", palette.warn, palette.warnTint14)
        BreachSeverity.LOW -> SeverityStyle("LOW", palette.muted, palette.mutedTint14)
    }
}

@Composable
internal fun SeverityPill(severity: BreachSeverity) {
    val style = severityStyle(severity)
    Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(style.tint).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(style.label, style = TpType.badgeSmall.copy(fontSize = 10.5.sp), color = style.color)
    }
}

/** "2026 · 8.4M records · Verified · Transport" — only the parts the source actually provided. */
internal fun breachMetaLine(b: BreachRecord): String = listOfNotNull(
    when (BreachAdvisor.kindOf(b)) {
        BreachKind.STEALER_LOGS -> "Malware stealer log"
        BreachKind.COMBO_LIST -> "Compiled list"
        BreachKind.SITE -> null
    },
    b.date.takeIf { it.isNotBlank() },
    b.records?.takeIf { it > 0 }?.let { "${BreachAdvisor.compactCount(it)} records" },
    if (b.verified) "Verified" else "Unverified",
    b.industry,
).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DataClassChips(dataClasses: List<String>) {
    val palette = LocalTpPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        dataClasses.forEach { dc ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.card2)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(dc, style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 15.sp), color = palette.fg2)
            }
        }
    }
}

@Composable
internal fun BreachActionList(actions: List<String>) {
    val palette = LocalTpPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        actions.forEachIndexed { i, action ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.accentTint14),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = TpType.badgeSmall.copy(fontSize = 11.sp), color = palette.accent)
                }
                Text(action, style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.5.sp), color = palette.fg2, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Name, severity, facts, description, what leaked and how passwords were stored. */
@Composable
internal fun BreachSummaryCard(b: BreachRecord, descriptionLines: Int = Int.MAX_VALUE) {
    val palette = LocalTpPalette.current
    val severity = BreachAdvisor.severityOf(b)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(b.name, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg, modifier = Modifier.weight(1f))
            SeverityPill(severity)
        }
        Text(breachMetaLine(b), style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted2)
        b.description?.let {
            Text(
                it,
                style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp),
                color = palette.fg2,
                maxLines = descriptionLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (b.dataClasses.isNotEmpty()) DataClassChips(b.dataClasses)
        BreachAdvisor.passwordStorageNote(b)?.let {
            Text(it, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.5.sp, fontWeight = FontWeight.SemiBold), color = severityStyle(severity).color)
        }
    }
}
