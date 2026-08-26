package com.threadprotection.app.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The routing envelope carried hop-to-hop by the mesh relay (MeshRelayManager) — deliberately
 * carries *no* sender identity and no plaintext, only what a relay genuinely needs to move the
 * message along: which device it's for and an opaque sealed blob only that destination can open.
 * The sender's identity and the message text live inside [MeshPayload], which is encrypted
 * (kemCiphertext + aeadPayload) against the destination's own long-term public key — a relay can
 * see *who this is addressed to*, never who sent it or what it says.
 *
 * How-many-hops-left is tracked separately (`StoredMeshEnvelope.ttl` in SettingsRepository) rather
 * than baked into this envelope, since it changes every hop and re-encoding the ~1KB KEM
 * ciphertext just to update one byte would be wasteful — the gossip wire protocol sends the
 * current ttl alongside the envelope's unchanged bytes (see MeshRelayManager.writeEnvelopes).
 *
 * Layout: [msgId:16][destNodeIdLen:1][destNodeId][kemCtLen:2][kemCt][aeadLen:2][aead]
 */
data class MeshEnvelope(
    val msgId: String,
    val destNodeId: String,
    val kemCiphertext: ByteArray,
    val aeadPayload: ByteArray,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(uuidToBytes(msgId))
        val destBytes = destNodeId.toByteArray(StandardCharsets.UTF_8)
        d.writeByte(destBytes.size)
        d.write(destBytes)
        d.writeShort(kemCiphertext.size)
        d.write(kemCiphertext)
        d.writeShort(aeadPayload.size)
        d.write(aeadPayload)
        return out.toByteArray()
    }

    companion object {
        private const val MAX_FIELD_LEN = 65535

        fun decode(bytes: ByteArray): MeshEnvelope? = runCatching {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            val idBytes = ByteArray(16).also { d.readFully(it) }
            val destLen = d.readUnsignedByte()
            val destBytes = ByteArray(destLen).also { d.readFully(it) }
            val kemLen = d.readUnsignedShort()
            require(kemLen in 0..MAX_FIELD_LEN)
            val kemCt = ByteArray(kemLen).also { d.readFully(it) }
            val aeadLen = d.readUnsignedShort()
            require(aeadLen in 0..MAX_FIELD_LEN)
            val aead = ByteArray(aeadLen).also { d.readFully(it) }
            MeshEnvelope(bytesToUuid(idBytes), String(destBytes, StandardCharsets.UTF_8), kemCt, aead)
        }.getOrNull()

        fun uuidToBytes(id: String): ByteArray {
            val uuid = UUID.fromString(id)
            val out = ByteArrayOutputStream(16)
            val d = DataOutputStream(out)
            d.writeLong(uuid.mostSignificantBits)
            d.writeLong(uuid.leastSignificantBits)
            return out.toByteArray()
        }

        fun bytesToUuid(bytes: ByteArray): String {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            val most = d.readLong()
            val least = d.readLong()
            return UUID(most, least).toString()
        }
    }
}
