package com.threadprotection.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.HwSim
import com.threadprotection.app.data.HwVerdict
import com.threadprotection.app.state.HwHandled
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.blinkAlpha
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

@Composable
fun HardwareAlertOverlay(
    alert: HwSim,
    handled: HwHandled?,
    onBlock: () -> Unit,
    onAllow: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val danger = alert.verdict == HwVerdict.DANGER
    val color = if (danger) palette.danger else palette.warn
    val bg = if (danger) palette.dangerTint12 else palette.warnTint12
    val label = if (danger) "DANGEROUS DEVICE" else "SUSPICIOUS DEVICE"

    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.alertScrim)
            .padding(18.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInVertically(animationSpec = tween(220)) { it / 10 } + fadeIn(animationSpec = tween(220)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.bg)
                    .border(BorderStroke(1.5.dp, color), RoundedCornerShape(24.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val alpha = blinkAlpha(1400)
                    Box(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("!", style = TpType.cardTitleBold.copy(fontSize = 28.sp), color = color.copy(alpha = alpha))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = TpType.badge.copy(fontSize = 12.5.sp), color = color)
                        Text(alert.name, style = TpType.cardTitleBold.copy(fontSize = 20.sp, lineHeight = 25.sp), color = palette.fg)
                        Text("${alert.kind} · risk ${alert.risk}/100", style = TpType.caption, color = palette.muted)
                    }
                }
                Text(alert.why, style = TpType.body.copy(fontSize = 16.sp, lineHeight = 24.8.sp), color = palette.fg2)

                if (handled == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text(alert.advice, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.muted)
                        PrimaryPillButton(text = "Block this device", onClick = onBlock)
                        com.threadprotection.app.ui.components.OutlinedPillButton(
                            text = "Allow once — I know this device",
                            onClick = onAllow,
                            borderColor = palette.line3,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.card)
                                .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(26.dp).clip(CircleShape).background(palette.accentTint15),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✓", color = palette.accent, style = TpType.cardTitle.copy(fontSize = 15.sp))
                            }
                            Text(
                                if (handled == HwHandled.BLOCK) {
                                    "Blocked. This device cannot type, read or install anything on your phone."
                                } else {
                                    "Allowed once. We are watching it closely and will warn you again if it acts up."
                                },
                                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 23.25.sp),
                                color = palette.fg2,
                            )
                        }
                        PrimaryPillButton(text = "Done", onClick = onDone)
                    }
                }
            }
        }
    }
}
