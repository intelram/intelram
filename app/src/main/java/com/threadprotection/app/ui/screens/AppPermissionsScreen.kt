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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Matches on both the display name and the package id, so "whatsapp" and "com.whatsapp" both
    // find the same app.
    val visibleApps = remember(state.scanData.permApps, query) {
        val q = query.trim()
        if (q.isBlank()) {
            state.scanData.permApps
        } else {
            state.scanData.permApps.filter {
                it.app.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryPillButton(
                        text = "Turn all them off",
                        onClick = onOpenPermissionManager,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.card2)
                            .border(BorderStroke(1.dp, palette.line3), RoundedCornerShape(14.dp))
                            .clickable { searchOpen = !searchOpen },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search apps",
                            tint = palette.fg2,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Text(
                    "Opens Android's permission manager, where you can switch permissions off across every app. Android reserves the actual change for the system — no app can revoke another app's permissions on its own.",
                    style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = palette.muted,
                )
            }

            if (searchOpen) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search apps by name or package", color = palette.muted3, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = palette.muted) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = palette.muted,
                                modifier = Modifier.clickable { query = "" },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.card2,
                        unfocusedContainerColor = palette.card2,
                        focusedTextColor = palette.fg,
                        unfocusedTextColor = palette.fg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = palette.accent,
                    ),
                )
            }

            if (state.scanData.permApps.isEmpty()) {
                Text(
                    "Reading installed apps and their permissions…",
                    style = TpType.body.copy(fontSize = 15.5.sp),
                    color = palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            } else if (visibleApps.isEmpty()) {
                Text(
                    "No installed app matches \"${query.trim()}\".",
                    style = TpType.body.copy(fontSize = 15.sp),
                    color = palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
            } else if (query.isNotBlank()) {
                Text(
                    "${visibleApps.size} of ${state.scanData.permApps.size} apps match",
                    style = TpType.caption.copy(fontSize = 12.sp),
                    color = palette.muted2,
                )
            }
            visibleApps.forEach { app -> AppOverviewRow(app, onClick = { onOpenDetail(app.packageName) }) }
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
