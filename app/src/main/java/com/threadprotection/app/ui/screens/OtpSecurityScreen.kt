package com.threadprotection.app.ui.screens

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
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

private val SMS_PERM_IDS = setOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS")

/**
 * One-time codes sent by SMS are only as safe as the apps that can read your messages. Android
 * gives no third-party app a way to intercept or protect another app's SMS access directly — the
 * honest, real thing this screen can do is show exactly which installed apps hold SMS
 * permissions right now (from the same `PermissionAudit` as App Permissions), since that's the
 * actual attack surface for OTP theft.
 */
@Composable
fun OtpSecurityScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenAppSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val smsApps = state.scanData.permApps.filter { app -> app.perms.any { it.id in SMS_PERM_IDS } }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("OTP security", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "One-time codes sent by text are only as safe as the apps that can read your messages. Here's every app on this phone that currently can.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (smsApps.isEmpty()) palette.accentTint08 else palette.warnTint06)
                    .border(BorderStroke(1.dp, if (smsApps.isEmpty()) palette.accentBorder25 else palette.warnBorder20), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (smsApps.isEmpty()) "No apps can currently read your text messages" else "${smsApps.size} app${if (smsApps.size == 1) "" else "s"} can read your text messages",
                    style = TpType.cardTitle.copy(fontSize = 16.5.sp),
                    color = palette.fg,
                )
                Text(
                    "A code-stealing app doesn't need to be obvious — it only needs SMS access and a reason to run in the background.",
                    style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                    color = palette.muted,
                )
            }

            if (smsApps.isEmpty() && state.scanData.permApps.isEmpty()) {
                Text(
                    "Reading installed apps and their permissions…",
                    style = TpType.body.copy(fontSize = 15.sp),
                    color = palette.muted,
                )
            }

            smsApps.forEach { app ->
                val smsPerms = app.perms.filter { it.id in SMS_PERM_IDS }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.card)
                        .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(palette.warn))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.app, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
                        Text(smsPerms.joinToString(" · ") { it.name }, style = TpType.caption.copy(fontSize = 13.sp), color = palette.muted)
                    }
                    Text(
                        "Review",
                        style = TpType.caption.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = { onOpenAppSettings(app.packageName) })
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                "For accounts that support it, switch from SMS codes to an authenticator app — those codes never pass through your text messages at all.",
                style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                color = palette.muted2,
            )
        }
        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}
