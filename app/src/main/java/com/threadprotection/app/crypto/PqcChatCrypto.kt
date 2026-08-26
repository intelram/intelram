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
    data class SessionKey(val aesKey: ByteArray)

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
        return secretWithEncapsulation.encapsulation to SessionKey(deriveAesKey(secretWithEncapsulation.secret))
    }

    /** Initiator side: recovers the same shared secret the responder encapsulated, using our private key. */
    fun decapsulate(privateKey: MLKEMPrivateKeyParameters, ciphertext: ByteArray): SessionKey {
        val secret = MLKEMExtractor(privateKey).extractSecret(ciphertext)
        return SessionKey(deriveAesKey(secret))
    }

    private fun deriveAesKey(sharedSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, "thread-protection-chat-v1".toByteArray()))
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
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Reverses [encrypt] — input is iv(12) + ciphertext+tag. */
    fun decrypt(key: SessionKey, payload: ByteArray): ByteArray {
        require(payload.size > GCM_IV_LEN) { "Payload too short to contain an IV" }
        val iv = payload.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = payload.copyOfRange(GCM_IV_LEN, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
