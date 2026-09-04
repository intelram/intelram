package com.threadprotection.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/**
 * Full-screen Answer/Decline prompt for an incoming Bluetooth voice call.
 *
 * Mirrors [IncomingChatRequestOverlay] for the same reason: a call can start while the user is
 * anywhere in the app (they don't have to already be sitting in this conversation), so it is
 * rendered above every screen rather than inside ChatConversationScreen, and it is not dismissible
 * by tapping outside — silently swallowing it would leave the caller ringing with no answer until
 * the far side's own ring timeout gives up.
 */
@Composable
fun IncomingCallOverlay(
    displayName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val palette = LocalTpPalette.current
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text("📞 $displayName is calling", style = TpType.cardTitle.copy(fontSize = 18.sp), color = palette.fg)
        },
        text = {
            Text(
                "Incoming voice call over your encrypted Bluetooth chat connection.",
                style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                color = palette.muted,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedPillButton(
                    text = "Decline",
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                    borderColor = palette.dangerBorder35,
                    textColor = palette.danger,
                )
                PrimaryPillButton(text = "Answer", onClick = onAccept, modifier = Modifier.weight(1f))
            }
        },
        containerColor = palette.card,
    )
}
