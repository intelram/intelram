package com.threadprotection.app.analyst.presentation.cve.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threadprotection.app.analyst.domain.model.CveDetail
import com.threadprotection.app.analyst.presentation.cve.CveDetailUiState
import com.threadprotection.app.analyst.presentation.cve.CveViewModel
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** CVE Detail — full NVD record, EPSS score, KEV status, composite priority, watchlist toggle. */
@Composable
fun CveDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CveViewModel = hiltViewModel(),
) {
    val palette = LocalTpPalette.current
    val state by viewModel.detailState.collectAsState()
    val isWatched by viewModel.isSelectedWatched.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackCircleButton(onClick = onBack)
            Text("CVE Detail", style = TpType.screenTitle, color = palette.fg)
        }

        when (val s = state) {
            is CveDetailUiState.Idle -> EmptyState("Nothing selected.")
            is CveDetailUiState.Loading -> LoadingState()
            is CveDetailUiState.Error -> ErrorState(s.message, onRetry = viewModel::retryDetail)
            is CveDetailUiState.Success -> DetailBody(
                cve = s.cve,
                isWatched = isWatched,
                onToggleWatch = { viewModel.toggleWatch(s.cve.id, !isWatched) },
            )
        }
    }
}

@Composable
private fun DetailBody(cve: CveDetail, isWatched: Boolean, onToggleWatch: () -> Unit) {
    val palette = LocalTpPalette.current
    val (priorityColor, priorityTint) = priorityColors(cve.priority)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(cve.id, style = TpType.cardTitleBold.copy(fontSize = 21.sp), color = palette.fg)
            Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(priorityTint).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(cve.priority.name, style = TpType.badge.copy(fontSize = 11.sp, letterSpacing = 0.7.sp), color = priorityColor)
            }
        }
        Text(cve.description, style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.5.sp), color = palette.fg2)

        // The three signals behind the priority badge, shown side by side so the analyst can see
        // exactly why it landed where it did — never just the composite result on its own.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("CVSS", cve.cvssScore?.let { "%.1f".format(it) } ?: "—", cve.cvssSeverity ?: "Unscored", Modifier.weight(1f))
            MetricTile(
                "EPSS",
                cve.epssScore?.let { "%.0f%%".format(it * 100) } ?: "—",
                cve.epssPercentile?.let { "P%.0f".format(it * 100) } ?: "No score",
                Modifier.weight(1f),
            )
            MetricTile(
                "KEV",
                if (cve.kev.isKnownExploited) "Yes" else "No",
                if (cve.kev.isKnownExploited) "Confirmed exploited" else "Not listed",
                Modifier.weight(1f),
                emphasize = cve.kev.isKnownExploited,
            )
        }

        if (cve.kev.isKnownExploited) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.dangerTint08)
                    .border(BorderStroke(1.dp, palette.dangerBorder30), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    cve.kev.vulnerabilityName ?: "Confirmed CISA Known Exploited Vulnerability",
                    style = TpType.cardTitle.copy(fontSize = 14.5.sp),
                    color = palette.danger,
                )
                cve.kev.requiredAction?.let { Text(it, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.sp), color = palette.fg2) }
                cve.kev.dueDate?.let { Text("Federal remediation due: $it", style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted) }
            }
        }

        cve.cvssVectorString?.let { vector ->
            LabeledValue("CVSS ${cve.cvssVersion.orEmpty()} vector", vector, monospace = true)
        }

        if (cve.cweIds.isNotEmpty()) {
            LabeledValue("Weakness (CWE)", cve.cweIds.joinToString(", "))
        }

        if (cve.affectedProducts.isNotEmpty()) {
            SectionCard(title = "Affected products (${cve.affectedProducts.size})") {
                cve.affectedProducts.take(15).forEach { cpe ->
                    Text(cpe, style = TpType.caption.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace), color = palette.fg2)
                }
                if (cve.affectedProducts.size > 15) {
                    Text("+ ${cve.affectedProducts.size - 15} more", style = TpType.caption.copy(fontSize = 12.sp), color = palette.muted)
                }
            }
        }

        if (cve.references.isNotEmpty()) {
            SectionCard(title = "References (${cve.references.size})") {
                cve.references.take(10).forEach { ref ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ref.url, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.accent, maxLines = 1)
                        if (ref.tags.isNotEmpty()) {
                            Text(ref.tags.joinToString(" · "), style = TpType.caption.copy(fontSize = 11.sp), color = palette.muted)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            if (isWatched) {
                OutlinedPillButton(text = "Remove from watchlist", onClick = onToggleWatch, borderColor = palette.line3, modifier = Modifier.weight(1f))
            } else {
                PrimaryPillButton(text = "Add to watchlist", onClick = onToggleWatch, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, caption: String, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (emphasize) palette.dangerTint08 else palette.card)
            .border(BorderStroke(1.dp, if (emphasize) palette.dangerBorder30 else palette.line), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = TpType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = palette.muted)
        Text(value, style = TpType.cardTitleBold.copy(fontSize = 19.sp), color = if (emphasize) palette.danger else palette.fg)
        Text(caption, style = TpType.caption.copy(fontSize = 11.sp), color = palette.muted)
    }
}

@Composable
private fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    val palette = LocalTpPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = TpType.caption.copy(fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold), color = palette.muted)
        Text(
            value,
            style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
            color = palette.fg2,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val palette = LocalTpPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = TpType.cardTitle.copy(fontSize = 14.5.sp), color = palette.fg)
        content()
    }
}
