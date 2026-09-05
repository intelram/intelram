package com.threadprotection.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okio.Buffer

/**
 * Real, on-the-wire checks of what a page actually contains — not a spell-checker (this app bundles
 * no dictionary and makes no claim to catch every typo) but the same handful of concrete, verifiable
 * red flags a careful human would look for after landing on a page: does it claim to be a brand it
 * isn't, does it lean on the pressure language phishing kits reuse near-verbatim, does it ask for a
 * password on a page that gave every other reason to distrust it. Every finding here is a fact about
 * bytes this app actually fetched from [finalUrl] just now, never a guess.
 */
object ContentInspector {

    /** Reused near-verbatim across real phishing kits — not exhaustive, but each phrase alone is a
     * strong tell, and it costs nothing to check for all of them. */
    private val URGENCY_PHRASES = listOf(
        "verify your account immediately", "account will be suspended", "confirm your identity now",
        "your account has been limited", "unusual activity detected", "click here immediately",
        "act now or lose access", "your payment could not be processed", "update your billing information",
        "your account will be closed", "security alert: unauthorized", "your account has been temporarily locked",
        "confirm your details to avoid suspension", "urgent action required", "verify now to avoid",
    )

    private const val MAX_BYTES = 200_000L

    data class ContentFinding(val severity: Int, val message: String)

    data class ContentReport(
        val title: String?,
        val hasPasswordField: Boolean,
        val bytesRead: Int,
        val findings: List<ContentFinding>,
        val error: String? = null,
    )

    /** [host] is the *final*, post-redirect host — content is judged against where it actually
     * ended up, not the link that was scanned. */
    suspend fun inspect(finalUrl: String, host: String): ContentReport? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) {
            runCatching {
                val request = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", "Mozilla/5.0 (compatible; ThreadProtectionScanner/1.0; +on-device security check)")
                    .get()
                    .build()
                NetworkModule.okHttpClient.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.contains("html", ignoreCase = true) && contentType.isNotBlank()) {
                        return@use ContentReport(null, false, 0, emptyList())
                    }
                    val html = response.body?.source()?.let { source ->
                        // A single BufferedSource.read() call only returns what's already arrived
                        // off the wire, not up to the byte count asked for — loop until either the
                        // cap or the real end of the response is reached.
                        val buffer = Buffer()
                        var total = 0L
                        while (total < MAX_BYTES) {
                            val n = source.read(buffer, MAX_BYTES - total)
                            if (n == -1L) break
                            total += n
                        }
                        buffer.readString(Charsets.UTF_8)
                    }.orEmpty()
                    analyze(html, host)
                }
            }.getOrElse { e -> ContentReport(null, false, 0, emptyList(), error = e.message ?: "Could not fetch page content") }
        }
    }

    private fun analyze(html: String, host: String): ContentReport {
        if (html.isBlank()) return ContentReport(null, false, 0, emptyList())
        val lower = html.lowercase()
        val findings = mutableListOf<ContentFinding>()

        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
        val hasPasswordField = Regex("type\\s*=\\s*[\"']password[\"']").containsMatchIn(lower)

        val mentionedBrand = UrlHeuristics.IMPERSONATED_BRANDS.firstOrNull { brand ->
            lower.contains(brand) && !UrlHeuristics.isOfficialDomain(host, brand)
        }
        if (mentionedBrand != null) {
            findings += ContentFinding(3, "Page content refers to \"$mentionedBrand\" but is hosted on $host, not $mentionedBrand's own domain — a classic phishing setup")
        }

        val matchedPhrase = URGENCY_PHRASES.firstOrNull { lower.contains(it) }
        if (matchedPhrase != null) {
            findings += ContentFinding(2, "Uses pressure/urgency wording common to scam pages (\"$matchedPhrase\")")
        }

        if (hasPasswordField && mentionedBrand != null) {
            findings += ContentFinding(3, "Asks for a password while impersonating another brand's site")
        }

        if (title.isNullOrBlank()) {
            findings += ContentFinding(1, "Page has no <title> — often a sign of an unfinished or auto-generated scam page")
        }

        return ContentReport(title, hasPasswordField, html.toByteArray(Charsets.UTF_8).size, findings)
    }
}
