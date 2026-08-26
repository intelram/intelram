package com.threadprotection.app.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * The plaintext sealed inside a [MeshEnvelope]'s `aeadPayload` — only decryptable by the intended
 * destination, so this is the *only* place the sender's identity appears anywhere in the mesh
 * relay protocol. A relay carrying the envelope never sees this.
 *
 * Layout: [senderNodeIdLen:1][senderNodeId][senderNameLen:1][senderName][sentAtMs:8][bodyLen:2][body]
 */
data class MeshPayload(val senderNodeId: String, val senderName: String, val sentAtMs: Long, val body: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        val idBytes = senderNodeId.toByteArray(StandardCharsets.UTF_8)
        d.writeByte(idBytes.size)
        d.write(idBytes)
        val nameBytes = senderName.take(60).toByteArray(StandardCharsets.UTF_8)
        d.writeByte(nameBytes.size)
        d.write(nameBytes)
        d.writeLong(sentAtMs)
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        d.writeShort(bodyBytes.size)
        d.write(bodyBytes)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): MeshPayload? = runCatching {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            val idLen = d.readUnsignedByte()
            val id = String(ByteArray(idLen).also { d.readFully(it) }, StandardCharsets.UTF_8)
            val nameLen = d.readUnsignedByte()
            val name = String(ByteArray(nameLen).also { d.readFully(it) }, StandardCharsets.UTF_8)
            val sentAt = d.readLong()
            val bodyLen = d.readUnsignedShort()
            val body = String(ByteArray(bodyLen).also { d.readFully(it) }, StandardCharsets.UTF_8)
            MeshPayload(id, name, sentAt, body)
        }.getOrNull()
    }
}
