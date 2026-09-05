package com.threadprotection.app.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google's public DNS-over-HTTPS JSON API — free, keyless, no rate-limit key required. Used for
 * exactly one thing: the `AD` (Authenticated Data) flag in the response, which Google's own
 * resolver sets only when it successfully validated DNSSEC for the answer. This app never does the
 * cryptographic DNSSEC validation itself — that would mean re-implementing a DNS resolver — it asks
 * a resolver that already validates on every query and reads back whether it succeeded, the same way
 * a browser's own "this connection is secure" check relies on the OS/CA trust store rather than
 * re-deriving trust from scratch.
 */
interface DnsApi {
    @GET("resolve")
    suspend fun resolve(@Query("name") name: String, @Query("type") type: String = "A"): DohResponse

    companion object {
        const val BASE_URL = "https://dns.google/"
    }
}

@Serializable
data class DohResponse(
    val Status: Int = -1,
    val AD: Boolean = false,
    val Answer: List<DohAnswer> = emptyList(),
)

@Serializable
data class DohAnswer(val name: String? = null, val type: Int? = null, val data: String? = null)
