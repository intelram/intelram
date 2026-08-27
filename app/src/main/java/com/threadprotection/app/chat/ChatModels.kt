package com.threadprotection.app.chat

import java.nio.charset.StandardCharsets

enum class ChatMode { BLUETOOTH, INTERNET }

enum class BtChatConnState { IDLE, BT_UNAVAILABLE, BLE_UNSUPPORTED, NO_PERMISSION, DISCOVERING, SCAN_FAILED, CONNECTING, HANDSHAKING, CONNECTED, FAILED }

/** A device found via BLE scanning, filtered at the OS/radio level to only devices advertising
 *  Thread Protection's own service UUID (see BluetoothChatManager.PRESENCE_SERVICE_UUID) — a
 *  generic Bluetooth accessory (headphones, a speaker, a laptop) never matches the scan filter, so
 *  it never reaches this list in the first place. rssi is the real signal strength from the BLE
 *  scan result; name is the peer's own chosen display name, read from the service-data payload
 *  they advertise alongside that UUID — not the phone's generic Bluetooth adapter name.
 *  lastSeenMs drives the staleness sweep that drops a device once its advertisements stop arriving
 *  (it went out of range or closed the app), since BLE scanning has no explicit "device left"
 *  event of its own. */
data class BtDeviceInfo(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val rssi: Int? = null,
    val kind: BtDeviceKind = BtDeviceKind.GENERIC,
    val lastSeenMs: Long = 0L,
)

/** relayed marks a message sent/received via the store-and-forward mesh (MeshRelayManager)
 *  instead of a live direct socket — shown with different tick styling in the UI since there's no
 *  synchronous delivery confirmation for a relayed message, only "queued" or "arrived". */
data class ChatUiMessage(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    val timestampMs: Long,
    val delivered: Boolean,
    val relayed: Boolean = false,
)

/** A device you've successfully chatted with before — README §Chat "History". Persisted (see
 *  SettingsRepository.chatHistoryFlow) so it survives leaving the Chat screen or restarting the
 *  app, letting you reconnect by address without rediscovering the device first. nodeId/
 *  publicKeyB64 are the contact's long-term mesh identity, learned during that handshake — with
 *  both present, you can message this contact via MeshRelayManager even when they're out of
 *  direct range (see AppViewModel.messageFromHistory). Empty strings mean this entry predates the
 *  mesh feature or the identity exchange failed; reconnecting directly refreshes it. */
/** A saved conversation as the UI sees it — the domain mirror of `StoredChatSession`. */
data class ChatSession(
    val sessionId: String,
    val address: String,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val status: ChatSessionStatus,
    val messages: List<ChatUiMessage>,
) {
    val sentCount: Int get() = messages.count { it.fromMe }
    val receivedCount: Int get() = messages.count { !it.fromMe }
}

enum class ChatSessionStatus { ACTIVE, COMPLETED, INTERRUPTED;

    val label: String
        get() = when (this) {
            ACTIVE -> "In progress"
            COMPLETED -> "Ended normally"
            INTERRUPTED -> "Disconnected unexpectedly"
        }
}

data class ChatHistoryEntry(
    val address: String,
    val name: String,
    val lastChattedAtMs: Long,
    val nodeId: String = "",
    val publicKeyB64: String = "",
) {
    val meshReachable: Boolean get() = nodeId.isNotBlank() && publicKeyB64.isNotBlank()
}

/**
 * Plaintext framing used *inside* the AES-GCM payload (after decryption) — README's "give all
 * the options the way we have in WhatsApp" needs more than raw text on the wire: a delivered
 * receipt and a typing indicator, both real application-level signals, not simulated.
 *
 * Layout: [type:1][idLen:1][id bytes][body bytes (rest)]
 */
sealed interface ChatWireMessage {
    data class Text(val id: String, val body: String) : ChatWireMessage
    data class Ack(val id: String) : ChatWireMessage
    data object Typing : ChatWireMessage

    companion object {
        private const val TYPE_TEXT: Byte = 0
        private const val TYPE_ACK: Byte = 1
        private const val TYPE_TYPING: Byte = 2

        fun encode(message: ChatWireMessage): ByteArray = when (message) {
            is Text -> frame(TYPE_TEXT, message.id, message.body.toByteArray(StandardCharsets.UTF_8))
            is Ack -> frame(TYPE_ACK, message.id, ByteArray(0))
            Typing -> frame(TYPE_TYPING, "", ByteArray(0))
        }

        fun decode(bytes: ByteArray): ChatWireMessage? {
            if (bytes.isEmpty()) return null
            val type = bytes[0]
            if (bytes.size < 2) return null
            val idLen = bytes[1].toInt() and 0xFF
            if (bytes.size < 2 + idLen) return null
            val id = String(bytes, 2, idLen, StandardCharsets.UTF_8)
            val body = String(bytes, 2 + idLen, bytes.size - 2 - idLen, StandardCharsets.UTF_8)
            return when (type) {
                TYPE_TEXT -> Text(id, body)
                TYPE_ACK -> Ack(id)
                TYPE_TYPING -> Typing
                else -> null
            }
        }

        private fun frame(type: Byte, id: String, body: ByteArray): ByteArray {
            val idBytes = id.toByteArray(StandardCharsets.UTF_8)
            require(idBytes.size <= 255) { "id too long" }
            return byteArrayOf(type, idBytes.size.toByte()) + idBytes + body
        }
    }
}
