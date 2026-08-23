package com.intelram.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.intelram.shield.scan.Finding
import com.intelram.shield.scan.FindingCategory
import com.intelram.shield.scan.ScanUiState
import com.intelram.shield.scan.ScanViewModel
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.components.ScoreRing
import com.intelram.shield.ui.components.SecondaryButton
import com.intelram.shield.ui.components.colorForRisk
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Border
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.Ink
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Surface
import com.intelram.shield.ui.theme.SurfaceAlt

@Composable
fun ResultsScreen(
    scanViewModel: ScanViewModel,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onOpenFinding: (String) -> Unit,
) {
    val uiState by scanViewModel.uiState.collectAsStateWithLifecycle()
    val report = (uiState as? ScanUiState.Done)?.report ?: return

    var filter by remember { mutableStateOf<FindingCategory?>(null) }
    val filtered = report.allFindings.filter { filter == null || it.category == filter }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.padding(top = 48.dp, start = 8.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "Back", modifier = Modifier.size(18.dp))
            }
            Text("Scan Complete", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Just now · Full device scan",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
            modifier = Modifier.padding(start = 60.dp, bottom = 8.dp),
        )

        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScoreRing(score = report.overallScore, color = if (report.overallScore >= 70) Green else colorForRisk(com.intelram.shield.scan.RiskLevel.HIGH), size = 76.dp)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    if (report.totalFlaggedCount == 0) "Looking Good" else "Needs Attention",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${report.totalFlaggedCount} issue(s) found across ${report.allFindings.map { it.category }.distinct().size} categories",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip("All (${report.allFindings.size})", filter == null) { filter = null } }
            items(FindingCategory.entries) { category ->
                val count = report.allFindings.count { it.category == category }
                if (count > 0) {
                    FilterChip("${category.label} ($count)", filter == category) { filter = category }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing flagged here", color = InkFaint)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { finding -> FindingRow(finding) { onOpenFinding(finding.id) } }
            }
        }

        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = if (report.allFindings.isEmpty()) "All Clear" else "Review Top Issue",
                enabled = report.allFindings.isNotEmpty(),
                onClick = { report.allFindings.firstOrNull()?.let { onOpenFinding(it.id) } },
            )
            SecondaryButton(text = "Rescan Device", onClick = onRescan)
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Ink else Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Surface else InkSoft,
        )
    }
}

@Composable
private fun FindingRow(finding: Finding, onClick: () -> Unit) {
    val color = colorForRisk(finding.severity)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp)) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(color))
            Row(
                modifier = Modifier.padding(14.dp).weight(1f),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        finding.severity.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(finding.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
                    Text(finding.description, style = MaterialTheme.typography.bodySmall, color = InkSoft, modifier = Modifier.padding(top = 3.dp))
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(SurfaceAlt)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(finding.category.label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = InkFaint)
            }
        }
    }
}
