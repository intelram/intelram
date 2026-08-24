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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.R
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.ProtectionSettings
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.components.ToggleSwitch
import com.threadprotection.app.ui.components.WhiteGoogleButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpThemeMode
import com.threadprotection.app.ui.theme.TpType

private data class SettingRow(val key: String, val name: String, val caption: String)

private val settingDefs = listOf(
    SettingRow("realtime", "Real-time protection", "Behavior-based monitoring of apps and network"),
    SettingRow("autoScan", "Scheduled scans", "Full scan every day at 3:00 AM"),
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
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val account = state.account

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
                                Text(def.caption, style = TpType.caption, color = palette.muted)
                            }
                            ToggleSwitch(
                                checked = state.settings.isOn(def.key, state.realtime),
                                onCheckedChange = { onToggleSetting(def.key) },
                            )
                        }
                    }
                }
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
