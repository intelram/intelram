package com.threadprotection.app.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.Remedy
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.Derived
import com.threadprotection.app.state.Vote
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.SeverityBadgeFor
import com.threadprotection.app.ui.components.SubtlePillButton
import com.threadprotection.app.ui.components.blinkAlpha
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.Severity
import com.threadprotection.app.ui.theme.TpType
import com.threadprotection.app.ui.theme.severityColor
import com.threadprotection.app.ui.theme.severityTint

@Composable
fun ThreatDetailScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
    onFix: () -> Unit,
    /** Called when the user is handed off to Android Settings, before they've confirmed anything.
     *  Drives the "fixing in progress" state — this app can't change a system setting itself, so
     *  in-progress means exactly "they've been sent there and haven't confirmed yet". */
    onBeginFix: (String) -> Unit,
    onUnresolve: (String) -> Unit,
    onIgnore: () -> Unit,
    /** Undo an "Ignore for now". Separate from [onIgnore]: the two are opposite actions, and
     *  wiring both buttons to the same handler is why "count it again" previously did nothing. */
    onUnignore: (String) -> Unit,
    ignored: Boolean,
    resolved: Boolean,
    onOpenRemedy: (Remedy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val sel = Derived.selectedFinding(state)
    if (sel == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val fixed = resolved
    val sev = if (fixed) Severity.FIXED else sel.sev
    val sevColor = palette.severityColor(sev)
    val sevTint = palette.severityTint(sev)
    val vote = state.votes[sel.id]

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Threat detail", style = TpType.screenTitle, color = palette.fg)
            }

            // Header card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(sevTint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(sevColor))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(sel.name, style = TpType.cardTitleBold.copy(fontSize = 19.5.sp), color = palette.fg)
                        Text(sel.type, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                    }
                    SeverityBadgeFor(sev)
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
                Text(sel.desc, style = TpType.body.copy(fontSize = 16.5.sp, lineHeight = 26.sp), color = palette.fg2)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        val alpha = blinkAlpha(2000)
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(palette.accent.copy(alpha = alpha)))
                        Text("Detected by ${sel.source}", style = TpType.caption, color = palette.muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(palette.accent))
                        Text("AI confidence ${Derived.confidence(sel.risk)}% · ${Derived.sourceCount(sel.risk)}", style = TpType.caption, color = palette.muted)
                    }
                }
            }

            // Risk potential
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("RISK POTENTIAL", style = TpType.sectionHeading, color = palette.muted)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${sel.risk}", style = TpType.cardTitleBold.copy(fontSize = 31.5.sp), color = sevColor)
                        Text("/100", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted2)
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(palette.line)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(sel.risk / 100f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(sevColor),
                    )
                }
                Text("${Derived.riskUrgency(sel.risk)} · Category: ${sel.cat.label()}", style = TpType.body.copy(fontSize = 15.5.sp), color = palette.fg2)
            }

            if (sel.breaches != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.threadprotection.app.ui.components.SectionHeading("Where your email was found")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp)),
                    ) {
                        sel.breaches.forEach { b ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(modifier = Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(palette.warn2))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(b.site, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
                                        Text(b.data, style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.muted)
                                    }
                                    Text(b.date, style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.muted2)
                                }
                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.accentTint07)
                        .border(BorderStroke(1.dp, palette.accentBorder22), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("PROS OF FIXING", style = TpType.badge.copy(fontSize = 13.5.sp), color = palette.accent)
                    sel.pros.forEach { p -> BulletLine("+", p, palette.accent) }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.warnTint06)
                        .border(BorderStroke(1.dp, palette.warnBorder20), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("TRADE-OFFS", style = TpType.badge.copy(fontSize = 13.5.sp), color = palette.warn)
                    sel.cons.forEach { c -> BulletLine("−", c, palette.warn) }
                }
            }

            // Feedback
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.card2)
                    .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Did we get this right?", style = TpType.cardTitle.copy(fontSize = 16.5.sp), color = palette.fg)
                if (vote == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VoteButton("Yes, correct", Modifier.weight(1f), onVoteUp)
                            VoteButton("No, mistake", Modifier.weight(1f), onVoteDown)
                        }
                        Text(
                            "Your answer trains the AI and, if you disagree, sends it to a human analyst.",
                            style = TpType.caption,
                            color = palette.muted,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.padding(top = 0.dp).size(24.dp).clip(CircleShape).background(palette.accentTint15),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", color = palette.accent, style = TpType.badge.copy(fontSize = 14.sp))
                        }
                        Text(
                            if (vote == Vote.UP) {
                                "Thank you — this confirmed detection now trains the model."
                            } else {
                                "Thank you — a human analyst will review this within 24 hours."
                            },
                            style = TpType.body.copy(fontSize = 15.sp),
                            color = palette.fg2,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.threadprotection.app.ui.components.SectionHeading("AI recommendation")
                Text(
                    sel.advice,
                    style = TpType.body.copy(fontSize = 16.sp, lineHeight = 25.6.sp),
                    color = palette.fg2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.accentTint08)
                        .border(BorderStroke(1.dp, palette.accentBorder25), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!fixed) {
                val hasRemedy = sel.remedy != Remedy.None
                val fixing = state.fixInProgressId == sel.id
                PrimaryPillButton(
                    text = when {
                        !hasRemedy -> "Got it"
                        fixing -> "I've done it — mark resolved"
                        else -> sel.fix
                    },
                    onClick = {
                        if (!hasRemedy) {
                            // Nothing for Android to open — acknowledging *is* the resolution.
                            onFix()
                            return@PrimaryPillButton
                        }
                        if (fixing) {
                            // Second tap: the user is back from Settings and confirming. Only now
                            // is it recorded as resolved, so the score never moves on a fix that
                            // was never actually applied.
                            onFix()
                        } else {
                            onOpenRemedy(sel.remedy)
                            onBeginFix(sel.id)
                        }
                    },
                )
                if (hasRemedy) {
                    Text(
                        if (fixing) {
                            "Waiting on you: change it in Android Settings, then tap above to mark it resolved. Nothing is recorded until you confirm."
                        } else {
                            "Opens Android Settings — this app can't change it for you, only you can."
                        },
                        style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp),
                        color = palette.muted,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .border(BorderStroke(1.dp, palette.accentBorder40), RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓ Resolved", style = TpType.primaryButtonLg.copy(fontSize = 15.sp), color = palette.accent)
                }
                // Resolutions persist across scans, so there has to be a way back out of one —
                // otherwise a mistaken tap hides a real threat for good.
                SubtlePillButton(text = "Not fixed after all — undo", onClick = { onUnresolve(sel.id); onBack() })
                Text(
                    "Kept resolved across scans and restarts. It comes back on its own if the problem reappears or gets worse.",
                    style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = palette.muted2,
                )
            }
            // Previously this only navigated back, so nothing changed and the finding kept
            // counting against the security score. It now really does mark the finding ignored
            // for this session: it drops out of the active list and the score recalculates.
            //
            // Not offered on an already-resolved finding: "ignore" and "resolved" are two ways of
            // saying the same thing to the score, and showing both invites the user to put one
            // finding into two states at once.
            if (fixed) {
                // nothing — undo above is the only way back out of a resolution
            } else if (ignored) {
                SubtlePillButton(text = "Ignored — count it again", onClick = { onUnignore(sel.id); onBack() })
                Text(
                    "Not counted in your security score for now. The next scan will raise it again — ignoring isn't fixing.",
                    style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = palette.muted2,
                )
            } else {
                SubtlePillButton(text = "Ignore for now", onClick = { onIgnore(); onBack() })
            }
        }
    }
}

@Composable
private fun BulletLine(symbol: String, text: String, color: androidx.compose.ui.graphics.Color) {
    val palette = LocalTpPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(symbol, style = TpType.caption.copy(fontSize = 14.5.sp), color = color)
        Text(text, style = TpType.caption.copy(fontSize = 14.5.sp, lineHeight = 21.75.sp), color = palette.fg2)
    }
}

@Composable
private fun VoteButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.5.dp, palette.line3), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg2)
    }
}

private fun com.threadprotection.app.data.Category.label(): String = when (this) {
    com.threadprotection.app.data.Category.SOFTWARE -> "Software"
    com.threadprotection.app.data.Category.PORTS -> "Ports"
    com.threadprotection.app.data.Category.SERVICES -> "Services"
    com.threadprotection.app.data.Category.LICENSING -> "Licensing"
    com.threadprotection.app.data.Category.ACTIVITY -> "Activity"
    com.threadprotection.app.data.Category.HARDWARE -> "Hardware"
    com.threadprotection.app.data.Category.OS -> "OS"
    com.threadprotection.app.data.Category.EMAIL -> "Email"
    com.threadprotection.app.data.Category.NETWORK -> "Network"
}
