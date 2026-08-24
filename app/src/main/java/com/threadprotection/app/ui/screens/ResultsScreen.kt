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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.Finding
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.Derived
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.SeverityBadgeFor
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.Severity
import com.threadprotection.app.ui.theme.TpType
import com.threadprotection.app.ui.theme.severityColor

@Composable
fun ResultsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onFixAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val threats = Derived.threats(state)
    val active = Derived.activeThreats(state)
    val hasThreats = threats.isNotEmpty()

    val headline = if (active.isEmpty()) {
        if (hasThreats) "All threats resolved" else "No threats found"
    } else if (active.size > 1) {
        "threats need your attention"
    } else {
        "threat needs your attention"
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Scan complete", style = TpType.screenTitle, color = palette.fg)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active.isNotEmpty()) palette.dangerTint08 else palette.accentTint08)
                    .border(BorderStroke(1.dp, if (active.isNotEmpty()) palette.dangerBorder30 else palette.accentBorder30), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "${active.size}",
                    style = TpType.cardTitleBold.copy(fontSize = 31.5.sp),
                    color = if (active.isNotEmpty()) palette.danger else palette.accent,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(headline, style = TpType.cardTitle, color = palette.fg)
                    Text(
                        "${state.scannedCount.let { "%,d".format(it) }} items scanned across software, services, ports, licences and OS",
                        style = TpType.caption.copy(fontSize = 14.5.sp),
                        color = palette.muted,
                    )
                }
            }

            val inventory = listOf(
                "Apps & packages" to "252",
                "Services & tasks" to "61",
                "Ports probed" to "65,535",
                "Hardware devices" to "6",
                "Licences verified" to "214",
                "Leaked records searched" to "14B",
                "Breaches matching you" to if (state.account != null) "${com.threadprotection.app.data.DemoData.breachSites.size}" else "—",
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                inventory.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (label, value) -> InventoryTile(label, value, Modifier.weight(1f)) }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (hasThreats) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    threats.forEach { finding ->
                        ThreatRow(finding = finding, fixed = finding.id in state.fixed, onClick = { onOpen(finding.id) })
                    }
                }
            } else {
                Text(
                    "No active threats. Your device is fully protected.",
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
            if (hasThreats) {
                PrimaryPillButton(text = "Fix all threats", onClick = onFixAll)
            } else {
                OutlinedPillButton(text = "Back to dashboard", onClick = onBack, borderColor = palette.line3)
            }
        }
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
private fun ThreatRow(finding: Finding, fixed: Boolean, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    val sev = if (fixed) Severity.FIXED else finding.sev
    val sevColor = palette.severityColor(sev)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (fixed) 0.55f else 1f)
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
                if (fixed) "Resolved" else "Risk potential ${finding.risk}/100",
                style = TpType.caption.copy(fontSize = 13.5.sp),
                color = palette.muted,
            )
        }
    }
}
