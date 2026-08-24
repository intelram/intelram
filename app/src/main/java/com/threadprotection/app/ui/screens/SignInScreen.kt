package com.threadprotection.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.threadprotection.app.R
import com.threadprotection.app.auth.GoogleAuthClient
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.ui.components.OrbitDots
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PulsingRings
import com.threadprotection.app.ui.components.WhiteGoogleButton
import com.threadprotection.app.ui.components.blinkAlpha
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(
    blockedCount: Long,
    tickerText: String,
    gsiError: String?,
    onSignedIn: (com.threadprotection.app.data.Account) -> Unit,
    onCreateAccount: () -> Unit,
    onGsiError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 30.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SignInHero()

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Thread Protection", style = TpType.signinAppName, color = palette.fg, textAlign = TextAlign.Center)
                Text(
                    "Keeps your phone safe. Sign in once, and we do the rest.",
                    style = TpType.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = palette.fg2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }

            LiveCounterCard(blockedCount, tickerText)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DemoData.stats.forEach { st ->
                    com.threadprotection.app.ui.components.StatTile(st.value, st.label, modifier = Modifier.weight(1f))
                }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoData.trust.forEach { tp -> TrustRow(tp.title, tp.body) }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WhiteGoogleButton(
                text = "Sign in with Google",
                onClick = {
                    if (GoogleAuthClient.isConfigured) {
                        scope.launch {
                            GoogleAuthClient.signIn(context)
                                .onSuccess { onSignedIn(it) }
                                .onFailure { onGsiError(it.message) }
                        }
                    } else {
                        onSignedIn(DemoData.demoAccount)
                    }
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_google_logo), contentDescription = null, modifier = Modifier.size(26.dp))
                    Text("Sign in with Google", style = TpType.primaryButtonLg, color = androidx.compose.ui.graphics.Color(0xFF1A1D1B))
                }
            }

            if (!gsiError.isNullOrBlank()) {
                Text(gsiError, style = TpType.caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(14.5f, androidx.compose.ui.unit.TextUnitType.Sp)), color = palette.warn, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            OutlinedPillButton(text = "Create an account", onClick = onCreateAccount, borderColor = palette.line4)

            Text(
                text = if (GoogleAuthClient.isConfigured) {
                    "We only ask for your name and email — nothing else. You can sign out any time."
                } else {
                    "Demo mode — sign-in uses a sample account until a Google client ID is configured."
                },
                style = TpType.caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = palette.muted2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SignInHero() {
    val palette = LocalTpPalette.current
    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        OrbitDots(
            containerSize = 150.dp,
            colors = Triple(palette.accent, palette.accentSoft, palette.warn),
        )
        Box(modifier = Modifier.padding(18.dp)) {
            PulsingRings(size = 114.dp, color = palette.accent)
        }
        Box(
            modifier = Modifier
                .size(106.dp)
                .clip(CircleShape)
                .background(palette.accentTint14)
                .border(BorderStroke(1.5.dp, palette.accentBorder45), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = palette.accent, style = TpType.screenTitleLg.copy(fontSize = androidx.compose.ui.unit.TextUnit(46f, androidx.compose.ui.unit.TextUnitType.Sp)))
        }
    }
}

@Composable
private fun LiveCounterCard(blockedCount: Long, tickerText: String) {
    val palette = LocalTpPalette.current
    val dotAlpha = blinkAlpha()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(palette.accent.copy(alpha = dotAlpha)))
            Text(
                "PROTECTING RIGHT NOW",
                style = TpType.badge.copy(fontSize = androidx.compose.ui.unit.TextUnit(13.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = palette.accent,
            )
        }
        Text(String.format("%,d", blockedCount), style = TpType.bigCounter, color = palette.fg)
        Text("threats blocked for people like you", style = TpType.caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(14.5f, androidx.compose.ui.unit.TextUnitType.Sp)), color = palette.muted)
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(palette.warn))
            Text(tickerText, style = TpType.caption, color = palette.fg2)
        }
    }
}

@Composable
private fun TrustRow(title: String, body: String) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card2)
            .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(palette.accentTint15),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = palette.accent, style = TpType.cardTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = TpType.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = palette.fg)
            Text(body, style = TpType.caption, color = palette.muted)
        }
    }
}
