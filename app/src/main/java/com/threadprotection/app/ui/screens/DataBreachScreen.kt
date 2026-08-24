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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * Real, free, keyless breach lookup via XposedOrNot (README §Data breach security). Have I Been
 * Pwned's equivalent stopped being free in 2024, so this uses a different — genuinely live —
 * source instead of faking one.
 */
@Composable
fun DataBreachScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val result = state.breachResult

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Data breach security", style = TpType.screenTitle, color = palette.fg)
            }

            if (state.account == null) {
                Text(
                    "Sign in first — this checks the email address you signed in with against known public breaches.",
                    style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                    color = palette.muted,
                )
                return@Column
            }

            Text(
                "Checking ${state.account.email} against live breach records.",
                style = TpType.body.copy(fontSize = 15.sp, lineHeight = 23.sp),
                color = palette.muted,
            )

            if (state.breachChecking) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            } else if (result != null && result.checked) {
                val safe = result.breaches.isEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (safe) palette.accentTint08 else palette.dangerTint08)
                        .border(BorderStroke(1.dp, if (safe) palette.accentBorder25 else palette.dangerBorder30), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (safe) "Not found in any known breach" else "Found in ${result.breaches.size} known breach${if (result.breaches.size == 1) "" else "es"}",
                        style = TpType.cardTitleBold.copy(fontSize = 18.sp),
                        color = if (safe) palette.accent else palette.danger,
                    )
                    Text(
                        if (safe) "Nothing to do right now — we'll keep this current each time you check." else "Change the password anywhere you reused it, starting with your email and bank. Turn on two-step verification where you can.",
                        style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
                        color = palette.fg2,
                    )
                }

                if (result.breaches.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp)),
                    ) {
                        result.breaches.forEachIndexed { i, b ->
                            Column {
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(modifier = Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(palette.warn2))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(b.name, style = TpType.cardTitle.copy(fontSize = 16.sp), color = palette.fg)
                                        if (b.dataExposed.isNotBlank()) {
                                            Text(b.dataExposed, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 18.5.sp), color = palette.muted)
                                        }
                                    }
                                    if (b.date.isNotBlank()) {
                                        Text(b.date, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted2)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (result?.error != null) {
                Text(result.error, style = TpType.caption.copy(fontSize = 14.sp), color = palette.warn)
            }

            PrimaryPillButton(text = if (result == null) "Check now" else "Check again", onClick = onCheck, enabled = !state.breachChecking)
            Text(
                "Source: XposedOrNot's free public breach database — the same category of source as Have I Been Pwned, which now requires a paid key.",
                style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = palette.muted2,
            )
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}
