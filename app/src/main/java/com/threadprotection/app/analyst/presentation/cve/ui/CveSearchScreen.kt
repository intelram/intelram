package com.threadprotection.app.analyst.presentation.cve.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threadprotection.app.analyst.domain.model.CvePriority
import com.threadprotection.app.analyst.domain.model.CveSummary
import com.threadprotection.app.analyst.presentation.cve.CveSearchUiState
import com.threadprotection.app.analyst.presentation.cve.CveViewModel
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * CVE Search — Analyst Mode Module 3 (Vulnerability Intelligence). Search NVD by keyword or exact
 * CVE id; each result shows the composite [CvePriority] badge rather than raw CVSS alone, per the
 * master spec's "never show raw CVSS alone as the primary risk signal."
 */
@Composable
fun CveSearchScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CveViewModel = hiltViewModel(),
) {
    val palette = LocalTpPalette.current
    val state by viewModel.searchState.collectAsState()
    val query by viewModel.query.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Column {
                    Text("CVE Search", style = TpType.screenTitle, color = palette.fg)
                    Text("Analyst Mode", style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.accent)
                }
            }
            Text(
                "Search NVD by keyword or exact CVE ID. Priority combines CVSS severity, EPSS exploitation likelihood and confirmed CISA KEV status — not CVSS alone.",
                style = TpType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = palette.muted,
            )
            TextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                placeholder = { Text("e.g. CVE-2021-44228, or \"log4j\"") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.card,
                    unfocusedContainerColor = palette.card,
                    focusedIndicatorColor = palette.accent,
                    unfocusedIndicatorColor = palette.line,
                    focusedTextColor = palette.fg,
                    unfocusedTextColor = palette.fg,
                ),
            )
            OutlinedPillButton(text = "Search", onClick = viewModel::search, borderColor = palette.accentBorder40, textColor = palette.accent)
        }

        when (val s = state) {
            is CveSearchUiState.Idle -> EmptyState("Search for a CVE to see its full NVD record, EPSS score and KEV status.")
            is CveSearchUiState.Loading -> LoadingState()
            is CveSearchUiState.Error -> ErrorState(s.message, onRetry = viewModel::search)
            is CveSearchUiState.Results -> {
                if (s.results.isEmpty()) {
                    EmptyState("No CVEs matched that search.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(s.results, key = { it.id }) { cve ->
                            CveSummaryRow(cve, onClick = { onOpenDetail(cve.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CveSummaryRow(cve: CveSummary, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    val (color, tint) = priorityColors(cve.priority)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(cve.id, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
            Text(cve.description, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.sp), color = palette.muted, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cve.cvssScore?.let {
                    Text("CVSS %.1f".format(it), style = TpType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = palette.fg2)
                }
                if (cve.isKev) {
                    Text("KEV", style = TpType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = palette.danger)
                }
            }
        }
        Box(
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(tint).padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(cve.priority.name, style = TpType.badge.copy(fontSize = 10.5.sp, letterSpacing = 0.6.sp), color = color)
        }
    }
}

@Composable
internal fun priorityColors(priority: CvePriority): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val palette = LocalTpPalette.current
    return when (priority) {
        CvePriority.CRITICAL -> palette.danger to palette.dangerTint08
        CvePriority.HIGH -> palette.warn2 to palette.warnTint06
        CvePriority.MEDIUM -> palette.warn to palette.warnTint06
        CvePriority.LOW -> palette.muted to palette.card
    }
}

@Composable
internal fun EmptyState(message: String) {
    val palette = LocalTpPalette.current
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = TpType.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = palette.muted, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun LoadingState() {
    val palette = LocalTpPalette.current
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = palette.accent)
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    val palette = LocalTpPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.weight(1f))
        Text("Something went wrong", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg, textAlign = TextAlign.Center)
        Text(message, style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp), color = palette.muted, textAlign = TextAlign.Center)
        OutlinedPillButton(text = "Retry", onClick = onRetry, borderColor = palette.line3)
        Box(modifier = Modifier.weight(1f))
    }
}
