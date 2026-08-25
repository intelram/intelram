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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.chat.BtChatConnState
import com.threadprotection.app.chat.ChatUiMessage
import com.threadprotection.app.state.AppUiState
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** WhatsApp-style thread for one active Bluetooth chat session — README's "give all the option they way we have in WhatsApp": bubbles, timestamps, delivered ticks, a typing indicator. */
@Composable
fun ChatConversationScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.chatMessages.size) {
        if (state.chatMessages.isNotEmpty()) listState.animateScrollToItem(state.chatMessages.size - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackCircleButton(onClick = onBack)
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(palette.accentTint16),
                contentAlignment = Alignment.Center,
            ) {
                Text("💬", fontSize = 18.sp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(state.chatPeerName ?: "Connecting…", style = TpType.cardTitleBold.copy(fontSize = 17.sp), color = palette.fg)
                Text(
                    connectionStatusLine(state),
                    style = TpType.caption.copy(fontSize = 12.5.sp),
                    color = if (state.btConnState == BtChatConnState.CONNECTED) palette.accent else palette.muted,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.chatMessages, key = { it.id }) { msg -> MessageBubble(msg) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = state.chatDraft,
                onValueChange = onDraftChange,
                placeholder = { Text("Message", color = palette.muted3) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.card2,
                    unfocusedContainerColor = palette.card2,
                    focusedTextColor = palette.fg,
                    unfocusedTextColor = palette.fg,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = palette.accent,
                ),
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (state.chatDraft.isNotBlank()) palette.accent else palette.muted3)
                    .clickable(enabled = state.chatDraft.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Text("➤", color = palette.onAccent, fontSize = 18.sp)
            }
        }
    }
}

private fun connectionStatusLine(state: AppUiState): String = when {
    state.chatPeerTyping -> "typing…"
    state.btConnState == BtChatConnState.CONNECTED -> "🔒 End-to-end encrypted · post-quantum (ML-KEM-768)"
    state.btConnState == BtChatConnState.HANDSHAKING -> "Securing connection…"
    else -> "Disconnected"
}

private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.US)

@Composable
private fun MessageBubble(msg: ChatUiMessage) {
    val palette = LocalTpPalette.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromMe) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (msg.fromMe) palette.accent else palette.card2)
                .border(BorderStroke(1.dp, if (msg.fromMe) palette.accent else palette.line2), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(msg.text, style = TpType.body.copy(fontSize = 15.5.sp, lineHeight = 21.sp), color = if (msg.fromMe) palette.onAccent else palette.fg)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TIME_FORMAT.format(Date(msg.timestampMs)),
                    style = TpType.caption.copy(fontSize = 10.5.sp),
                    color = if (msg.fromMe) palette.onAccent.copy(alpha = 0.75f) else palette.muted,
                )
                if (msg.fromMe) {
                    Text(
                        if (msg.delivered) "✓✓" else "✓",
                        style = TpType.caption.copy(fontSize = 10.5.sp),
                        color = if (msg.delivered) androidx.compose.ui.graphics.Color(0xFF7FD8FF) else palette.onAccent.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
