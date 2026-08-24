package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.ui.components.BlinkingDot
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.components.StatTile
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

@Composable
fun AiBrainScreen(
    learned: Long,
    onGoHome: () -> Unit,
    onGoQr: () -> Unit,
    onGoSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("How it learns", style = TpType.screenTitleLg, color = palette.fg)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BlinkingDot(9.dp, palette.accent)
                    Text("LEARNING RIGHT NOW", style = TpType.badge.copy(fontSize = 13.5.sp), color = palette.accent)
                }
                Text(String.format("%,d", learned), style = TpType.bigCounterLg, color = palette.fg)
                Text(
                    "new threat patterns learned this month — your phone gets smarter without you doing anything.",
                    style = TpType.body.copy(fontSize = 15.sp),
                    color = palette.muted,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DemoData.brainStats.forEach { st -> StatTile(st.value, st.label, Modifier.weight(1f)) }
            }

            SectionHeading("The learning loop")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoData.learnLoop.forEachIndexed { i, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card2)
                            .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(palette.accentTint15)
                                .border(BorderStroke(1.dp, palette.lineHover), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.accent)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(step.step, style = TpType.cardTitleBold.copy(fontSize = 17.sp), color = palette.fg)
                            Text(step.body, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                        }
                    }
                }
            }

            SectionHeading("What it learns from")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoData.brainSources.forEach { source ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BlinkingDot(8.dp, palette.accent, durationMs = 2200)
                            Text(source.name, style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg, modifier = Modifier.weight(1f))
                        }
                        Text(source.detail, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                        Text(source.metric, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted2)
                    }
                }
            }

            SectionHeading("Why you can trust it")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoData.assurances.forEach { a ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.accentTint06)
                            .border(BorderStroke(1.dp, palette.lineHover), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(26.dp).clip(CircleShape).background(palette.accentTint15),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", color = palette.accent, style = TpType.cardTitle.copy(fontSize = 15.sp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(a.title, style = TpType.cardTitle.copy(fontSize = 16.5.sp), color = palette.fg)
                            Text(a.body, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                        }
                    }
                }
            }
        }

        BottomNavBar(active = NavTab.BRAIN, onHome = onGoHome, onQr = onGoQr, onBrain = {}, onSettings = onGoSettings)
    }
}
