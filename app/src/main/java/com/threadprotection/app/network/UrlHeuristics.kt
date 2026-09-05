package com.threadprotection.app.network

import java.net.URI

/**
 * On-device URL red-flag checks — no network, no API key. These run for every QR scan and every
 * link check regardless of which (if any) threat-intel keys the user has configured.
 *
 * **Severity is a real scale, not a boolean.** [ThreatIntelRepository] weighs these against the
 * positive evidence it gathers (domain age, valid certificate, clean reputation lookups), so a
 * single low-severity note here no longer condemns a site on its own. Getting that wrong is what
 * made an ordinary `.top` domain read the same as a confirmed phishing page:
 * - 1 — worth mentioning, meaningless alone (a cheap TLD, hyphens, plain HTTP).
 * - 2 — genuinely unusual; a couple of these together are a real problem.
 * - 3 — a technique that essentially only exists to deceive (raw IP, punycode, embedded
 *   credentials, brand impersonation).
 */
object UrlHeuristics {

    private val SUSPICIOUS_TLDS = setOf(
        "zip", "mov", "top", "xyz", "click", "country", "gq", "tk", "ml", "cf", "work", "support",
        "quest", "cam", "rest", "loan", "win", "bid", "review", "party", "date", "stream",
    )

    private val URL_SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly",
        "rebrand.ly", "shorte.st", "s.id", "rb.gy", "tiny.cc", "lnkd.in",
    )

    private val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    data class Flag(val severity: Int, val message: String)

    /** Higher severity = worse. 0 flags means nothing suspicious was found on-device. */
    fun analyze(rawUrl: String): List<Flag> {
        val flags = mutableListOf<Flag>()
        val uri = runCatching { URI(normalizeForParsing(rawUrl)) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        if (host.isEmpty()) {
            flags += Flag(1, "Could not parse a host from this link")
            return flags
        }

        if (IPV4_REGEX.matches(host)) {
            flags += Flag(3, "Links straight to a raw IP address instead of a domain name")
        }
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            flags += Flag(3, "Uses punycode — often used to fake a trusted domain with look-alike letters")
        }
        val tld = host.substringAfterLast('.', "")
        if (tld in SUSPICIOUS_TLDS) {
            flags += Flag(1, "Uses a \".$tld\" domain ending frequently abused for scam sites")
        }
        if (host in URL_SHORTENERS) {
            flags += Flag(1, "Shortened link — the real destination is hidden until it's followed")
        }
        if (rawUrl.contains("@") && uri?.userInfo != null) {
            flags += Flag(3, "Contains an \"@\" before the real host — a classic link-spoofing trick")
        }
        val subdomainCount = host.count { it == '.' }
        if (subdomainCount >= 4) {
            flags += Flag(2, "Unusually many subdomains, often used to bury the real domain")
        }
        if (host.count { it == '-' } >= 4) {
            flags += Flag(1, "Unusually many hyphens in the domain name")
        }
        // See BrandRegistry's doc: whole-label matching for short tokens and leetspeak-normalised
        // lookalikes, never raw substring — `purchase.com` is not Chase and `backups.io` is not UPS.
        BrandRegistry.impersonationIn(host)?.let { match ->
            val why = when (match.kind) {
                BrandRegistry.MatchKind.LOOKALIKE ->
                    "Domain is a look-alike of ${match.brand.display} (\"${match.token}\" with digits swapped in) but isn't ${match.brand.display}'s real domain"
                BrandRegistry.MatchKind.LABEL ->
                    "Domain uses ${match.brand.display}'s name but isn't one of ${match.brand.display}'s real domains — possible brand impersonation"
            }
            flags += Flag(3, why)
        }
        uri?.port?.let { port ->
            if (port != -1 && port !in setOf(80, 443)) {
                flags += Flag(1, "Connects on non-standard port $port")
            }
        }
        if (rawUrl.startsWith("http://")) {
            flags += Flag(1, "Uses unencrypted HTTP instead of HTTPS")
        }

        return flags
    }

    fun isShortener(rawUrl: String): Boolean {
        val host = runCatching { URI(normalizeForParsing(rawUrl)).host?.lowercase() }.getOrNull() ?: return false
        return host in URL_SHORTENERS
    }

    private fun normalizeForParsing(rawUrl: String): String {
        var s = rawUrl.trim()
        if (!s.contains("://")) s = "https://$s"
        return s
    }
}
