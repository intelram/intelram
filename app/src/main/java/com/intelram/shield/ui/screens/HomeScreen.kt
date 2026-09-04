package com.intelram.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.intelram.shield.scan.ScanUiState
import com.intelram.shield.scan.ScanViewModel
import com.intelram.shield.ui.components.IconTile
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.components.ScoreRing
import com.intelram.shield.ui.components.ToggleSwitch
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Blue
import com.intelram.shield.ui.theme.BlueSoft
import com.intelram.shield.ui.theme.Border
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Surface
import com.intelram.shield.ui.theme.SurfaceAlt

private data class ScanFeature(val icon: ImageVector, val title: String, val subtitle: String, val onClick: (() -> Unit)? = null)

@Composable
fun HomeScreen(
    scanViewModel: ScanViewModel,
    greetingName: String,
    onScanNow: () -> Unit,
    onOpenQrScanner: () -> Unit,
) {
    val uiState by scanViewModel.uiState.collectAsStateWithLifecycle()
    val realTime by scanViewModel.realTimeProtection.collectAsStateWithLifecycle()

    val features = listOf(
        ScanFeature(Icons.Filled.Security, "App Malware Scan", "Checks every installed app"),
        ScanFeature(Icons.Filled.NetworkCheck, "Wi-Fi & Network", "Checks your current connection"),
        ScanFeature(Icons.Filled.PrivacyTip, "Privacy Audit", "Flags nosy app permissions"),
        ScanFeature(Icons.Filled.QrCodeScanner, "QR Code Scanner", "Checks a code before you open it", onOpenQrScanner),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Good to see you", style = MaterialTheme.typography.bodySmall, color = InkFaint)
                    Text(greetingName, style = MaterialTheme.typography.headlineSmall)
                }
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Green),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        greetingName.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }

        item { ScoreCard(uiState, onScanNow) }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(background = GreenSoft) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = GreenDark)
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Real-Time Protection", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Blocks threats automatically, as they happen",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                    }
                    ToggleSwitch(checked = realTime, onCheckedChange = scanViewModel::setRealTimeProtection)
                }
            }
        }

        item {
            Text(
                "What we scan",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(((features.size + 1) / 2 * 118).dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(features) { feature -> FeatureTile(feature) }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceAlt),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "On-device detection",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    StatusRow("On-Device Detection Engine — Active")
                    StatusRow("Known-Threat Signature List — Local sample list")
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(uiState: ScanUiState, onScanNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Column(Modifier.padding(20.dp)) {
            when (uiState) {
                is ScanUiState.Done -> {
                    val report = uiState.report
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(score = report.overallScore, color = if (report.overallScore >= 70) Green else Blue)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                if (report.totalFlaggedCount == 0) "You're Protected" else "Needs Attention",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (report.totalFlaggedCount == 0) GreenDark else InkSoft,
                            )
                            Text(
                                "${report.totalFlaggedCount} issue(s) found",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkFaint,
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
                    Text(
                        "Rescan",
                        color = GreenDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onScanNow),
                    )
                }
                else -> {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = GreenDark, modifier = Modifier.size(40.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    Text("Run your first scan", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "See your security score and any risks in under a minute",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    PrimaryButton(text = "Scan Now", onClick = onScanNow)
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(feature: ScanFeature) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (feature.onClick != null) Modifier.clickable(onClick = feature.onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            IconTile(background = BlueSoft) {
                Icon(feature.icon, contentDescription = null, tint = Blue, modifier = Modifier.size(20.dp))
            }
            Text(
                feature.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(feature.subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
    }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Green))
        Text("  $text", style = MaterialTheme.typography.bodySmall, color = InkSoft)
    }
}
