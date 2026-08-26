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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.chat.ChatHistoryEntry
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import java.util.concurrent.TimeUnit

/** Full list of devices you've chatted with before — README's "history, compressed on the Chat
 *  screen, opens here to select someone and start chatting" without rediscovering them. */
@Composable
fun ChatHistoryScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackCircleButton(onClick = onBack)
            Text("Chat history", style = TpType.screenTitle, color = palette.fg)
        }

        if (state.chatHistory.isEmpty()) {
            Text(
                "No conversations yet. Devices you successfully chat with show up here so you can reach them again without scanning.",
                style = TpType.body.copy(fontSize = 15.sp, lineHeight = 23.sp),
                color = palette.muted,
            )
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.chatHistory.forEach { entry -> HistoryRow(entry, onClick = { onSelect(entry.address) }) }
            }
        }
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
            Text(relativeTime(entry.lastChattedAtMs), style = TpType.caption.copy(fontSize = 12.5.sp), color = palette.muted)
        }
        Text("Chat", style = TpType.caption.copy(fontSize = 13.sp), color = palette.accent)
    }
}

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
