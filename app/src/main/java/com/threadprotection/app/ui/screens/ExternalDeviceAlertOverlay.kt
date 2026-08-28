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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.hardware.DeviceRiskAssessor
import com.threadprotection.app.hardware.DeviceRiskLevel
import com.threadprotection.app.hardware.DeviceTransport
import com.threadprotection.app.hardware.DeviceTrust
import com.threadprotection.app.hardware.ExternalDevice
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.components.blinkAlpha
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * The alert shown when a real external device connects over USB or Bluetooth.
 *
 * Deliberately honest about what "Block" achieves. Android gives no app the ability to sever a USB
 * or Bluetooth connection — by the time this alert appears the connection already exists — so the
 * overlay records the user's decision and tells them the one thing that actually stops the device:
 * unplugging it, or unpairing it in Settings. Telling someone a malicious cable has been
 * neutralised when it is still plugged in would be worse than saying nothing.
 */
@Composable
fun ExternalDeviceAlertOverlay(
    device: ExternalDevice,
    decision: DeviceTrust,
    onBlock: () -> Unit,
    onAllowOnce: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: (DeviceTransport) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val risk = DeviceRiskAssessor.assess(device)
    val color = when (risk.level) {
        DeviceRiskLevel.HIGH -> palette.danger
        DeviceRiskLevel.MEDIUM -> palette.warn
        DeviceRiskLevel.LOW -> palette.accent
    }
    val tint = when (risk.level) {
        DeviceRiskLevel.HIGH -> palette.dangerTint12
        DeviceRiskLevel.MEDIUM -> palette.warnTint12
        DeviceRiskLevel.LOW -> palette.accentTint08
    }
    val heading = when (risk.level) {
        DeviceRiskLevel.HIGH -> "HIGH-RISK DEVICE CONNECTED"
        DeviceRiskLevel.MEDIUM -> "UNKNOWN DEVICE CONNECTED"
        DeviceRiskLevel.LOW -> "NEW DEVICE CONNECTED"
    }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Box(
        modifier = modifier.fillMaxSize().background(palette.alertScrim).padding(18.dp),
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
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(tint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "!",
                            style = TpType.cardTitleBold.copy(fontSize = 28.sp),
                            color = color.copy(alpha = if (risk.level == DeviceRiskLevel.HIGH) blinkAlpha(1400) else 1f),
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(heading, style = TpType.badge.copy(fontSize = 12.5.sp), color = color)
                        Text(
                            device.displayName,
                            style = TpType.cardTitleBold.copy(fontSize = 20.sp, lineHeight = 25.sp),
                            color = palette.fg,
                        )
                    }
                }

                // Everything here came from the OS. Anything the device didn't report is shown as
                // "not reported" rather than guessed at.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.card)
                        .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DetailRow("Connection", device.transportLabel)
                    DetailRow("Reports itself as", device.kindLabel)
                    DetailRow("Name", device.name ?: "Not reported by the device")
                    device.manufacturer?.let { DetailRow("Maker", it) }
                    DetailRow(
                        if (device.transport == DeviceTransport.USB) "Vendor / product ID" else "Bluetooth address",
                        device.hardwareId,
                    )
                    device.serial?.let { DetailRow("Serial", it) }
                    if (device.transport == DeviceTransport.BLUETOOTH) {
                        DetailRow("Paired before", if (device.bonded) "Yes" else "No")
                    }
                }

                Text(risk.why, style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 23.5.sp), color = palette.fg2)

                if (decision == DeviceTrust.UNKNOWN) {
                    Text(risk.advice, style = TpType.caption.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.muted)
                    PrimaryPillButton(text = "Block this device", onClick = onBlock)
                    OutlinedPillButton(
                        text = "Allow once — I know this device",
                        onClick = onAllowOnce,
                        borderColor = palette.line3,
                    )
                    Text(
                        "\"Allow once\" lasts for this session only. Next time it connects, you'll be asked again.",
                        style = TpType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = palette.muted2,
                    )
                } else {
                    OutcomePanel(device = device, decision = decision, onOpenSettings = onOpenSettings)
                    PrimaryPillButton(text = "Done", onClick = onDone)
                }
            }
        }
    }
}

/**
 * What actually happened after the user chose — including, for Block, the part most security apps
 * get wrong. Android does not let an app cut a USB or Bluetooth connection, so this says what was
 * recorded and what the user has to do themselves, rather than claiming the device is neutralised.
 */
@Composable
private fun OutcomePanel(device: ExternalDevice, decision: DeviceTrust, onOpenSettings: (DeviceTransport) -> Unit) {
    val palette = LocalTpPalette.current
    val blocked = decision == DeviceTrust.BLOCKED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (blocked) palette.dangerTint08 else palette.card)
            .border(
                BorderStroke(1.dp, if (blocked) palette.dangerBorder30 else palette.line),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            if (blocked) "Marked as blocked" else "Allowed for this session",
            style = TpType.cardTitle.copy(fontSize = 16.sp),
            color = if (blocked) palette.danger else palette.fg,
        )
        if (blocked) {
            Text(
                "Thread Protection will never treat this device as trusted, and it stays flagged in your " +
                    "connected devices list.",
                style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.5.sp),
                color = palette.fg2,
            )
            // The honest part. Stated plainly, not buried.
            Text(
                "It is still connected. Android does not let any app cut a " +
                    "${device.transportLabel} connection — only you can:",
                style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.5.sp, fontWeight = FontWeight.SemiBold),
                color = palette.fg,
            )
            Text(
                when (device.transport) {
                    DeviceTransport.USB ->
                        "Unplug it now, and don't reuse that cable, dock or charger. If it came with a " +
                            "public charging point, treat the cable as untrusted."
                    DeviceTransport.BLUETOOTH ->
                        "Open Bluetooth settings and unpair \"${device.displayName}\", or turn Bluetooth " +
                            "off until you're somewhere you trust."
                },
                style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.5.sp),
                color = palette.fg2,
            )
            if (device.transport == DeviceTransport.BLUETOOTH) {
                OutlinedPillButton(
                    text = "Open Bluetooth settings",
                    onClick = { onOpenSettings(DeviceTransport.BLUETOOTH) },
                    borderColor = palette.line3,
                )
            }
        } else {
            Text(
                "Allowed until you close the app. This app hasn't granted it any extra access — it " +
                    "simply won't warn you about this one again this session.",
                style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.5.sp),
                color = palette.fg2,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalTpPalette.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = TpType.caption.copy(fontSize = 13.sp),
            color = palette.muted,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = TpType.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = palette.fg2,
            modifier = Modifier.weight(0.58f),
        )
    }
}
