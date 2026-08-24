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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.PulsingRings
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxSize().padding(top = 28.dp, start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                PulsingRings(size = 96.dp, color = palette.accent)
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(palette.accentTint14)
                        .border(BorderStroke(1.dp, palette.accentBorder40), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = palette.accent, style = TpType.screenTitleLg.copy(fontSize = 46.sp))
                }
            }
            Column(
                modifier = Modifier.padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Thread Protection", style = TpType.signinAppName.copy(fontSize = 35.sp), color = palette.fg, textAlign = TextAlign.Center)
                Text(
                    "We watch your phone for anything harmful and tell you in plain words what to do about it.",
                    style = TpType.body.copy(fontSize = 18.5.sp),
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DemoData.obFeatures.forEach { feature ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.card)
                            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(palette.accent))
                        Text(feature, style = TpType.body.copy(fontSize = 16.5.sp), color = palette.fg2)
                    }
                }
            }
        }
        PrimaryPillButton(text = "Get started", onClick = onGetStarted)
    }
}
