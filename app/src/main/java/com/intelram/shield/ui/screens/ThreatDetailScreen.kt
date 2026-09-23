package com.intelram.shield.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CancelPresentation
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.intelram.shield.scan.Finding
import com.intelram.shield.scan.FixAction
import com.intelram.shield.scan.RiskLevel
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.components.SecondaryButton
import com.intelram.shield.ui.components.SeverityBadge
import com.intelram.shield.ui.components.colorForRisk
import com.intelram.shield.ui.components.softColorForRisk
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Red
import com.intelram.shield.ui.theme.Surface

@Composable
fun ThreatDetailScreen(finding: Finding?, onBack: () -> Unit, onDismissFinding: (String) -> Unit) {
    if (finding == null) {
        Column(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
            Text("This finding is no longer available — try rescanning.", color = InkSoft)
        }
        return
    }

    val context = LocalContext.current
    var actionMessage by remember(finding.id) { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(top = 44.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "Back", modifier = Modifier.size(18.dp))
                }
                Text("Threat Details", style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = softColorForRisk(finding.severity)),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = colorForRisk(finding.severity))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("${finding.severity.label} Risk", color = colorForRisk(finding.severity), style = MaterialTheme.typography.titleMedium)
                        Text("From today's scan", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                }
            }
        }

        item {
            Text(
                finding.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            )
            finding.sourceApp?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
            }
        }

        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("What this means", style = MaterialTheme.typography.titleMedium)
                Text(finding.description, style = MaterialTheme.typography.bodyMedium, color = InkSoft, modifier = Modifier.padding(top = 4.dp))
                Text(finding.whyItMatters, style = MaterialTheme.typography.bodyMedium, color = InkSoft, modifier = Modifier.padding(top = 8.dp))
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("If you fix this", color = GreenDark, style = MaterialTheme.typography.titleMedium)
                    prosFor(finding).forEach { ProConRow(it, positive = true) }

                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.HorizontalDivider()
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))

                    Text("If you ignore this", color = Red, style = MaterialTheme.typography.titleMedium)
                    consFor(finding).forEach { ProConRow(it, positive = false) }
                }
            }
        }

        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val fix = finding.fixAction) {
                    is FixAction.UninstallApp -> {
                        PrimaryButton(text = "Fix Now — Uninstall App") {
                            actionMessage = try {
                                context.startActivity(
                                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${fix.packageName}")),
                                )
                                "Complete the uninstall in the system dialog, then rescan to confirm."
                            } catch (e: ActivityNotFoundException) {
                                "Couldn't open the uninstall dialog on this device."
                            }
                        }
                    }
                    is FixAction.OpenSystemSettings -> {
                        PrimaryButton(text = "Fix Now — Open Settings") {
                            actionMessage = try {
                                context.startActivity(Intent(fix.settingsAction))
                                "Make the change in Settings, then rescan to confirm."
                            } catch (e: ActivityNotFoundException) {
                                "This device doesn't expose that settings screen directly — check Settings > Security."
                            }
                        }
                    }
                    FixAction.None -> {
                        PrimaryButton(text = "Got It", onClick = { actionMessage = null; onBack() })
                    }
                }
                actionMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                SecondaryButton(
                    text = "Not a Threat — Don't Flag Again",
                    onClick = {
                        onDismissFinding(finding.id)
                        onBack()
                    },
                )
            }
        }
    }
}

private fun prosFor(finding: Finding): List<String> = when (finding.fixAction) {
    is FixAction.UninstallApp -> listOf(
        "Removes the app and this risk immediately",
        "Any permissions it had are revoked instantly",
    )
    is FixAction.OpenSystemSettings -> listOf(
        "Takes under a minute to change in Settings",
        "Closes off a common way devices get compromised",
    )
    FixAction.None -> listOf("You're already aware of it and can decide what to do")
}

private fun consFor(finding: Finding): List<String> = when (finding.severity) {
    RiskLevel.CRITICAL, RiskLevel.HIGH -> listOf(
        "This risk stays active every time you use your device",
        "It could be used against you before you notice",
    )
    else -> listOf("This risk stays active until you address it")
}

@Composable
private fun ProConRow(text: String, positive: Boolean) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (positive) Icons.Filled.CheckCircle else Icons.Filled.CancelPresentation,
            contentDescription = null,
            tint = if (positive) GreenDark else Red,
            modifier = Modifier.size(17.dp),
        )
        Text("  $text", style = MaterialTheme.typography.bodyMedium)
    }
}
