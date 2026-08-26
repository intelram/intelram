package com.threadprotection.app.chat

import java.nio.charset.StandardCharsets

enum class ChatMode { BLUETOOTH, INTERNET }

enum class BtChatConnState { IDLE, BT_UNAVAILABLE, NO_PERMISSION, DISCOVERING, CONNECTING, HANDSHAKING, CONNECTED, FAILED }

/** rssi is the real signal strength Android reported for this device (`EXTRA_RSSI` on
 *  `ACTION_FOUND`), null if the OS didn't include one. kind comes from the peer's real Bluetooth
 *  Class of Device — see SignalEstimate.kt for both. */
data class BtDeviceInfo(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val rssi: Int? = null,
    val kind: BtDeviceKind = BtDeviceKind.GENERIC,
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
