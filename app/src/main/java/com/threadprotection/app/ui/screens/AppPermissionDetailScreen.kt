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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.threadprotection.app.data.AppPermission
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.scan.PermGrantState
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.AppIcon
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.ConicProgressRing
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * One app's full permission picture — icon, package name, install source, a real on-device
 * safety rating, and every granted permission with a plain-language name and description.
 *
 * "Revoke" and "Uninstall" are both honest about what a third-party app can and can't do on
 * Android: no app (this one included) can silently revoke another app's permission or delete it
 * — that's reserved for the device owner via the OS's own confirmation UI. So both buttons launch
 * the real system flow (ACTION_APPLICATION_DETAILS_SETTINGS / ACTION_DELETE) with the user's
 * explicit confirmation, rather than pretending to do it in-app.
 */
@Composable
fun AppPermissionDetailScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onOpenAppSettings: (packageName: String) -> Unit,
    onUninstall: (packageName: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val app = state.scanData.permApps.firstOrNull { it.packageName == state.selectedPermApp }

    // Re-read the real grant states from PackageManager every time this screen comes back to the
    // foreground — which is exactly what happens when the user returns from the system Settings
    // page one of the buttons below sent them to. Without this the screen would keep showing the
    // states captured before they made the change.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("App details", style = TpType.screenTitle, color = palette.fg)
            }

            if (app == null) {
                Text("This app is no longer installed.", style = TpType.body.copy(fontSize = 15.5.sp), color = palette.muted)
                return@Column
            }

            // ---- header: icon, name, package, install source ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIcon(app.packageName, size = 60.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.app, style = TpType.cardTitleBold.copy(fontSize = 19.sp), color = palette.fg)
                        Text(app.packageName, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted)
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Storefront, contentDescription = null, tint = palette.muted, modifier = Modifier.height(18.dp))
                    Column {
                        Text("Installed from", style = TpType.caption.copy(fontSize = 11.5.sp), color = palette.muted)
                        Text(app.installerLabel, style = TpType.cardTitle.copy(fontSize = 14.5.sp), color = palette.fg2)
                    }
                }
            }

            // ---- safety rating ----
            val ratingColor = safetyColor(palette, app.safetyScore)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConicProgressRing(
                    size = 128.dp,
                    inset = 10.dp,
                    progressFraction = app.safetyScore / 100f,
                    activeColor = ratingColor,
                    trackColor = palette.line,
                    innerBackground = palette.card,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${app.safetyScore}", style = TpType.bigCounter, color = ratingColor)
                        Text("SAFETY", style = TpType.caption.copy(fontSize = 10.5.sp, letterSpacing = 1.2.sp), color = palette.muted)
                    }
                }
                Text(safetyLabel(app.safetyScore), style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = ratingColor)
                Text(
                    safetyExplanation(app),
                    style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp),
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                )
            }

            // ---- actions ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedPillButton(
                    text = "Manage access",
                    onClick = { onOpenAppSettings(app.packageName) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedPillButton(
                    text = "Uninstall",
                    onClick = { onUninstall(app.packageName) },
                    modifier = Modifier.weight(1f),
                    borderColor = palette.dangerBorder35,
                    textColor = palette.danger,
                )
            }
            Text(
                "Android reserves revoking a permission or removing an app for the system itself — both buttons open the real confirmation screen rather than pretending to act in-app.",
                style = TpType.caption.copy(fontSize = 11.5.sp, lineHeight = 16.5.sp),
                color = palette.muted2,
            )

            // ---- permission list ----
            SectionHeading("Permissions (${app.perms.size})")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                app.perms.forEach { perm ->
                    PermissionDetailRow(perm, onChange = { onOpenAppSettings(app.packageName) })
                }
            }
        }
    }
}

/**
 * One permission, showing the state Android itself reports right now. There is deliberately no
 * in-app switch: Android does not let one app change another app's permissions, so a switch here
 * could only ever have recorded a local preference while the real permission stayed exactly as it
 * was — which is what this screen used to do. Instead each user-changeable permission gets a
 * "Change in Settings" action that opens the real system page, and the state below is re-read from
 * the OS when the user comes back.
 */
@Composable
private fun PermissionDetailRow(perm: AppPermission, onChange: () -> Unit) {
    val palette = LocalTpPalette.current
    val stateColor = when (perm.state) {
        PermGrantState.GRANTED -> if (perm.risk) palette.warn else palette.accent
        PermGrantState.ALWAYS_ON -> palette.muted
        PermGrantState.DENIED -> palette.accent
        PermGrantState.RESTRICTED -> palette.danger
        PermGrantState.NOT_REQUESTED, PermGrantState.UNKNOWN -> palette.muted2
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, if (perm.risk) palette.warnBorder20 else palette.line), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(perm.name, style = TpType.cardTitle.copy(fontSize = 15.5.sp), color = palette.fg)
                if (perm.risk) {
                    Text("Flagged as risky", style = TpType.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = palette.warn)
                }
            }
            Text(
                perm.state.label,
                style = TpType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = stateColor,
            )
        }
        Text(perm.description, style = TpType.caption.copy(fontSize = 13.sp, lineHeight = 19.sp), color = palette.fg2)
        Text(perm.why, style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp), color = palette.muted)
        when (perm.state) {
            PermGrantState.GRANTED, PermGrantState.DENIED -> OutlinedPillButton(
                text = if (perm.state == PermGrantState.GRANTED) "Turn off in Settings" else "Turn on in Settings",
                onClick = onChange,
                borderColor = palette.line3,
            )
            PermGrantState.ALWAYS_ON -> Text(
                "Granted at install time. Android doesn't allow this one to be turned off — not by you, not by Settings, not by this app.",
                style = TpType.caption.copy(fontSize = 11.5.sp, lineHeight = 16.5.sp),
                color = palette.muted2,
            )
            PermGrantState.RESTRICTED -> Text(
                "Blocked by a device or work-profile policy. Only whoever manages this device can change it.",
                style = TpType.caption.copy(fontSize = 11.5.sp, lineHeight = 16.5.sp),
                color = palette.muted2,
            )
            PermGrantState.NOT_REQUESTED, PermGrantState.UNKNOWN -> Unit
        }
    }
}

private fun safetyColor(palette: TpPalette, score: Int): Color = when {
    score >= 80 -> palette.accent
    score >= 50 -> palette.warn
    else -> palette.danger
}

private fun safetyLabel(score: Int): String = when {
    score >= 90 -> "Excellent"
    score >= 80 -> "Good"
    score >= 60 -> "Fair"
    score >= 40 -> "Poor"
    else -> "Critical"
}

private fun safetyExplanation(app: PermApp): String {
    val riskyCount = app.perms.count { it.risk }
    val riskyPart = if (riskyCount == 0) "no permissions that look unnecessary" else "$riskyCount permission${if (riskyCount == 1) "" else "s"} that look unnecessary"
    return "Based on $riskyPart out of ${app.perms.size} granted, and its install source (${app.installerLabel})."
}
