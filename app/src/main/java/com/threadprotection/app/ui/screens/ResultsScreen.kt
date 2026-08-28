package com.threadprotection.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.Finding
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.Derived
import com.threadprotection.app.state.FixProgress
import com.threadprotection.app.state.ScanStatus
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.ConicProgressRing
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.SeverityBadgeFor
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.Severity
import com.threadprotection.app.ui.theme.TpType
import com.threadprotection.app.ui.theme.severityColor

/**
 * Scan results.
 *
 * The header — "Scan complete", the live security score and the threat count — is pinned outside
 * the scrolling region, so the two numbers that matter stay on screen while the user works down a
 * long list. Everything below it is derived from state on every recomposition rather than
 * remembered locally, which is what keeps the score, the list and the Start Fixing button
 * describing the same reality at the same instant: resolving a threat updates all three at once,
 * with no refresh and no chance of one lagging behind another.
 */
@Composable
fun ResultsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onStartFixing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val threats = Derived.threats(state)
    val groups = Derived.threatsBySeverity(state)
    val resolved = Derived.resolvedThreats(state)
    val ignored = Derived.ignoredThreats(state)
    val progress = Derived.fixProgress(state)

    Column(modifier = modifier.fillMaxSize()) {
        StickyResultsHeader(state = state, progress = progress, onBack = onBack)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val scan = state.scanData
            val inventory = listOf(
                "Apps & packages" to "${scan.appsScanned}",
                "Apps with permission risks" to "${scan.permApps.size}",
                "Ports probed" to if (scan.portsProbed) "${scan.portsFound} listening" else "Restricted",
                "Hardware devices" to "${scan.hwDevices.size}",
                "OS patch level" to scan.osPatchLabel.ifBlank { "—" },
                "Threat-intel feeds live" to "${scan.feedsConfigured}/${scan.feedsTotal}",
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                inventory.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (label, value) -> InventoryTile(label, value, Modifier.weight(1f)) }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }

            // Active threats, grouped by how bad they are. Empty bands are dropped entirely rather
            // than shown as "0 critical" — an empty heading reads as a finding of its own.
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SeverityGroupHeading(group.label, group.findings.size, palette.severityColor(group.severity))
                    group.findings.forEach { finding ->
                        ThreatRow(
                            finding = finding,
                            fixed = false,
                            // A threat the user resolved before and that has come back is a new
                            // active threat, not a re-listing — say so on the row rather than
                            // letting it look like something they never dealt with.
                            returned = finding.id in state.reEmergedIds,
                            onClick = { onOpen(finding.id) },
                        )
                    }
                }
            }

            if (ignored.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SeverityGroupHeading("Ignored for now", ignored.size, palette.muted)
                    Text(
                        "Not counted in your score for this session. The next scan raises them again — ignoring isn't fixing.",
                        style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.5.sp),
                        color = palette.muted2,
                    )
                    ignored.forEach { finding ->
                        ThreatRow(finding = finding, fixed = false, muted = true, onClick = { onOpen(finding.id) })
                    }
                }
            }

            // Resolved threats are collapsed out of the way, not listed. Once something is dealt
            // with the user shouldn't have to scroll past it on every rescan — but it can't vanish
            // entirely either, because a resolution the user can't reverse would hide a real threat
            // for good. So: a one-line summary they can open when they want it.
            if (resolved.isNotEmpty()) {
                var showResolved by rememberSaveable { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.accentTint08)
                            .border(BorderStroke(1.dp, palette.accentBorder30), RoundedCornerShape(14.dp))
                            .clickable { showResolved = !showResolved }
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("✓", style = TpType.cardTitle.copy(fontSize = 15.sp), color = palette.accent)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${resolved.size} resolved and hidden",
                                style = TpType.cardTitle.copy(fontSize = 15.sp),
                                color = palette.accent,
                            )
                            Text(
                                "Kept out of your way across scans. They come back on their own if the problem returns.",
                                style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp),
                                color = palette.muted,
                            )
                        }
                        Text(
                            if (showResolved) "Hide" else "Show",
                            style = TpType.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = palette.accent,
                        )
                    }
                    if (showResolved) {
                        resolved.forEach { finding ->
                            ThreatRow(finding = finding, fixed = true, onClick = { onOpen(finding.id) })
                        }
                    }
                }
            }

            if (threats.isEmpty()) {
                Text(
                    "No threats found. Your device is fully protected.",
                    style = TpType.body.copy(fontSize = 17.sp),
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            StartFixingButton(progress = progress, onStartFixing = onStartFixing, onBack = onBack)
        }
    }
}

/**
 * The pinned top block: title, live score, and the count of what still needs attention. Sits
 * outside the scroll container, so all three stay put while the list moves under them.
 */
@Composable
private fun StickyResultsHeader(state: AppUiState, progress: FixProgress, onBack: () -> Unit) {
    val palette = LocalTpPalette.current
    val score = Derived.securityScore(state)
    val status = Derived.scanStatus(state)
    val statusColor = when (status) {
        ScanStatus.NEEDED -> palette.warn
        ScanStatus.AT_RISK -> palette.danger
        ScanStatus.PROTECTED -> palette.accent
    }
    val remaining = progress.remaining
    val headline = when {
        !progress.hasThreats -> "No threats found"
        remaining == 0 -> "All threats resolved"
        remaining == 1 -> "threat needs your attention"
        else -> "threats need your attention"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackCircleButton(onClick = onBack)
            Text("Scan complete", style = TpType.screenTitle, color = palette.fg, modifier = Modifier.weight(1f))
            // Same ring, same number, same colour rule as the home screen's score — just sized to
            // sit inside a header rather than fill a card.
            CompactSecurityScore(score = score, color = statusColor)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (remaining > 0) palette.dangerTint08 else palette.accentTint08)
                .border(
                    BorderStroke(1.dp, if (remaining > 0) palette.dangerBorder30 else palette.accentBorder30),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (progress.hasThreats && remaining > 0) {
                Text(
                    "$remaining",
                    style = TpType.cardTitleBold.copy(fontSize = 31.5.sp),
                    color = palette.danger,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    headline,
                    style = TpType.cardTitle,
                    color = if (remaining > 0) palette.fg else palette.accent,
                )
                Text(
                    headerCaption(state, progress),
                    style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 18.5.sp),
                    color = palette.muted,
                )
            }
        }
    }
}

private fun headerCaption(state: AppUiState, progress: FixProgress): String {
    val scanned = "%,d".format(state.scannedCount)
    // A threat that came back after being resolved is the single most important thing this header
    // can say, so it outranks the ordinary progress line.
    val returned = state.reEmergedIds.count { id -> Derived.activeThreats(state).any { it.id == id } }
    if (returned > 0) {
        return "$returned previously resolved threat${if (returned > 1) "s have" else " has"} come back · $scanned items checked"
    }
    return when {
        !progress.hasThreats -> "$scanned items checked across software, services, ports, licences and OS"
        progress.remaining == 0 && progress.ignored > 0 ->
            "${progress.resolved} resolved, ${progress.ignored} ignored for now · rescan to confirm"
        progress.remaining == 0 -> "All ${progress.total} resolved · rescan to confirm"
        progress.resolved > 0 || progress.ignored > 0 ->
            "${progress.resolved} of ${progress.total} resolved · $scanned items checked"
        else -> "$scanned items checked across software, services, ports, licences and OS"
    }
}

/** The home screen's score ring at header size, so the two read as the same element. */
@Composable
private fun CompactSecurityScore(score: Int, color: androidx.compose.ui.graphics.Color) {
    val palette = LocalTpPalette.current
    // Animated so a resolved threat visibly moves the score rather than snapping, making the
    // live update legible instead of easy to miss.
    val fraction by animateFloatAsState(targetValue = score / 100f, animationSpec = tween(450), label = "score")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ConicProgressRing(
            size = 58.dp,
            inset = 6.dp,
            progressFraction = fraction,
            activeColor = color,
            trackColor = palette.line,
            innerBackground = palette.bg,
            animate = false,
        ) {
            Text("$score", style = TpType.cardTitleBold.copy(fontSize = 19.sp), color = color)
        }
        Text(
            "SECURITY SCORE",
            style = TpType.caption.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold),
            color = palette.muted,
        )
    }
}

@Composable
private fun SeverityGroupHeading(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    val palette = LocalTpPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(
            label.uppercase(),
            style = TpType.caption.copy(fontSize = 12.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.Bold),
            color = palette.fg2,
        )
        Text(
            "$count",
            style = TpType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = color,
        )
    }
}

/**
 * The action at the bottom, driven entirely by [FixProgress] — there is no separate "button state"
 * to fall out of sync with the list above it.
 */
@Composable
private fun StartFixingButton(progress: FixProgress, onStartFixing: () -> Unit, onBack: () -> Unit) {
    val palette = LocalTpPalette.current
    when {
        !progress.hasThreats ->
            OutlinedPillButton(text = "Back to dashboard", onClick = onBack, borderColor = palette.line3)

        progress.allResolved -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.accentTint08)
                    .border(BorderStroke(1.dp, palette.accentBorder40), RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (progress.ignored > 0) "✓ Nothing left to act on" else "✓ All threats resolved",
                    style = TpType.primaryButtonLg.copy(fontSize = 15.5.sp),
                    color = palette.accent,
                )
            }
            OutlinedPillButton(text = "Back to dashboard", onClick = onBack, borderColor = palette.line3)
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val started = progress.resolved > 0 || progress.ignored > 0 || progress.fixing
            if (started) {
                FixProgressBar(progress)
            }
            PrimaryPillButton(
                text = when {
                    progress.fixing -> "Finish fixing (${progress.remaining} left)"
                    started -> "Continue fixing (${progress.remaining} left)"
                    progress.remaining == 1 -> "Start fixing (1 threat)"
                    else -> "Start fixing (${progress.remaining} threats)"
                },
                onClick = onStartFixing,
            )
        }
    }
}

@Composable
private fun FixProgressBar(progress: FixProgress) {
    val palette = LocalTpPalette.current
    val fraction by animateFloatAsState(targetValue = progress.fraction, animationSpec = tween(400), label = "fixProgress")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(palette.line),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.accent),
            )
        }
        Text(
            if (progress.fixing) {
                "Applying a fix in Android Settings — come back and confirm it to mark it resolved."
            } else {
                "${progress.resolved} resolved of ${progress.total}" +
                    if (progress.ignored > 0) " · ${progress.ignored} ignored" else ""
            },
            style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = palette.muted,
        )
    }
}

@Composable
private fun InventoryTile(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = TpType.cardTitleBold.copy(fontSize = 20.5.sp), color = palette.fg)
        Text(label, style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.muted)
    }
}

@Composable
private fun ThreatRow(
    finding: Finding,
    fixed: Boolean,
    muted: Boolean = false,
    returned: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalTpPalette.current
    val sev = if (fixed) Severity.FIXED else finding.sev
    val sevColor = palette.severityColor(sev)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (fixed || muted) 0.55f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(sevColor))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    finding.name,
                    style = TpType.cardTitle.copy(fontSize = 17.sp, textDecoration = if (fixed) TextDecoration.LineThrough else TextDecoration.None),
                    color = palette.fg,
                )
                Text(finding.type, style = TpType.caption, color = palette.muted)
                if (returned) {
                    Text(
                        "⟳ Came back — you resolved this before",
                        style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.warn,
                    )
                }
            }
            SeverityBadgeFor(sev)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (fixed) 1f else finding.risk / 100f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(sevColor),
                )
            }
            Text(
                when {
                    fixed -> "Resolved"
                    muted -> "Ignored · risk ${finding.risk}/100"
                    else -> "Risk potential ${finding.risk}/100"
                },
                style = TpType.caption.copy(fontSize = 13.5.sp),
                color = palette.muted,
            )
        }
    }
}
