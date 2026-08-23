package com.intelram.shield.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intelram.shield.scan.RiskLevel
import com.intelram.shield.scan.ScanReport
import com.intelram.shield.scan.ScanUiState
import com.intelram.shield.scan.ScannedApp
import com.intelram.shield.ui.RiskBadge
import com.intelram.shield.ui.colorForRisk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: ScanUiState,
    onScan: () -> Unit,
    onAppClick: (ScannedApp) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null)
                        Text("  IntelRAM Shield", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when (state) {
                is ScanUiState.Idle -> IdleContent(onScan)
                is ScanUiState.Scanning -> ScanningContent()
                is ScanUiState.Done -> ResultsContent(state.report, onScan, onAppClick)
            }
        }
    }
}

@Composable
private fun IdleContent(onScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Security,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Scan this device for risky apps and\nweak security settings",
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(onClick = onScan) { Text("Start Scan") }
    }
}

@Composable
private fun ScanningContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text("Scanning installed apps…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun ResultsContent(
    report: ScanReport,
    onScan: () -> Unit,
    onAppClick: (ScannedApp) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ScoreCard(report, onScan) }

        if (report.deviceChecks.isNotEmpty()) {
            item {
                Text(
                    "Device checks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(report.deviceChecks) { check ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RiskBadge(check.severity)
                            Text(
                                check.title,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(
                            check.description,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Apps (${report.apps.size}) — ${report.flaggedAppCount} flagged",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(report.apps) { app -> AppRow(app, onClick = { onAppClick(app) }) }
    }
}

@Composable
private fun ScoreCard(report: ScanReport, onScan: () -> Unit) {
    val level = when {
        report.overallScore >= 85 -> RiskLevel.CLEAN
        report.overallScore >= 60 -> RiskLevel.LOW
        report.overallScore >= 40 -> RiskLevel.MEDIUM
        else -> RiskLevel.HIGH
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                CircularProgressIndicator(
                    progress = { report.overallScore / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = colorForRisk(level),
                    trackColor = colorForRisk(level).copy(alpha = 0.15f),
                    strokeWidth = 6.dp,
                )
                Text("${report.overallScore}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text("Security score", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${report.flaggedAppCount} flagged app(s), " +
                        "${report.deviceChecks.size} device warning(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Rescan",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable(onClick = onScan),
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: ScannedApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .aspectRatio(1f)
                    .background(colorForRisk(app.riskLevel), CircleShape),
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(app.appName, fontWeight = FontWeight.Medium)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            RiskBadge(app.riskLevel)
        }
    }
}
