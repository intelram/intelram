package com.intelram.shield.qr

import android.content.Context
import com.intelram.shield.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val SAFE_BROWSING_KEY_PLACEHOLDER = "YOUR_SAFE_BROWSING_API_KEY"
private const val MAX_REDIRECTS = 5
private const val TIMEOUT_MS = 4000

/**
 * Judges a decoded QR link WITHOUT ever rendering it — no WebView, no page
 * load, no JavaScript execution. Actually opening a suspicious page (even in
 * an embedded WebView) isn't a sandbox: the device's real browser engine
 * would still run the page's script and could still be exploited by it. This
 * inspects only network-level metadata instead, the same technique real
 * browsers use for link warnings:
 *
 *   1. [QrLinkHeuristic] — structural red flags in the URL text itself.
 *   2. The redirect chain the link actually leads through (HTTP headers
 *      only, no body is read, no downloads happen).
 *   3. Whether the final destination's TLS certificate is valid.
 *   4. Google Safe Browsing's live threat-list lookup, if a free API key is
 *      configured (see `safe_browsing_api_key` in strings.xml) — the same
 *      database Chrome and Firefox check. Skipped, not faked, if unset.
 */
class LinkInspector(private val context: Context) {

    suspend fun inspect(payload: String): LinkInspectionResult = withContext(Dispatchers.IO) {
        when (val heuristic = QrLinkHeuristic.evaluate(payload)) {
            is LinkSafety.PlainText -> LinkInspectionResult(
                originalUrl = payload,
                finalUrl = payload,
                redirectHops = emptyList(),
                verdict = LinkVerdict.NOT_A_LINK,
                reasons = emptyList(),
                safeBrowsingChecked = false,
                certificateValid = null,
            )
            is LinkSafety.Safe -> runInspection(payload, emptyList())
            is LinkSafety.Suspicious -> runInspection(payload, heuristic.reasons)
        }
    }

    private fun runInspection(url: String, heuristicReasons: List<String>): LinkInspectionResult {
        val reasons = heuristicReasons.toMutableList()

        val chain = resolveRedirectChain(url)
        val finalUrl = chain.lastOrNull() ?: url
        if (chain.size > 3) {
            reasons += "Redirects through ${chain.size} different addresses before landing"
        }

        val certValid = checkCertificate(finalUrl)
        if (certValid == false) {
            reasons += "The destination's security certificate doesn't check out"
        }

        val safeBrowsingHit = checkSafeBrowsing(finalUrl)
        if (safeBrowsingHit == true) {
            reasons += "Flagged by Google Safe Browsing as a known malicious or deceptive site"
        }

        val verdict = when {
            safeBrowsingHit == true -> LinkVerdict.UNSAFE
            certValid == false -> LinkVerdict.UNSAFE
            reasons.size >= 2 -> LinkVerdict.UNSAFE
            reasons.size == 1 -> LinkVerdict.CAUTION
            else -> LinkVerdict.SAFE
        }

        return LinkInspectionResult(
            originalUrl = url,
            finalUrl = finalUrl,
            redirectHops = chain,
            verdict = verdict,
            reasons = reasons,
            safeBrowsingChecked = safeBrowsingHit != null,
            certificateValid = certValid,
        )
    }

    /** Follows redirects via headers only — no response body is ever read. */
    private fun resolveRedirectChain(start: String): List<String> {
        val chain = mutableListOf(start)
        var current = start
        repeat(MAX_REDIRECTS) {
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return chain
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "GET"
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = try {
                        URL(URL(current), location).toString()
                    } catch (e: Exception) {
                        return chain
                    }
                    chain += current
                } else {
                    return chain
                }
            } catch (e: Exception) {
                return chain
            } finally {
                connection.disconnect()
            }
        }
        return chain
    }

    /** Null when not https (nothing to check); false only on a genuine trust failure. */
    private fun checkCertificate(urlStr: String): Boolean? {
        if (!urlStr.startsWith("https://", ignoreCase = true)) return null
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(urlStr).openConnection() as HttpsURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.connect()
            connection.serverCertificates
            true
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** Null when no API key is configured or the check itself fails — never fabricated. */
    private fun checkSafeBrowsing(urlStr: String): Boolean? {
        val apiKey = context.getString(R.string.safe_browsing_api_key)
        if (apiKey.isBlank() || apiKey == SAFE_BROWSING_KEY_PLACEHOLDER) return null

        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL("https://safebrowsing.googleapis.com/v4/threatMatches:find?key=$apiKey")
            val requestBody = JSONObject().apply {
                put(
                    "client",
                    JSONObject().apply {
                        put("clientId", "threat-protection-android")
                        put("clientVersion", "1.0.0")
                    },
                )
                put(
                    "threatInfo",
                    JSONObject().apply {
                        put("threatTypes", JSONArray(listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE")))
                        put("platformTypes", JSONArray(listOf("ANY_PLATFORM")))
                        put("threatEntryTypes", JSONArray(listOf("URL")))
                        put("threatEntries", JSONArray().put(JSONObject().put("url", urlStr)))
                    },
                )
            }

            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) return null
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(responseText).has("matches")
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
