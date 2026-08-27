package com.threadprotection.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real post-quantum key exchange for Bluetooth chat (README: "highly encrypted to resist quantum
 * attacks"). ML-KEM-768 is the NIST-standardized (FIPS 203) post-quantum key encapsulation
 * mechanism — this is the actual algorithm, via Bouncy Castle's audited implementation, not a
 * hand-rolled scheme. A fresh ephemeral key pair is generated for every connection (forward
 * secrecy: compromising one session's key doesn't expose past or future sessions), the resulting
 * shared secret is run through HKDF-SHA256 to derive a 256-bit key, and every message is
 * encrypted with AES-256-GCM (authenticated — tampering is detected, not just hidden).
 *
 * Also used for the long-term "mesh identity" keypair (MeshIdentity.kt) — the same KEM primitive,
 * just generated once and persisted rather than per-session, so a message can be sealed for a
 * contact you've met before without a live connection to them (see MeshRelayManager.kt).
 *
 * Bouncy Castle's `org.bouncycastle.pqc.crypto.mlkem` package is marked deprecated in favour of
 * `org.bouncycastle.crypto.kems.MLKEMGenerator` in the newest releases (ML-KEM graduating from
 * "PQC" to "standard"), but the deprecated path is still the fully-functional, documented
 * implementation as of the pinned Bouncy Castle version here — used deliberately for a verified,
 * stable API surface rather than guessing at an unverified newer one.
 */
object PqcChatCrypto {
    private val random = SecureRandom()
    private val KEM_PARAMS = MLKEMParameters.ml_kem_768

    data class KemKeyPair(val publicKeyBytes: ByteArray, val privateKey: MLKEMPrivateKeyParameters)

    /**
     * Direction-separated session keys plus a short verification code.
     *
     * Security fix: both directions previously shared one AES key. Because AES-GCM decryption only
     * proves "someone who held this key produced this", an attacker who captured a frame A sent to
     * B could simply replay it back *at A*, and A would decrypt it, authenticate it, and display it
     * as a genuine message from B. Deriving one key for A->B and another for B->A from the same
     * shared secret removes that entirely: a reflected frame is encrypted under the wrong key for
     * the direction it arrives on and fails authentication outright.
     *
     * [safetyCode] is a short fingerprint of the shared secret. Both phones compute the same value
     * only if no one is sitting in the middle, so the two users can read it to each other to detect
     * a machine-in-the-middle — the KEM exchange itself is unauthenticated (no server, no PKI), so
     * this out-of-band check is the honest mitigation rather than pretending it's not needed.
     */
    data class SessionKey(
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val safetyCode: String,
    )

    fun generateKeyPair(): KemKeyPair {
        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(random, KEM_PARAMS))
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as MLKEMPublicKeyParameters
        val privateKey = keyPair.private as MLKEMPrivateKeyParameters
        return KemKeyPair(publicKey.encoded, privateKey)
    }

    /** Reconstructs a private key from its raw encoded bytes — used for the long-term mesh
     *  identity key (MeshIdentity.kt), which has to survive app restarts, unlike the ephemeral
     *  per-session keys `generateKeyPair()` normally produces. */
    fun loadPrivateKey(encoded: ByteArray): MLKEMPrivateKeyParameters = MLKEMPrivateKeyParameters(KEM_PARAMS, encoded)

    /** Responder side: encapsulates a fresh shared secret against the initiator's public key. Returns (ciphertext to send back, session key). */
    fun encapsulate(peerPublicKeyBytes: ByteArray): Pair<ByteArray, SessionKey> {
        val peerPublicKey = MLKEMPublicKeyParameters(KEM_PARAMS, peerPublicKeyBytes)
        val secretWithEncapsulation = MLKEMGenerator(random).generateEncapsulated(peerPublicKey)
        // The responder sends on the responder->initiator key and receives on the other.
        return secretWithEncapsulation.encapsulation to sessionKeys(secretWithEncapsulation.secret, isInitiator = false)
    }

    /** Initiator side: recovers the same shared secret the responder encapsulated, using our private key. */
    fun decapsulate(privateKey: MLKEMPrivateKeyParameters, ciphertext: ByteArray): SessionKey {
        val secret = MLKEMExtractor(privateKey).extractSecret(ciphertext)
        return sessionKeys(secret, isInitiator = true)
    }

    /** Splits one KEM shared secret into per-direction keys plus the shared verification code. */
    private fun sessionKeys(sharedSecret: ByteArray, isInitiator: Boolean): SessionKey {
        val initiatorToResponder = deriveKey(sharedSecret, "thread-protection-chat-v2-i2r")
        val responderToInitiator = deriveKey(sharedSecret, "thread-protection-chat-v2-r2i")
        val fingerprint = deriveKey(sharedSecret, "thread-protection-chat-v2-verify")
        return SessionKey(
            sendKey = if (isInitiator) initiatorToResponder else responderToInitiator,
            receiveKey = if (isInitiator) responderToInitiator else initiatorToResponder,
            safetyCode = safetyCodeOf(fingerprint),
        )
    }

    /** Six digits in two groups, easy to read aloud, derived only from the shared secret. */
    private fun safetyCodeOf(fingerprint: ByteArray): String {
        var value = 0L
        for (i in 0 until 6) value = (value shl 8) or (fingerprint[i].toLong() and 0xFF)
        val digits = (value % 1_000_000L).toString().padStart(6, '0')
        return "${digits.substring(0, 3)} ${digits.substring(3)}"
    }

    private fun deriveKey(sharedSecret: ByteArray, info: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, info.toByteArray()))
        val keyBytes = ByteArray(32)
        hkdf.generateBytes(keyBytes, 0, 32)
        return keyBytes
    }

    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    /** Returns iv(12) + ciphertext+tag, ready to frame and send. */
    fun encrypt(key: SessionKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.sendKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Reverses [encrypt] — input is iv(12) + ciphertext+tag. */
    fun decrypt(key: SessionKey, payload: ByteArray): ByteArray {
        require(payload.size > GCM_IV_LEN) { "Payload too short to contain an IV" }
        val iv = payload.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = payload.copyOfRange(GCM_IV_LEN, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.receiveKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
