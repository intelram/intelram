package com.threadprotection.app.network

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import java.security.MessageDigest

/**
 * Have I Been Pwned's Pwned Passwords range API — free, keyless, and built so the password never
 * leaves the phone (k-anonymity): only the first 5 hex characters of its SHA-1 are sent, and the
 * match against the ~800 returned suffixes happens on-device. `Add-Padding` makes every response a
 * similar size so response length can't hint at the prefix either.
 */
interface PwnedPasswordsApi {
    @Headers("Add-Padding: true")
    @GET("range/{prefix}")
    suspend fun range(@Path("prefix") prefix: String): ResponseBody

    companion object {
        const val BASE_URL = "https://api.pwnedpasswords.com/"
    }
}

/** Pure, offline half of the password check — hashing and matching, no I/O. */
object PasswordLeakCheck {

    /** Uppercase hex SHA-1, the format the range API's prefixes and suffixes use. */
    fun sha1Hex(password: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    /** How many times [suffix] appears in a range response. Padding lines carry a count of 0, so
     *  a match on one of those correctly reads as "not found". */
    fun countIn(rangeBody: String, suffix: String): Long =
        rangeBody.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(':').equals(suffix, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toLongOrNull()
            ?: 0L
}
