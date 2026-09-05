package com.threadprotection.app.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * DNS-over-HTTPS, used for two different jobs against two different resolvers — both free, both
 * keyless, neither needing any signup:
 *
 * - **`dns.google`** ([DnsApi]) answers "what does this domain actually publish?" — the A/AAAA/MX/
 *   NS/TXT/SOA/CNAME record sets shown in the technical details, plus the `AD` (Authenticated Data)
 *   flag, which Google's resolver sets only when it successfully validated DNSSEC. This app never
 *   does the DNSSEC cryptography itself; it asks a resolver that already validates on every query
 *   and reads back whether that succeeded.
 * - **`security.cloudflare-dns.com`** ([ThreatDnsApi]) answers "does a major security vendor
 *   consider this domain malicious?" — see that interface's doc.
 */
interface DnsApi {
    @GET("resolve")
    suspend fun resolve(@Query("name") name: String, @Query("type") type: String = "A"): DohResponse

    companion object {
        const val BASE_URL = "https://dns.google/"
    }
}

/**
 * Cloudflare's malware/phishing-filtering resolver (the DoH front door for `1.1.1.2`).
 *
 * This is the one piece of *real* threat intelligence in the whole pipeline that needs no API key
 * at all, which matters enormously: with no keys configured — the state most users are in — every
 * reputation lookup (Safe Browsing, VirusTotal, URLhaus, ThreatFox, AbuseIPDB) sits out, and
 * PhishTank's keyless endpoint now answers with a Cloudflare interstitial rather than JSON. Without
 * this, a verdict rested entirely on domain age, TLS and heuristics.
 *
 * When Cloudflare classifies a domain as malware or phishing it answers `0.0.0.0` (A) / `::`
 * (AAAA) instead of the real address, with an RFC 8914 Extended DNS Error 16 ("Censored") in the
 * response comment. Verified live against Cloudflare's own documented test endpoints
 * `malware.testcategory.com` and `phishing.testcategory.com`, which return real Cloudflare IPs on
 * an unfiltered resolver and the sinkhole address here — so the sinkhole really is the verdict and
 * not the domain's own record. See `ThreatDnsInspectorTest`.
 */
interface ThreatDnsApi {
    @GET("dns-query")
    suspend fun resolve(
        @Query("name") name: String,
        @Query("type") type: String = "A",
        @Header("accept") accept: String = "application/dns-json",
    ): DohResponse

    companion object {
        const val BASE_URL = "https://security.cloudflare-dns.com/"
    }
}

@Serializable
data class DohResponse(
    val Status: Int = -1,
    val AD: Boolean = false,
    val Answer: List<DohAnswer> = emptyList(),
    val Comment: List<String> = emptyList(),
)

@Serializable
data class DohAnswer(val name: String? = null, val type: Int? = null, val TTL: Int? = null, val data: String? = null)

/** Reads Cloudflare's sinkhole answer — see [ThreatDnsApi]'s doc for how this was verified. */
object ThreatDnsVerdictReader {

    private val SINKHOLE_ADDRESSES = setOf("0.0.0.0", "::", "0000:0000:0000:0000:0000:0000:0000:0000")

    /** True when Cloudflare's security resolver is actively blocking this domain. */
    fun isBlocked(response: DohResponse): Boolean {
        if (response.Status != 0) return false
        val sinkholed = response.Answer.any { it.data?.trim() in SINKHOLE_ADDRESSES }
        val censored = response.Comment.any { it.contains("EDE(16)", ignoreCase = true) || it.contains("Censored", ignoreCase = true) }
        return sinkholed || censored
    }

    /** True when the resolver answered normally with at least one real address. */
    fun isClean(response: DohResponse): Boolean =
        response.Status == 0 && response.Answer.isNotEmpty() && !isBlocked(response)
}
