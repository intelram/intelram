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
 * The in-app Accept/Deny prompt for an incoming Bluetooth chat request.
 *
 * Rendered above every screen rather than inside the Chat screen, because a request can arrive at
 * any moment regardless of where the user happens to be. It is intentionally modal and not
 * dismissible by tapping outside: silently dismissing it would leave the sender waiting on a
 * request the recipient has no way to get back to, until it times out.
 */
@Composable
fun IncomingChatRequestOverlay(
    displayName: String,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
) {
    val palette = LocalTpPalette.current
    AlertDialog(
        // Answering is the only way out — see the note above.
        onDismissRequest = {},
        title = {
            Text("$displayName wants to chat", style = TpType.cardTitle.copy(fontSize = 18.sp), color = palette.fg)
        },
        text = {
            Text(
                "They're nearby and asking to start an encrypted Bluetooth chat with you. " +
                    "Nothing is connected until you accept.",
                style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                color = palette.muted,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedPillButton(
                    text = "Deny",
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    borderColor = palette.dangerBorder35,
                    textColor = palette.danger,
                )
                PrimaryPillButton(text = "Accept", onClick = onAccept, modifier = Modifier.weight(1f))
            }
        },
        containerColor = palette.card,
    )
}
