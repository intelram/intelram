package com.intelram.shield.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intelram.shield.scan.ScannedApp
import com.intelram.shield.ui.RiskBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(app: ScannedApp, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app.appName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RiskBadge(app.riskLevel)
                            Text(
                                "  Risk score: ${app.riskScore}/100",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(app.packageName, modifier = Modifier.padding(top = 8.dp))
                        Text("Version: ${app.versionName ?: "unknown"}")
                        Text("System app: ${if (app.isSystemApp) "yes" else "no"}")
                        Text("Installed from: ${app.installerPackageName ?: "unknown / sideloaded"}")
                        if (app.sha256 != null) {
                            Text(
                                "SHA-256: ${app.sha256}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    if (app.findings.isEmpty()) "No findings" else "Findings (${app.findings.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(app.findings) { finding ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RiskBadge(finding.severity)
                            Text(
                                "  ${finding.title}",
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            finding.description,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item {
                Text(
                    "Sensitive permissions (${app.dangerousPermissions.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(app.dangerousPermissions) { permission ->
                Text(
                    "• ${permission.substringAfterLast('.')}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
