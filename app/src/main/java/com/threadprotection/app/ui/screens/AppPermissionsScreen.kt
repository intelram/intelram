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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.state.Derived
import com.threadprotection.app.ui.components.AppIcon
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.BottomNavBar
import com.threadprotection.app.ui.components.NavTab
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * Real, on-device audit from `PermissionAudit` — every app, permission and safety score here
 * comes from `PackageManager` on this phone, not demo data. This screen is the overview: tap an
 * app to open AppPermissionDetailScreen for the full icon/package/installer/permission-by-
 * permission breakdown.
 */
@Composable
fun AppPermissionsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onGoQr: () -> Unit,
    onGoChat: () -> Unit,
    onGoBrain: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenPermissionManager: () -> Unit,
    onOpenDetail: (packageName: String) -> Unit,
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
                "This is what every app and background service is allowed to do. Tap an app to see the full picture and manage it.",
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
                Text(
                    "Android has no way for one app to switch off another app's permissions in bulk — that stays with you and the system. This opens Android's own Permission manager, where you can review every app by permission type.",
                    style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = palette.muted,
                )
                PrimaryPillButton(text = "Open Android's permission manager", onClick = onOpenPermissionManager)
            }

            if (state.scanData.permApps.isEmpty()) {
                Text(
                    "Reading installed apps and their permissions…",
                    style = TpType.body.copy(fontSize = 15.5.sp),
                    color = palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
            state.scanData.permApps.forEach { app -> AppOverviewRow(app, onClick = { onOpenDetail(app.packageName) }) }
        }

        BottomNavBar(active = NavTab.HOME, onHome = onBack, onQr = onGoQr, onChat = onGoChat, onBrain = onGoBrain, onSettings = onGoSettings)
    }
}

@Composable
private fun AppOverviewRow(app: PermApp, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    val riskyCount = app.perms.count { it.risk }
    val ratingColor = when {
        app.safetyScore >= 80 -> palette.accent
        app.safetyScore >= 50 -> palette.warn
        else -> palette.danger
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppIcon(app.packageName, size = 44.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.app, style = TpType.cardTitleBold.copy(fontSize = 16.5.sp), color = palette.fg)
            Text(app.kind, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ratingColor.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "${app.safetyScore}",
                    style = TpType.badgeSmall,
                    color = ratingColor,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                if (riskyCount > 0) "$riskyCount risky" else "All clear",
                style = TpType.caption.copy(fontSize = 11.sp),
                color = palette.muted,
            )
        }
    }
}
