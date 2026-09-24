package com.threadprotection.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.threadprotection.app.network.BreachAdvisor
import com.threadprotection.app.network.BreachCheckResult
import com.threadprotection.app.network.BreachRecord
import com.threadprotection.app.network.BreachSeverity
import com.threadprotection.app.network.PasswordLeakResult
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.ToggleSwitch
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** Breaches listed before "Show all" — a heavily exposed address can be in 200+. */
private const val INITIAL_BREACHES_SHOWN = 15

/**
 * Real, free, keyless breach lookup via XposedOrNot (README §Data breach security), background
 * monitoring for new breaches (`BreachMonitorWorker`), per-breach next steps driven by what each
 * breach exposed (`BreachAdvisor`), and a k-anonymous password check against Have I Been Pwned's
 * Pwned Passwords. Have I Been Pwned's *email* API stopped being free in 2024, which is why the
 * email lookup uses XposedOrNot instead of faking one.
 */
@Composable
fun DataBreachScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onCheck: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onCheckPassword: (String) -> Unit,
    onClearPasswordResult: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val result = state.breachResult

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Data breach security", style = TpType.screenTitle, color = palette.fg)
            }

            if (state.account == null) {
                Text(
                    "Sign in first — this checks the email address you signed in with against known public breaches.",
                    style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                    color = palette.muted,
                )
                PasswordLeakCard(state.passwordLeakResult, state.passwordLeakChecking, onCheckPassword, onClearPasswordResult)
                return@Column
            }

            MonitoringCard(
                email = state.account.email,
                enabled = state.settings.breach,
                lastCheckedAtMs = state.breachLastCheckedAtMs,
                onToggle = onToggleMonitoring,
            )

            if (state.breachChecking) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            } else if (result != null && result.checked) {
                ResultSummary(result)
                if (result.breaches.isNotEmpty()) BreachList(result.breaches, onOpenLink)
            } else if (result?.error != null) {
                Text(result.error, style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.warn)
            }

            PrimaryPillButton(text = if (result == null) "Check now" else "Check again", onClick = onCheck, enabled = !state.breachChecking)

            PasswordLeakCard(state.passwordLeakResult, state.passwordLeakChecking, onCheckPassword, onClearPasswordResult)
            ProtectEmailCard()

            Text(
                "Sources: XposedOrNot's free public breach database for your email, and Have I Been Pwned's Pwned Passwords for the password check.",
                style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = palette.muted2,
            )
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

@Composable
private fun MonitoringCard(email: String, enabled: Boolean, lastCheckedAtMs: Long?, onToggle: () -> Unit) {
    val palette = LocalTpPalette.current
    val context = LocalContext.current
    val notificationsBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) palette.accentTint08 else palette.card)
            .border(BorderStroke(1.dp, if (enabled) palette.accentBorder25 else palette.line), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (enabled) "Breach monitoring is on" else "Breach monitoring is off",
                    style = TpType.cardTitle.copy(fontSize = 16.5.sp),
                    color = if (enabled) palette.accent else palette.fg,
                )
                Text(email, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted)
            }
            ToggleSwitch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            if (enabled) {
                "Checked in the background every 12 hours. The moment this address shows up in a new breach, you'll get an alert with what leaked and what to do."
            } else {
                "Turn this on to be alerted automatically when this address shows up in a new breach."
            },
            style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp),
            color = palette.fg2,
        )
        if (enabled) {
            Text(
                lastCheckedAtMs?.let {
                    "Last checked " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                } ?: "First background check pending — it runs as soon as the phone is online.",
                style = TpType.caption.copy(fontSize = 12.5.sp),
                color = palette.muted2,
            )
            if (notificationsBlocked) {
                Text(
                    "Notifications are turned off for this app, so a new breach will appear as a popup the next time you open it instead.",
                    style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp),
                    color = palette.warn,
                )
            }
        }
    }
}

@Composable
private fun ResultSummary(result: BreachCheckResult) {
    val palette = LocalTpPalette.current
    val safe = result.breaches.isEmpty()
    val bySeverity = result.breaches.groupBy { BreachAdvisor.severityOf(it) }
    val passwordLeaks = result.breaches.count { BreachAdvisor.passwordStorageNote(it) != null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (safe) palette.accentTint08 else palette.dangerTint08)
            .border(BorderStroke(1.dp, if (safe) palette.accentBorder25 else palette.dangerBorder30), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (safe) "Not found in any known breach" else "Found in ${result.breaches.size} known breach${if (result.breaches.size == 1) "" else "es"}",
            style = TpType.cardTitleBold.copy(fontSize = 18.sp),
            color = if (safe) palette.accent else palette.danger,
        )
        if (!safe && result.riskLabel != null) {
            Text(
                "Exposure rating: ${result.riskLabel}" + (result.riskScore?.let { " ($it/100)" } ?: ""),
                style = TpType.caption.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = palette.fg,
            )
        }
        Text(
            if (safe) {
                "Nothing to do right now. Keep monitoring on and you'll be told if that changes."
            } else {
                "Start with the breaches marked Critical below — each one lists exactly what leaked and what to do about it."
            },
            style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
            color = palette.fg2,
        )
    }

    if (!safe) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BreachStat("${result.breaches.size}", "Breaches", palette.danger, Modifier.weight(1f))
            BreachStat("${bySeverity[BreachSeverity.CRITICAL]?.size ?: 0}", "Critical", palette.danger, Modifier.weight(1f))
            BreachStat("$passwordLeaks", "Leaked\npasswords", palette.warn2, Modifier.weight(1f))
            BreachStat("${result.pasteCount}", "Paste\ndumps", if (result.pasteCount > 0) palette.warn else palette.muted, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BreachStat(value: String, label: String, color: Color, modifier: Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = TpType.cardTitleBold.copy(fontSize = 19.sp), color = color, textAlign = TextAlign.Center)
        Text(label, style = TpType.caption.copy(fontSize = 11.5.sp, lineHeight = 14.5.sp), color = palette.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BreachList(breaches: List<BreachRecord>, onOpenLink: (String) -> Unit) {
    val palette = LocalTpPalette.current
    val ordered = remember(breaches) { BreachAdvisor.prioritized(breaches) }
    var showAll by remember { mutableStateOf(false) }
    val visible = if (showAll) ordered else ordered.take(INITIAL_BREACHES_SHOWN)

    Text("Your breaches — most serious first", style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
    Text("Tap any breach for what leaked and what to do.", style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp)),
    ) {
        visible.forEachIndexed { i, b ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
            BreachRow(b, onOpenLink)
        }
    }
    if (ordered.size > INITIAL_BREACHES_SHOWN) {
        OutlinedPillButton(
            text = if (showAll) "Show fewer" else "Show all ${ordered.size} breaches",
            onClick = { showAll = !showAll },
            borderColor = palette.line3,
        )
    }
}

@Composable
private fun BreachRow(b: BreachRecord, onOpenLink: (String) -> Unit) {
    val palette = LocalTpPalette.current
    val severity = BreachAdvisor.severityOf(b)
    var expanded by remember(b.name) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(severityStyle(severity).color))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(b.name, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
                Text(breachMetaLine(b), style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted2)
            }
            SeverityPill(severity)
        }
        if (!expanded) {
            if (b.dataClasses.isNotEmpty()) {
                Text(
                    b.dataExposed,
                    style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.5.sp),
                    color = palette.muted,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 21.dp),
                )
            }
        } else {
            Column(modifier = Modifier.padding(start = 21.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                b.description?.let {
                    Text(it, style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp), color = palette.fg2)
                }
                if (b.dataClasses.isNotEmpty()) {
                    Text("What leaked", style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = palette.muted)
                    DataClassChips(b.dataClasses)
                }
                BreachAdvisor.passwordStorageNote(b)?.let {
                    Text(it, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.5.sp, fontWeight = FontWeight.SemiBold), color = severityStyle(severity).color)
                }
                Text("What to do", style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = palette.muted)
                BreachActionList(BreachAdvisor.actionsFor(b))
                val facts = listOfNotNull(
                    b.domain?.let { "Site: $it" },
                    b.addedAt?.take(10)?.let { "Added to the breach database: $it" },
                )
                facts.forEach { Text(it, style = TpType.caption.copy(fontSize = 12.sp), color = palette.muted2) }
                b.referenceUrl?.let { url ->
                    Text(
                        "Read the original report ›",
                        style = TpType.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.accent,
                        modifier = Modifier.clickable { onOpenLink(url) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordLeakCard(
    result: PasswordLeakResult?,
    checking: Boolean,
    onCheck: (String) -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalTpPalette.current
    // Plain `remember`, deliberately not `rememberSaveable`: the password must never be written
    // into saved instance state, and it's cleared the moment it has been checked.
    var password by remember { mutableStateOf("") }
    val submit = {
        if (password.isNotEmpty()) {
            onCheck(password)
            password = ""
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Has a password leaked?", style = TpType.cardTitle.copy(fontSize = 16.5.sp), color = palette.fg)
        Text(
            "Checks Have I Been Pwned's list of passwords from real breaches. Your password never leaves this phone — only the first 5 characters of a one-way fingerprint of it are sent, and the match happens here.",
            style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp),
            color = palette.fg2,
        )
        TextField(
            value = password,
            onValueChange = {
                password = it
                if (result != null) onClear()
            },
            singleLine = true,
            placeholder = { Text("Type a password to check", color = palette.muted3) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = palette.card2,
                unfocusedContainerColor = palette.card2,
                focusedTextColor = palette.fg,
                unfocusedTextColor = palette.fg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = palette.accent,
            ),
        )
        OutlinedPillButton(
            text = if (checking) "Checking…" else "Check password",
            onClick = { if (!checking) submit() },
            borderColor = palette.line3,
        )
        when {
            result == null -> Unit
            !result.checked -> Text(result.error ?: "Couldn't check right now.", style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.warn)
            result.timesSeen > 0 -> Text(
                "This password has appeared ${"%,d".format(result.timesSeen)} time${if (result.timesSeen == 1L) "" else "s"} in data breaches. Stop using it everywhere — attackers try leaked passwords first.",
                style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                color = palette.danger,
            )
            else -> Text(
                "Not found in any known breach. Still, use it for one account only.",
                style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                color = palette.accent,
            )
        }
    }
}

@Composable
private fun ProtectEmailCard() {
    val palette = LocalTpPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Protect your email address", style = TpType.cardTitle.copy(fontSize = 16.5.sp), color = palette.fg)
        BreachAdvisor.emailProtectionChecklist().forEach { tip ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(palette.accent))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tip.title, style = TpType.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = palette.fg)
                    Text(tip.detail, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.5.sp), color = palette.fg2)
                }
            }
        }
    }
}
