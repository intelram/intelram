package com.threadprotection.app.data

import android.util.Base64
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class HashedPassword(val saltB64: String, val hashB64: String)

/** PBKDF2-HMAC-SHA256 password hashing for the local "Create an account" credential. */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun hash(password: CharSequence, salt: ByteArray = randomSalt()): HashedPassword {
        val hash = pbkdf2(password, salt)
        return HashedPassword(
            saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP),
        )
    }

    fun verify(password: CharSequence, saltB64: String, expectedHashB64: String): Boolean {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val candidate = pbkdf2(password, salt)
        val expected = Base64.decode(expectedHashB64, Base64.NO_WRAP)
        return constantTimeEquals(candidate, expected)
    }

    private fun pbkdf2(password: CharSequence, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
