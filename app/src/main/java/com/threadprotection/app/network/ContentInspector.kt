package com.threadprotection.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okio.Buffer

/**
 * Real, on-the-wire checks of what a page actually contains — not a spell-checker (this app bundles
 * no dictionary and makes no claim to catch every typo) but the same handful of concrete, verifiable
 * red flags a careful human would look for after landing on a page. Every finding here is a fact
 * about bytes this app actually fetched from the final URL just now, never a guess.
 *
 * **Two root causes this was rewritten to fix**, both of which made ordinary sites read as
 * suspicious:
 *
 * 1. **It searched raw HTML, including scripts and stylesheets.** `google.com`'s own homepage
 *    matched "microsoft" (from `navigator.userAgent` sniffing for `"microsoft edge"`) and "irs"
 *    (inside the CSS `:first-child`); `bbc.com` matched six brands. Only [visibleText] — markup,
 *    `<script>` and `<style>` stripped — is searched now, and only on whole-word boundaries.
 * 2. **Naming a brand was treated as impersonation by itself.** Any page that mentions a big
 *    company — a news story, a review, a documentation page — was flagged. Impersonation now
 *    requires the actual phishing shape: the brand is named *and* the page collects a password
 *    *and* the host isn't one of that brand's own domains.
 */
object ContentInspector {

    /** Reused near-verbatim across real phishing kits — each phrase alone is a strong tell. */
    private val URGENCY_PHRASES = listOf(
        "verify your account immediately", "account will be suspended", "confirm your identity now",
        "your account has been limited", "unusual activity detected", "click here immediately",
        "act now or lose access", "your payment could not be processed", "update your billing information",
        "your account will be closed", "security alert: unauthorized", "your account has been temporarily locked",
        "confirm your details to avoid suspension", "urgent action required", "verify now to avoid",
    )

    private const val MAX_BYTES = 200_000L

    private val SCRIPT_OR_STYLE = Regex("<(script|style|noscript|template)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val ANY_TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PASSWORD_FIELD = Regex("<input[^>]*type\\s*=\\s*[\"']?password", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    data class ContentFinding(val severity: Int, val message: String)

    data class ContentReport(
        val title: String?,
        val hasPasswordField: Boolean,
        val bytesRead: Int,
        val findings: List<ContentFinding>,
        val error: String? = null,
    )

    /** [host] is the *final*, post-redirect host — content is judged against where the link
     *  actually ended up, not the link that was scanned. */
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

    /** What a person actually reads on the page: no markup, no scripts, no stylesheets. */
    internal fun visibleText(html: String): String =
        html.replace(SCRIPT_OR_STYLE, " ")
            .replace(HTML_COMMENT, " ")
            .replace(ANY_TAG, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** Split out from [inspect] so the judgement is testable without a network fetch. */
    internal fun analyze(html: String, host: String): ContentReport {
        if (html.isBlank()) return ContentReport(null, false, 0, emptyList())
        val findings = mutableListOf<ContentFinding>()

        val title = TITLE_TAG.find(html)?.groupValues?.get(1)?.replace(WHITESPACE, " ")?.trim()?.take(200)
        val hasPasswordField = PASSWORD_FIELD.containsMatchIn(html)
        val text = visibleText(html)
        val lowerText = text.lowercase()

        // Impersonation needs the whole phishing shape, not just a brand name on the page — see
        // this object's doc for the false positives that rule exists to kill.
        val namedBrand = BrandRegistry.brandNamedInText(text, host)
        if (namedBrand != null && hasPasswordField) {
            findings += ContentFinding(
                3,
                "Asks for a password while presenting itself as ${namedBrand.display} — but $host is not one of ${namedBrand.display}'s own domains",
            )
        }

        val matchedPhrase = URGENCY_PHRASES.firstOrNull { BrandRegistry.containsWord(lowerText, it) }
        if (matchedPhrase != null) {
            findings += ContentFinding(2, "Uses pressure/urgency wording common to scam pages (\"$matchedPhrase\")")
        }

        if (hasPasswordField && findings.isEmpty()) {
            // Worth stating plainly — a sign-in page is not a problem, it's just the thing worth
            // being sure about before typing into it.
            findings += ContentFinding(1, "This page asks for a password — make sure the address above is the one you expect")
        }

        if (title.isNullOrBlank()) {
            findings += ContentFinding(1, "Page has no title — sometimes a sign of an unfinished or auto-generated page")
        }

        return ContentReport(title, hasPasswordField, html.toByteArray(Charsets.UTF_8).size, findings)
    }
}
