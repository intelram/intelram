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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import com.threadprotection.app.state.Derived
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.ToggleSwitch
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * Real, on-device audit from `PermissionAudit` — every app, permission and "why" line here comes
 * from `PackageManager` on this phone, not demo data.
 *
 * On Android, no third-party app — including this one — can revoke another app's permission
 * directly; that capability is reserved for the device owner (MDM) or the OS itself. The closest
 * real "take over and change it" action is a one-tap deep-link straight into *that exact app's*
 * system permission screen (`ACTION_APPLICATION_DETAILS_SETTINGS`), skipping the hunt through
 * Settings → Apps. The switch below still flips a local "turned off by you" flag for the
 * at-a-glance summary, but `onOpenAppSettings` is what actually changes anything.
 */
@Composable
fun AppPermissionsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onTogglePermission: (app: String, permId: String) -> Unit,
    onTurnOffAllRisky: () -> Unit,
    onOpenAppSettings: (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("App permissions", style = TpType.screenTitle, color = palette.fg)
            }
            Text(
                "This is what every app and background service is allowed to do. Tap a switch to turn anything off — you can turn it back on any time.",
                style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 24.5.sp),
                color = palette.muted,
            )

            val riskyTotal = Derived.riskyPermTotal(state)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("$riskyTotal permissions look unnecessary", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg)
                PrimaryPillButton(text = "Turn all of them off", onClick = onTurnOffAllRisky)
            }

            if (state.scanData.permApps.isEmpty()) {
                Text(
                    "Reading installed apps and their permissions…",
                    style = TpType.body.copy(fontSize = 15.5.sp),
                    color = palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
            state.scanData.permApps.forEach { app ->
                val riskyCount = app.perms.count { it.risk && "${app.app}|${it.id}" !in state.permOff }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.card)
                        .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(app.app, style = TpType.cardTitleBold.copy(fontSize = 18.sp), color = palette.fg)
                            Text(app.kind, style = TpType.caption.copy(fontSize = 13.5.sp), color = palette.muted)
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(max = 110.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (riskyCount > 0) palette.warnTint14 else palette.accentTint14)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                if (riskyCount > 0) "$riskyCount risky permission${if (riskyCount > 1) "s" else ""}" else "Nothing unusual",
                                style = TpType.badgeSmall,
                                color = if (riskyCount > 0) palette.warn else palette.accent,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 15.6.sp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAppSettings(app.packageName) }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Change in system settings",
                            style = TpType.caption.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = palette.accent,
                            modifier = Modifier.weight(1f),
                        )
                        Text("›", color = palette.accent, fontSize = 16.sp)
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
                    app.perms.forEachIndexed { i, perm ->
                        val key = "${app.app}|${perm.id}"
                        val on = key !in state.permOff
                        Column {
                            if (i > 0) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(perm.name, style = TpType.cardTitle.copy(fontSize = 16.sp, lineHeight = 21.6.sp), color = palette.fg)
                                    Text(
                                        if (!on) "Turned off by you" else perm.why,
                                        style = TpType.caption.copy(fontSize = 13.5.sp, lineHeight = 18.9.sp),
                                        color = if (!on) palette.accent else if (perm.risk) palette.warn else palette.muted,
                                    )
                                }
                                ToggleSwitch(checked = on, onCheckedChange = { onTogglePermission(app.app, perm.id) })
                            }
                        }
                    }
                }
            }
        }

        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}
