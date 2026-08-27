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
import com.threadprotection.app.chat.ChatHistoryEntry
import com.threadprotection.app.chat.ChatSession
import com.threadprotection.app.chat.ChatSessionStatus
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.SectionHeading
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Full list of devices you've chatted with before — README's "history, compressed on the Chat
 *  screen, opens here to select someone and start chatting" without rediscovering them. */
@Composable
fun ChatHistoryScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSelect: (ChatHistoryEntry) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onClearSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all conversations?", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg) },
            text = {
                Text(
                    "Every saved transcript will be permanently removed from this device. Your list of devices stays. This can't be undone.",
                    style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                    color = palette.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearSessions() }) { Text("Delete all", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = palette.fg2) } },
            containerColor = palette.card,
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackCircleButton(onClick = onBack)
            Text("Chat history", style = TpType.screenTitle, color = palette.fg)
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Saved transcripts first — these are the actual conversations, restored from disk.
            if (state.chatSessions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionHeading("Saved conversations")
                    Text(
                        "Delete all",
                        style = TpType.caption.copy(fontSize = 12.5.sp),
                        color = palette.danger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { confirmClear = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                state.chatSessions.forEach { session ->
                    SessionRow(session, onClick = { onOpenSession(session.sessionId) })
                }
            }

            SectionHeading("Devices you've chatted with")
            if (state.chatHistory.isEmpty()) {
                Text(
                    "No conversations yet. Devices you successfully chat with show up here so you can reach them again without scanning.",
                    style = TpType.body.copy(fontSize = 15.sp, lineHeight = 23.sp),
                    color = palette.muted,
                )
            } else {
                state.chatHistory.forEach { entry -> HistoryRow(entry, onClick = { onSelect(entry) }) }
            }
        }
    }
}

/** One saved transcript. Tapping it opens the conversation read-only, not a new connection. */
@Composable
private fun SessionRow(session: ChatSession, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    val statusColor = when (session.status) {
        ChatSessionStatus.INTERRUPTED -> palette.warn
        else -> palette.accent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.name, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
                Text(
                    SESSION_FORMAT.format(Date(session.startedAtMs)),
                    style = TpType.caption.copy(fontSize = 12.sp),
                    color = palette.muted,
                )
            }
            Text(
                "${session.messages.size} msg",
                style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = palette.muted,
            )
        }
        Text(
            "${session.sentCount} sent · ${session.receivedCount} received · ${session.status.label}",
            style = TpType.caption.copy(fontSize = 11.5.sp),
            color = statusColor,
        )
    }
}

@Composable
private fun HistoryRow(entry: ChatHistoryEntry, onClick: () -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(palette.accentTint16),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.name.take(1).uppercase(), style = TpType.cardTitleBold.copy(fontSize = 15.sp), color = palette.accent)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = TpType.cardTitleBold.copy(fontSize = 16.sp), color = palette.fg)
            Text(
                relativeTime(entry.lastChattedAtMs) + if (entry.meshReachable) " · reachable via relay" else " · direct range only",
                style = TpType.caption.copy(fontSize = 12.5.sp),
                color = palette.muted,
            )
        }
        Text("Message", style = TpType.caption.copy(fontSize = 13.sp), color = palette.accent)
    }
}

private val SESSION_FORMAT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)

private fun relativeTime(atMs: Long): String {
    val diffMs = (System.currentTimeMillis() - atMs).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
    val days = TimeUnit.MILLISECONDS.toDays(diffMs)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days < 7 -> "$days d ago"
        else -> "${days / 7} w ago"
    }
}
