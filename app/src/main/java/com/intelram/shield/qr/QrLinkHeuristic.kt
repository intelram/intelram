package com.intelram.shield.qr

import java.net.URI

sealed class LinkSafety {
    data object PlainText : LinkSafety()
    data class Safe(val url: String, val host: String) : LinkSafety()
    data class Suspicious(val url: String, val host: String, val reasons: List<String>) : LinkSafety()
}

/**
 * A basic, fully on-device heuristic for judging a decoded QR payload — not a
 * live threat-intelligence feed. It flags well-known red flags (raw IP hosts,
 * punycode/homograph domains, credential-stuffing "@" tricks, non-HTTPS links,
 * known shortener domains) so a result can be shown before the user opens it.
 */
object QrLinkHeuristic {

    private val shortenerHosts = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly",
    )

    private val suspiciousTlds = setOf(
        "zip", "mov", "xyz", "top", "gq", "tk", "cf", "ml", "work", "click",
    )

    fun evaluate(payload: String): LinkSafety {
        val uri = try {
            URI(payload.trim())
        } catch (e: Exception) {
            null
        }

        val scheme = uri?.scheme?.lowercase()
        if (uri == null || scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return LinkSafety.PlainText
        }

        val host = uri.host.lowercase()
        val reasons = mutableListOf<String>()

        if (scheme == "http") {
            reasons += "Uses an unencrypted (http://) connection"
        }
        if (host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            reasons += "Points to a raw IP address instead of a normal domain"
        }
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            reasons += "Uses punycode — a common trick to imitate a trusted domain"
        }
        if (payload.contains("@") && payload.substringBefore("://", "").isNotEmpty()) {
            val afterScheme = payload.substringAfter("://", "")
            if (afterScheme.contains("@")) {
                reasons += "Contains an \"@\" before the real destination, which can hide the true site"
            }
        }
        if (host in shortenerHosts) {
            reasons += "Uses a link shortener, which hides the real destination"
        }
        val tld = host.substringAfterLast('.', "")
        if (tld in suspiciousTlds) {
            reasons += "Ends in \".$tld\", a domain ending frequently abused for scams"
        }
        if (host.count { it == '.' } >= 4) {
            reasons += "Unusually many subdomains, often used to disguise the real host"
        }

        return if (reasons.isEmpty()) {
            LinkSafety.Safe(payload, host)
        } else {
            LinkSafety.Suspicious(payload, host, reasons)
        }
    }
}
