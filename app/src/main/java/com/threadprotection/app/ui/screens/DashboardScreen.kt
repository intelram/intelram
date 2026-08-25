package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.Derived
import com.threadprotection.app.state.ScanStatus
import com.threadprotection.app.ui.components.BlinkingDot
import com.threadprotection.app.ui.components.ConicProgressRing
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.ToggleSwitch
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

@Composable
fun DashboardScreen(
    state: AppUiState,
    onToggleTheme: () -> Unit,
    onStartScan: () -> Unit,
    onToggleRealtime: () -> Unit,
    onGoQr: () -> Unit,
    onGoPerms: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onGoOtpSecurity: () -> Unit,
    onGoDataBreach: () -> Unit,
    onGoScanWebsite: () -> Unit,
    onGoHardwareDetail: () -> Unit,
    onGoPortsDetail: () -> Unit,
    onGoOsDetail: () -> Unit,
    onToggleHwOpen: () -> Unit,
    onSimulateHw: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val status = Derived.scanStatus(state)
    val statusColor = when (status) {
        ScanStatus.NEEDED -> palette.warn
        ScanStatus.AT_RISK -> palette.danger
        ScanStatus.PROTECTED -> palette.accent
    }
    val statusBg = when (status) {
        ScanStatus.NEEDED -> palette.warnTint14
        ScanStatus.AT_RISK -> palette.dangerTint14
        ScanStatus.PROTECTED -> palette.accentTint14
    }
    val score = Derived.securityScore(state)

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Thread Protection", style = TpType.dashboardHeader, color = palette.fg)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusBg)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            when (status) {
                                ScanStatus.NEEDED -> "SCAN NEEDED"
                                ScanStatus.AT_RISK -> "AT RISK"
                                ScanStatus.PROTECTED -> "PROTECTED"
                            },
                            style = TpType.badgeSmall,
                            color = statusColor,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .border(BorderStroke(1.5.dp, palette.line3), CircleShape)
                            .clickable(onClick = onToggleTheme),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (state.theme == com.threadprotection.app.ui.theme.TpThemeMode.NIGHT) "☀" else "☽", color = palette.fg2, fontSize = 19.sp)
                    }
                }
            }

            // Score card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConicProgressRing(
                    size = 200.dp,
                    inset = 14.dp,
                    progressFraction = score / 100f,
                    activeColor = statusColor,
                    trackColor = palette.line,
                    innerBackground = palette.card,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$score", style = TpType.scoreNumber, color = statusColor)
                        Text("SECURITY SCORE", style = TpType.caption.copy(letterSpacing = 0.84.sp), color = palette.muted)
                    }
                }
                Text(Derived.scoreCaption(state), style = TpType.body.copy(fontSize = 16.sp), color = palette.muted, textAlign = TextAlign.Center)
                PrimaryPillButton(text = "Scan now", onClick = onStartScan)
            }

            // Real-time protection
            DashboardRow(
                title = "Real-time protection",
                caption = if (state.realtime) "Active — monitoring in background" else "Off — your device is exposed",
                trailing = { ToggleSwitch(checked = state.realtime, onCheckedChange = onToggleRealtime) },
            )

            // QR entry
            ClickableCard(onClick = onGoQr) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(palette.accentTint12)
                        .border(BorderStroke(1.dp, palette.accentBorder30), RoundedCornerShape(11.dp)),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Scan a QR code", style = TpType.cardTitle, color = palette.fg)
                    Text("Check a code against live feeds before you open it", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                }
                Text("›", color = palette.muted, fontSize = 22.sp)
            }

            // Permissions entry
            ClickableCard(onClick = onGoPerms) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.warnTint12)
                        .border(BorderStroke(1.dp, palette.line3), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚿", color = palette.warn, fontSize = 22.sp)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("App Permissions", style = TpType.cardTitle, color = palette.fg)
                    Text("${Derived.riskyPermTotal(state)} permissions look unnecessary", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                }
                Text("›", color = palette.muted, fontSize = 22.sp)
            }

            com.threadprotection.app.ui.components.SectionHeading("Security tools")
            ClickableCard(onClick = onGoOtpSecurity) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.warnTint12)
                        .border(BorderStroke(1.dp, palette.line3), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✉", color = palette.warn, fontSize = 20.sp)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("OTP security", style = TpType.cardTitle, color = palette.fg)
                    Text("See which apps can read your text messages", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                }
                Text("›", color = palette.muted, fontSize = 22.sp)
            }
            ClickableCard(onClick = onGoDataBreach) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.dangerTint12)
                        .border(BorderStroke(1.dp, palette.line3), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", color = palette.danger, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Data breach security", style = TpType.cardTitle, color = palette.fg)
                    Text("Check your email against live breach records", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                }
                Text("›", color = palette.muted, fontSize = 22.sp)
            }
            ClickableCard(onClick = onGoScanWebsite) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accentTint12)
                        .border(BorderStroke(1.dp, palette.accentBorder30), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔗", fontSize = 18.sp)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Scan a website", style = TpType.cardTitle, color = palette.fg)
                    Text("Paste any link to check it before you open it", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                }
                Text("›", color = palette.muted, fontSize = 22.sp)
            }

            HardwareWatchCard(state, onToggleHwOpen, onSimulateHw)

            // AI brain entry
            ClickableCard(onClick = onGoBrain) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accentTint12)
                        .border(BorderStroke(1.dp, palette.lineHover), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    BlinkingDot(12.dp, palette.accent)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("How it learns", style = TpType.cardTitle, color = palette.fg)
                    Text("${String.format("%,d", state.learned)} patterns learned this month", style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                }
                Text("›", color = palette.muted, fontSize = 22.sp)
            }

            // Threat intelligence
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                com.threadprotection.app.ui.components.SectionHeading("Threat intelligence")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlinkingDot(6.dp, palette.accent)
                    Text("LIVE", style = TpType.caption.copy(fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 0.36.sp), color = palette.accent)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp)),
            ) {
                DemoData.feeds.forEach { feed ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BlinkingDot(7.dp, palette.accent, durationMs = 2000)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(feed.name, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
                                Text(feed.kind, style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.muted)
                            }
                            Text(feed.items, style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.muted2)
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
                    }
                }
                Text(DemoData.feedSyncLine, style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.muted2, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }

            // System audit
            com.threadprotection.app.ui.components.SectionHeading("System audit")
            val areas = Derived.systemAuditAreas(state)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                areas.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { area ->
                            val onClick = when (area.cat) {
                                com.threadprotection.app.data.Category.SOFTWARE -> onGoPerms
                                com.threadprotection.app.data.Category.HARDWARE -> onGoHardwareDetail
                                com.threadprotection.app.data.Category.PORTS -> onGoPortsDetail
                                com.threadprotection.app.data.Category.OS -> onGoOsDetail
                                else -> null
                            }
                            AuditAreaTile(area, onClick, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        BottomNavBar(
            active = NavTab.HOME,
            onHome = {},
            onQr = onGoQr,
            onBrain = onGoBrain,
            onSettings = onGoSettings,
        )
    }
}

@Composable
private fun AuditAreaTile(area: com.threadprotection.app.state.AuditArea, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(area.name, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (area.hasIssue) palette.danger else if (area.scanned) palette.accent else palette.muted3),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                area.caption,
                style = TpType.caption.copy(fontSize = 14.sp),
                color = if (area.hasIssue) palette.danger2 else palette.muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onClick != null) {
                Text("›", style = TpType.caption.copy(fontSize = 14.sp), color = palette.muted3)
            }
        }
    }
}

@Composable
private fun DashboardRow(title: String, caption: String, trailing: @Composable () -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TpType.cardTitle, color = palette.fg)
            Text(caption, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
        }
        trailing()
    }
}

@Composable
private fun ClickableCard(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        content = content,
    )
}

@Composable
private fun HardwareWatchCard(state: AppUiState, onToggleOpen: () -> Unit, onSimulate: () -> Unit) {
    val palette = LocalTpPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BlinkingDot(9.dp, palette.accent)
            Text("Hardware watch", style = TpType.cardTitle, color = palette.fg, modifier = Modifier.weight(1f))
            Text("ON", style = TpType.badgeSmall, color = palette.accent)
        }
        Text(
            "We check anything plugged in or paired — cables, chargers, keyboards, Bluetooth, SIM and memory cards.",
            style = TpType.caption.copy(fontSize = 14.5.sp),
            color = palette.muted,
        )
        if (!state.hwOpen) {
            Text(Derived.hwSummary(state), style = TpType.cardTitle.copy(fontSize = 15.5.sp), color = palette.fg2)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                state.liveHwDevices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (d.ok) palette.accent else palette.danger))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(d.name, style = TpType.cardTitle.copy(fontSize = 15.5.sp), color = palette.fg)
                            Text(d.detail, style = TpType.caption.copy(fontSize = 13.5.sp), color = if (d.ok) palette.muted else palette.danger2)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (d.ok) palette.accentTint14 else palette.dangerTint14)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(if (d.ok) "Safe" else "Blocked", style = TpType.badgeSmall, color = if (d.ok) palette.accent else palette.danger)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .border(BorderStroke(1.5.dp, palette.line3), RoundedCornerShape(999.dp))
                .clickable(onClick = onToggleOpen)
                .padding(15.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (state.hwOpen) "Hide the details" else "Show all ${state.liveHwDevices.size} devices", style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg2)
            Text(if (state.hwOpen) " ⌃" else " ⌄", style = TpType.cardTitle.copy(fontSize = 15.sp), color = palette.fg2)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .border(BorderStroke(1.5.dp, palette.line3), RoundedCornerShape(999.dp))
                .clickable(onClick = onSimulate)
                .padding(17.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("Show me what an alert looks like", style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg2)
        }
    }
}
