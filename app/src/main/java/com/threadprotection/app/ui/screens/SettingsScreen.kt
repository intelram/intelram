package com.threadprotection.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.threadprotection.app.R
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.ProtectionSettings
import com.threadprotection.app.state.ScanFrequency
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.components.ToggleSwitch
import com.threadprotection.app.ui.components.WhiteGoogleButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpThemeMode
import com.threadprotection.app.ui.theme.TpType
import java.util.Calendar

private data class SettingRow(val key: String, val name: String, val caption: String)

private val settingDefs = listOf(
    SettingRow("realtime", "Real-time protection", "Behavior-based monitoring of apps and network"),
    SettingRow("autoScan", "Scheduled scans", ""),
    SettingRow("breach", "Data breach alerts", "Notify when your accounts appear in breaches"),
    SettingRow("phishing", "AI scam & link protection", "Detect AI-crafted phishing in SMS, chat and browsers"),
    SettingRow("downloads", "Sideload & download guard", "Check every APK and file before it runs"),
    SettingRow("hardware", "Hardware watch", "Alert me when anything is plugged in or paired"),
)

private fun ProtectionSettings.isOn(key: String, realtime: Boolean): Boolean = when (key) {
    "realtime" -> realtime
    "autoScan" -> autoScan
    "breach" -> breach
    "phishing" -> phishing
    "downloads" -> downloads
    "hardware" -> hardware
    else -> false
}

private fun ProtectionSettings.scheduleCaption(): String {
    val time = "%02d:%02d".format(scanHour, scanMinute)
    return when (scanFrequency) {
        ScanFrequency.DAILY -> "Full scan every day at $time"
        ScanFrequency.WEEKLY -> "Full scan every ${dayOfWeekName(scanDayOfWeek)} at $time"
    }
}

private fun dayOfWeekName(day: Int): String = when (day) {
    Calendar.SUNDAY -> "Sunday"
    Calendar.MONDAY -> "Monday"
    Calendar.TUESDAY -> "Tuesday"
    Calendar.WEDNESDAY -> "Wednesday"
    Calendar.THURSDAY -> "Thursday"
    Calendar.FRIDAY -> "Friday"
    Calendar.SATURDAY -> "Saturday"
    else -> "Monday"
}

@Composable
fun SettingsScreen(
    state: AppUiState,
    onGoHome: () -> Unit,
    onGoQr: () -> Unit,
    onGoBrain: () -> Unit,
    onSignOut: () -> Unit,
    onSignInGoogle: () -> Unit,
    onPickTheme: (TpThemeMode) -> Unit,
    onToggleSetting: (String) -> Unit,
    onSetApiKey: (ApiKeyId, String) -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onSetScheduledScanTime: (hour: Int, minute: Int) -> Unit,
    onSetScheduledScanFrequency: (frequency: ScanFrequency, dayOfWeek: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val account = state.account
    var showScanDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = TpType.screenTitleLg, color = palette.fg)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(palette.accentTint16)
                        .border(BorderStroke(1.dp, palette.accentBorder35), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(account?.initial ?: "?", style = TpType.cardTitleBold.copy(fontSize = 22.sp), color = palette.accent)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account?.name ?: "Not signed in", style = TpType.cardTitle.copy(fontSize = 17.5.sp), color = palette.fg)
                    Text(
                        account?.email ?: "Sign in to save your settings",
                        style = TpType.caption.copy(fontSize = 14.5.sp),
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }

            if (account != null) {
                OutlinedPillButton(text = "Sign out", onClick = onSignOut, borderColor = palette.line4)
            } else {
                WhiteGoogleButton(text = "Sign in with Google", onClick = onSignInGoogle) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_google_logo), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Sign in with Google", style = TpType.badge.copy(fontSize = 15.5.sp, letterSpacing = 0.sp), color = androidx.compose.ui.graphics.Color(0xFF1A1D1B))
                    }
                }
            }

            SectionHeading("Appearance")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Day or night colours", style = TpType.cardTitle.copy(fontSize = 17.5.sp), color = palette.fg)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeOption("☀", "Day", state.theme == TpThemeMode.DAY, Modifier.weight(1f)) { onPickTheme(TpThemeMode.DAY) }
                    ThemeOption("☽", "Night", state.theme == TpThemeMode.NIGHT, Modifier.weight(1f)) { onPickTheme(TpThemeMode.NIGHT) }
                }
                Text(
                    "Night is easier on the eyes indoors. Day is clearer in bright sunlight.",
                    style = TpType.caption.copy(fontSize = 14.5.sp),
                    color = palette.muted,
                )
            }

            SectionHeading("Protection")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp)),
            ) {
                settingDefs.forEachIndexed { i, def ->
                    Column {
                        if (i > 0) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(def.name, style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg)
                                Text(
                                    if (def.key == "autoScan") state.settings.scheduleCaption() else def.caption,
                                    style = TpType.caption,
                                    color = palette.muted,
                                )
                                if (def.key == "autoScan" && state.settings.autoScan) {
                                    Text(
                                        "Change time ›",
                                        style = TpType.caption.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                        color = palette.accent,
                                        modifier = Modifier.clickable { showScanDialog = true },
                                    )
                                }
                            }
                            ToggleSwitch(
                                checked = state.settings.isOn(def.key, state.realtime),
                                onCheckedChange = { onToggleSetting(def.key) },
                            )
                        }
                    }
                }
            }

            if (showScanDialog) {
                ScheduledScanDialog(
                    initialHour = state.settings.scanHour,
                    initialMinute = state.settings.scanMinute,
                    initialFrequency = state.settings.scanFrequency,
                    initialDayOfWeek = state.settings.scanDayOfWeek,
                    onDismiss = { showScanDialog = false },
                    onSave = { hour, minute, frequency, dayOfWeek ->
                        onSetScheduledScanTime(hour, minute)
                        onSetScheduledScanFrequency(frequency, dayOfWeek)
                        showScanDialog = false
                    },
                )
            }

            SectionHeading("Threat intelligence sources")
            Text(
                "Free API keys from each provider — only NVD works with no key at all (just rate-limited). Add any of these and its feed lights up immediately across scans and QR checks.",
                style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = palette.muted,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ApiKeyId.entries.forEach { id ->
                    ApiKeyRow(id = id, value = state.apiKeys[id], onValueChange = { onSetApiKey(id, it) })
                }
            }

            SectionHeading("Quick access")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Quick Settings tile", style = TpType.cardTitle.copy(fontSize = 16.5.sp), color = palette.fg)
                Text(
                    "Add an App Permissions tile to your notification shade for one-tap access, even with the app closed.",
                    style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp),
                    color = palette.muted,
                )
                OutlinedPillButton(text = "Add Quick Settings tile", onClick = onAddQuickSettingsTile, borderColor = palette.accentBorder40, textColor = palette.accent)
            }

            SectionHeading("About")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Thread Protection AI Security", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg)
                Text("Version 2.4.1 · Threat definitions updated today", style = TpType.caption, color = palette.muted)
            }
        }

        BottomNavBar(active = NavTab.SETTINGS, onHome = onGoHome, onQr = onGoQr, onBrain = onGoBrain, onSettings = {})
    }
}

@Composable
private fun ApiKeyRow(id: ApiKeyId, value: String, onValueChange: (String) -> Unit) {
    val palette = LocalTpPalette.current
    val uriHandler = LocalUriHandler.current
    var draft by remember(id) { mutableStateOf(value) }
    val configured = value.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(id.label, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (configured) palette.accent else palette.muted3),
                )
                Text(
                    if (configured) "Live" else "Off",
                    style = TpType.badgeSmall,
                    color = if (configured) palette.accent else palette.muted,
                )
            }
        }
        TextField(
            value = draft,
            onValueChange = { draft = it; onValueChange(it) },
            singleLine = true,
            placeholder = { Text("Paste your free API key", color = palette.muted3) },
            visualTransformation = if (draft.isNotEmpty()) PasswordVisualTransformation('•') else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)),
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
        Text(
            "Get a free key → ${id.signupUrl}",
            style = TpType.caption.copy(fontSize = 13.sp),
            color = palette.accent,
            modifier = Modifier.clickable { runCatching { uriHandler.openUri(id.signupUrl) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledScanDialog(
    initialHour: Int,
    initialMinute: Int,
    initialFrequency: ScanFrequency,
    initialDayOfWeek: Int,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int, frequency: ScanFrequency, dayOfWeek: Int) -> Unit,
) {
    val palette = LocalTpPalette.current
    val timeState = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    var frequency by remember { mutableStateOf(initialFrequency) }
    var dayOfWeek by remember { mutableStateOf(initialDayOfWeek) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(palette.card)
                .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scheduled scan", style = TpType.cardTitle.copy(fontSize = 18.sp), color = palette.fg)
            Text(
                "Pick whatever time works for you — the scan runs quietly in the background and only notifies you if it finds something.",
                style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = palette.muted,
            )
            TimeInput(state = timeState)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeOption("", "Daily", frequency == ScanFrequency.DAILY, Modifier.weight(1f)) { frequency = ScanFrequency.DAILY }
                ThemeOption("", "Weekly", frequency == ScanFrequency.WEEKLY, Modifier.weight(1f)) { frequency = ScanFrequency.WEEKLY }
            }
            if (frequency == ScanFrequency.WEEKLY) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Calendar.MONDAY to "M", Calendar.TUESDAY to "T", Calendar.WEDNESDAY to "W",
                        Calendar.THURSDAY to "T", Calendar.FRIDAY to "F", Calendar.SATURDAY to "S", Calendar.SUNDAY to "S",
                    ).forEach { (day, label) ->
                        DayChip(label, dayOfWeek == day, Modifier.weight(1f)) { dayOfWeek = day }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedPillButton(text = "Cancel", onClick = onDismiss, borderColor = palette.line3, modifier = Modifier.weight(1f))
                PrimaryPillButton(
                    text = "Save",
                    onClick = { onSave(timeState.hour, timeState.minute, frequency, dayOfWeek) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(BorderStroke(1.dp, if (active) palette.accent else palette.line3), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TpType.caption.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = if (active) palette.onAccent else palette.fg2,
        )
    }
}

@Composable
private fun ThemeOption(glyph: String, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(BorderStroke(1.5.dp, if (active) palette.accent else palette.line3), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, fontSize = 20.sp, color = if (active) palette.onAccent else palette.fg2)
        Text(" $label", style = TpType.cardTitle.copy(fontSize = 17.sp), color = if (active) palette.onAccent else palette.fg2)
    }
}
