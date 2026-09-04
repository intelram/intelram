package com.threadprotection.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.threadprotection.app.chat.BtCallState
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
    onExitChat: () -> Unit,
    onStartCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showExitConfirm by remember { mutableStateOf(false) }

    // The mic permission belongs to *starting* a call, not to the call feature existing at all —
    // requested here, right when the user actually means to use it, same as Chat's own Bluetooth
    // permissions are requested at the point of scanning rather than up front at app launch.
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartCall()
    }
    val requestCall: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onStartCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.chatMessages.size) {
        if (state.chatMessages.isNotEmpty()) listState.animateScrollToItem(state.chatMessages.size - 1)
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("End this chat?", style = TpType.cardTitle.copy(fontSize = 17.sp), color = palette.fg) },
            text = {
                Text(
                    "The conversation will be saved to Chat History and the Bluetooth connection will be closed.",
                    style = TpType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                    color = palette.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { showExitConfirm = false; onExitChat() }) {
                    Text("Exit chat", color = palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Keep chatting", color = palette.fg2) }
            },
            containerColor = palette.card,
        )
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
            // Calling only makes sense on top of an already-connected chat, and only one call at a
            // time — the button disappears once one is ringing or live rather than doing nothing.
            if (state.btConnState == BtChatConnState.CONNECTED && state.callState == BtCallState.IDLE) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(palette.accentTint16)
                        .clickable(onClick = requestCall),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📞", fontSize = 17.sp)
                }
            }
            Text(
                "Exit",
                style = TpType.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = palette.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showExitConfirm = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.line))

        if (state.callState != BtCallState.IDLE) {
            CallStatusBar(state = state, onEndCall = onEndCall, onToggleMute = onToggleMute)
        } else if (state.callEndedReason != null) {
            Text(
                state.callEndedReason,
                style = TpType.caption.copy(fontSize = 13.sp),
                color = palette.muted,
                modifier = Modifier.fillMaxWidth().background(palette.card2).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.chatMessages.distinctBy { it.id }, key = { it.id }) { msg -> MessageBubble(msg) }
        }

        // The composer only opens once the connection is genuinely established (or the peer is
        // reachable via the store-and-forward relay). Until then there is nothing to send over, so
        // it stays disabled rather than accepting text that would silently go nowhere.
        val canSend = state.btConnState == BtChatConnState.CONNECTED || state.chatMeshPeer?.meshReachable == true
        if (!canSend) {
            Text(
                when (state.btConnState) {
                    BtChatConnState.CONNECTING -> "Connecting to this device…"
                    BtChatConnState.HANDSHAKING -> "Verifying the other device and securing the connection…"
                    else -> "Not connected. Messages can't be sent until the connection is re-established."
                },
                style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = palette.muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = state.chatDraft,
                onValueChange = onDraftChange,
                enabled = canSend,
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
                    .background(if (canSend && state.chatDraft.isNotBlank()) palette.accent else palette.muted3)
                    .clickable(enabled = canSend && state.chatDraft.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Text("➤", color = palette.onAccent, fontSize = 18.sp)
            }
        }
    }
}

/**
 * Reports the state the connection is actually in.
 *
 * This used to collapse every non-connected state into "Not in range", so a failed handshake, a
 * declined request or a dropped socket all read as "the other phone is too far away" — while it sat
 * on the same desk. Each real state now has its own line, and "not in range" is said only when the
 * peer genuinely isn't reachable directly.
 */
private fun connectionStatusLine(state: AppUiState): String = when {
    state.chatPeerTyping -> "typing…"
    // Showing the code here makes the out-of-band check something users can actually perform:
    // both phones display the same digits only if nobody is relaying between them.
    state.btConnState == BtChatConnState.CONNECTED ->
        state.chatSafetyCode?.let { "🔒 Encrypted · verify code $it matches on both phones" }
            ?: "🔒 End-to-end encrypted · post-quantum (ML-KEM-768)"
    state.btConnState == BtChatConnState.HANDSHAKING -> "Securing connection…"
    state.btConnState == BtChatConnState.CONNECTING -> "Connecting…"
    state.btConnState == BtChatConnState.REQUEST_SENT -> "Chat request sent — waiting for them to accept"
    state.btConnState == BtChatConnState.REQUEST_RECEIVED -> "They'd like to chat — answer the request to continue"
    state.btConnState == BtChatConnState.DENIED -> "They declined the chat request"
    state.btConnState == BtChatConnState.REQUEST_TIMEOUT -> "No answer — the request timed out"
    state.btConnState == BtChatConnState.FAILED -> "Connection failed — tap back and try again"
    state.btConnState == BtChatConnState.BT_UNAVAILABLE -> "Bluetooth is off"
    state.btConnState == BtChatConnState.NO_PERMISSION -> "Bluetooth permission needed"
    state.chatMeshPeer?.meshReachable == true -> "Not connected directly — messages relay via nearby phones"
    else -> "Disconnected — go back and reconnect to keep chatting"
}

/**
 * The in-conversation call strip — everything about an active or ringing call that isn't the
 * full-screen incoming-call overlay (that overlay only covers the RINGING/receiving side while the
 * user is elsewhere in the app). Here, on the conversation screen itself, all three non-idle states
 * get one compact bar rather than a second full-screen takeover, since the person is already looking
 * at this exact conversation.
 */
@Composable
internal fun CallStatusBar(state: AppUiState, onEndCall: () -> Unit, onToggleMute: () -> Unit) {
    val palette = LocalTpPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().background(palette.accentTint16).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when (state.callState) {
                    BtCallState.CALLING -> "Calling ${state.chatPeerName ?: "device"}…"
                    BtCallState.RINGING -> "Incoming call from ${state.chatPeerName ?: "device"}"
                    BtCallState.IN_CALL -> if (state.callMuted) "In call · muted" else "In call"
                    BtCallState.IDLE -> ""
                },
                style = TpType.cardTitleBold.copy(fontSize = 14.5.sp),
                color = palette.fg,
            )
            Text(
                "Voice call over this Bluetooth connection",
                style = TpType.caption.copy(fontSize = 11.5.sp),
                color = palette.muted,
            )
        }
        if (state.callState == BtCallState.IN_CALL) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (state.callMuted) palette.muted3 else palette.card2)
                    .clickable(onClick = onToggleMute),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.callMuted) "🔇" else "🎙️", fontSize = 15.sp)
            }
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.danger)
                .clickable(onClick = onEndCall),
            contentAlignment = Alignment.Center,
        ) {
            Text("📵", fontSize = 15.sp)
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.US)

@Composable
internal fun MessageBubble(msg: ChatUiMessage) {
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
            if (msg.relayed) {
                Text(
                    if (msg.fromMe) "via nearby relay — no delivery confirmation" else "arrived via nearby relay",
                    style = TpType.caption.copy(fontSize = 10.sp),
                    color = if (msg.fromMe) palette.onAccent.copy(alpha = 0.7f) else palette.muted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TIME_FORMAT.format(Date(msg.timestampMs)),
                    style = TpType.caption.copy(fontSize = 10.5.sp),
                    color = if (msg.fromMe) palette.onAccent.copy(alpha = 0.75f) else palette.muted,
                )
                if (msg.fromMe && !msg.relayed) {
                    Text(
                        if (msg.delivered) "✓✓" else "✓",
                        style = TpType.caption.copy(fontSize = 10.5.sp),
                        color = if (msg.delivered) androidx.compose.ui.graphics.Color(0xFF7FD8FF) else palette.onAccent.copy(alpha = 0.75f),
                    )
                } else if (msg.fromMe) {
                    Text("↝", style = TpType.caption.copy(fontSize = 12.sp), color = palette.onAccent.copy(alpha = 0.75f))
                }
            }
        }
    }
}
