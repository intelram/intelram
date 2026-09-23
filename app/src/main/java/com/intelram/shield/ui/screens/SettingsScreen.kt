package com.intelram.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.intelram.shield.auth.AuthUiState
import com.intelram.shield.auth.AuthViewModel
import com.intelram.shield.scan.ScanViewModel
import com.intelram.shield.ui.components.ToggleSwitch
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Red
import com.intelram.shield.ui.theme.Surface

@Composable
fun SettingsScreen(
    scanViewModel: ScanViewModel,
    authViewModel: AuthViewModel,
    onSignOut: () -> Unit,
    onOpenEmailCheck: () -> Unit,
) {
    val realTime by scanViewModel.realTimeProtection.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val dismissedCount by scanViewModel.dismissedFindingCount.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(20.dp, 48.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        (authState as? AuthUiState.SignedIn)?.let { signedIn ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(48.dp).background(Green, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (signedIn.displayName ?: signedIn.email).take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(signedIn.displayName ?: "Signed in", style = MaterialTheme.typography.titleMedium)
                            Text(signedIn.email, style = MaterialTheme.typography.bodySmall, color = InkFaint)
                        }
                    }
                }
            }
        }

        item { SectionLabel("Protection") }
        item {
            SettingsGroup {
                ToggleRow(
                    title = "Real-Time Protection",
                    subtitle = "Blocks threats as they happen",
                    checked = realTime,
                    onCheckedChange = scanViewModel::setRealTimeProtection,
                )
            }
        }

        item { SectionLabel("On-Device Detection") }
        item {
            SettingsGroup {
                InfoRow("Detection engine", "Runs entirely on this device")
                InfoRow("Threat signature list", "Local sample list, bundled with the app")
                InfoRow("Link threat intel", "Google Safe Browsing + abuse.ch URLhaus")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenEmailCheck)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Email, contentDescription = null, tint = InkSoft, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Email & Link Check", style = MaterialTheme.typography.titleMedium)
                        Text("A phishing checklist, and a link checker for emails", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = InkFaint)
                }
            }
        }

        item { SectionLabel("Adaptive Learning") }
        item {
            SettingsGroup {
                InfoRow(
                    "Findings dismissed as \"not a threat\"",
                    if (dismissedCount == 0) {
                        "None yet — mark a finding \"Not a Threat\" on its detail screen to stop seeing it"
                    } else {
                        "$dismissedCount — won't be raised again on future scans"
                    },
                )
                if (dismissedCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = scanViewModel::resetLearnedExceptions)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Reset Learned Exceptions",
                            style = MaterialTheme.typography.titleMedium,
                            color = Red,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item { SectionLabel("Account") }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSignOut)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
                    Text(
                        "  Sign Out",
                        style = MaterialTheme.typography.titleMedium,
                        color = Red,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Text(
                "Threat Protection · Version 1.0",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = InkFaint,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(content = content)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
        ToggleSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
    }
}
