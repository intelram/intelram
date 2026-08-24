package com.threadprotection.app.network

import java.net.URI

/**
 * On-device URL red-flag checks — no network, no API key. These run for every QR scan and every
 * link check regardless of which (if any) threat-intel keys the user has configured.
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

    private val IMPERSONATED_BRANDS = listOf(
        "paypal", "google", "apple", "amazon", "microsoft", "netflix", "facebook", "instagram",
        "bankofamerica", "chase", "wellsfargo", "irs", "usps", "fedex", "ups", "dhl", "coinbase",
        "binance", "whatsapp",
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
        val brand = IMPERSONATED_BRANDS.firstOrNull { host.contains(it) }
        if (brand != null && !isOfficialDomain(host, brand)) {
            flags += Flag(3, "Domain contains \"$brand\" but is not $brand's real domain — possible brand impersonation")
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

    private fun isOfficialDomain(host: String, brand: String): Boolean {
        // crude but effective: the brand's real domain is the registrable domain itself,
        // e.g. "paypal.com" or "accounts.google.com" — not "paypal.evil-domain.tk".
        val labels = host.split(".")
        val registrable = if (labels.size >= 2) labels[labels.size - 2] else host
        return registrable == brand
    }

    private fun normalizeForParsing(rawUrl: String): String {
        var s = rawUrl.trim()
        if (!s.contains("://")) s = "https://$s"
        return s
    }
}
