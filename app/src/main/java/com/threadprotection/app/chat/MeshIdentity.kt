package com.threadprotection.app.chat

import android.util.Base64
import com.threadprotection.app.crypto.PqcChatCrypto
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.data.StoredIdentity
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import java.util.UUID

/** This device's own long-term identity for the mesh relay: a random node ID (independent of the
 *  Bluetooth MAC, since that's only meaningful for a live connection) plus a persistent ML-KEM-768
 *  keypair, generated once on first use and kept for the life of the install. Any device you've
 *  chatted with directly learns this public key during the handshake (see
 *  BluetoothChatManager.establishSession) and can then seal a message for you even when you're
 *  not directly reachable — see MeshRelayManager. */
data class MeshIdentity(val nodeId: String, val publicKeyBytes: ByteArray, val privateKey: MLKEMPrivateKeyParameters)

/** What two devices exchange, once, over their already-encrypted live session during
 *  BluetoothChatManager's handshake — this is how a contact learns your long-term public key so
 *  they can reach you later via the mesh relay even without a live connection.
 *  Layout: [nodeIdLen:1][nodeId][pubKeyLen:2][pubKey] */
data class MeshIdentityInfo(val nodeId: String, val publicKeyBytes: ByteArray) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        val idBytes = nodeId.toByteArray(Charsets.UTF_8)
        d.writeByte(idBytes.size)
        d.write(idBytes)
        d.writeShort(publicKeyBytes.size)
        d.write(publicKeyBytes)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): MeshIdentityInfo? = runCatching {
            val d = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            val idLen = d.readUnsignedByte()
            val id = String(ByteArray(idLen).also { d.readFully(it) }, Charsets.UTF_8)
            val keyLen = d.readUnsignedShort()
            val key = ByteArray(keyLen).also { d.readFully(it) }
            MeshIdentityInfo(id, key)
        }.getOrNull()
    }
}

object MeshIdentityStore {
    suspend fun ensure(settingsRepository: SettingsRepository): MeshIdentity {
        val stored = settingsRepository.ensureIdentity {
            val keyPair = PqcChatCrypto.generateKeyPair()
            StoredIdentity(
                nodeId = UUID.randomUUID().toString(),
                publicKeyB64 = Base64.encodeToString(keyPair.publicKeyBytes, Base64.NO_WRAP),
                privateKeyB64 = Base64.encodeToString(keyPair.privateKey.encoded, Base64.NO_WRAP),
            )
        }
        val publicKeyBytes = Base64.decode(stored.publicKeyB64, Base64.NO_WRAP)
        val privateKey = PqcChatCrypto.loadPrivateKey(Base64.decode(stored.privateKeyB64, Base64.NO_WRAP))
        return MeshIdentity(stored.nodeId, publicKeyBytes, privateKey)
    }
}
