package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.chat.ChatSessionStatus
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.OutlinedPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A saved conversation, read back from disk. Everything shown here is a real transcript of a real
 * Bluetooth session — who it was with, when it started and ended, how it ended, and every message
 * in the order it was actually sent or received.
 */
@Composable
fun ChatSessionScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onDelete: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val session = state.viewingSession
    var confirmDelete by remember { mutableStateOf(false) }

    if (session == null) {
        Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Text("Conversation", style = TpType.screenTitle, color = palette.fg)
            }
            Text("This conversation is no longer saved.", style = TpType.body.copy(fontSize = 15.5.sp), color = palette.muted)
        }
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg) },
            text = {
                Text(
                    "The full transcript with ${session.name} will be permanently removed from this device. This can't be undone.",
                    style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                    color = palette.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(session.sessionId); onBack() }) {
                    Text("Delete", color = palette.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = palette.fg2) } },
            containerColor = palette.card,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircleButton(onClick = onBack)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(session.name, style = TpType.cardTitleBold.copy(fontSize = 18.sp), color = palette.fg)
                    Text(session.address, style = TpType.caption.copy(fontSize = 11.5.sp), color = palette.muted2)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.card)
                    .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailLine("Started", FULL_FORMAT.format(Date(session.startedAtMs)))
                session.endedAtMs?.let { DetailLine("Ended", FULL_FORMAT.format(Date(it))) }
                DetailLine("Status", session.status.label, valueColor = statusColor(session.status, palette.accent, palette.warn))
                DetailLine("Messages", "${session.sentCount} sent · ${session.receivedCount} received")
            }
            OutlinedPillButton(
                text = "Delete conversation",
                onClick = { confirmDelete = true },
                borderColor = palette.dangerBorder35,
                textColor = palette.danger,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(session.messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val palette = LocalTpPalette.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted)
        Text(
            value,
            style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            color = valueColor ?: palette.fg2,
        )
    }
}

private fun statusColor(
    status: ChatSessionStatus,
    accent: androidx.compose.ui.graphics.Color,
    warn: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.graphics.Color = when (status) {
    ChatSessionStatus.COMPLETED -> accent
    ChatSessionStatus.INTERRUPTED -> warn
    ChatSessionStatus.ACTIVE -> accent
}

private val FULL_FORMAT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
