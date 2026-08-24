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
 * from `PackageManager` on this phone, not demo data. README: "On Android, revoking another
 * app's permission is not possible programmatically — the switch must deep-link to that app's
 * system permission page." Toggling here flips the local "turned off by you" state shown in the
 * UI; a full production build would additionally resolve the real package name per app and
 * launch `ACTION_APPLICATION_DETAILS_SETTINGS` for it, then read the grant back on resume.
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
