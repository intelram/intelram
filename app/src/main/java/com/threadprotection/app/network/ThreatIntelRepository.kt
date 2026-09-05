package com.threadprotection.app.network

import android.util.Base64
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.ApiKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.IDN
import java.net.InetAddress
import java.net.URI

enum class Verdict { SAFE, SUSPICIOUS, MALICIOUS, UNKNOWN }

data class BreachRecord(val name: String, val date: String, val dataExposed: String, val records: Long?, val domain: String?)

data class BreachCheckResult(val email: String, val breaches: List<BreachRecord>, val checked: Boolean, val error: String? = null)

data class UrlSignal(val source: String, val verdict: Verdict, val detail: String)

data class UrlVerdict(
    val url: String,
    val overall: Verdict,
    val confidence: Int,
    val signals: List<UrlSignal>,
    val onDeviceFlags: List<String>,
    val sourcesQueried: Int,
    val technical: TechnicalDetails? = null,
    val content: ContentInspector.ContentReport? = null,
) {
    val matchedBy: String get() = signals.firstOrNull { it.verdict == Verdict.MALICIOUS }?.source
        ?: signals.firstOrNull { it.verdict == Verdict.SUSPICIOUS }?.source
        ?: signals.firstOrNull { it.verdict == Verdict.SAFE }?.source
        ?: "On-device analysis only"
}

/**
 * Aggregates every free threat-intel source the user has configured (README §Threat intelligence)
 * plus always-on on-device heuristics, into a single verdict. Every network source degrades
 * silently (contributes UNKNOWN, not a crash) when its key is missing or the request fails —
 * the app must stay useful with zero configuration, and get stronger as keys are added.
 */
class ThreatIntelRepository {
    private val safeBrowsing by lazy { NetworkModule.create<SafeBrowsingApi>(SafeBrowsingApi.BASE_URL) }
    private val virusTotal by lazy { NetworkModule.create<VirusTotalApi>(VirusTotalApi.BASE_URL) }
    private val abuseIpdb by lazy { NetworkModule.create<AbuseIpdbApi>(AbuseIpdbApi.BASE_URL) }
    private val nvd by lazy { NetworkModule.create<NvdApi>(NvdApi.BASE_URL) }
    private val phishTank by lazy { NetworkModule.create<PhishTankApi>(PhishTankApi.BASE_URL) }
    private val urlhaus by lazy { NetworkModule.create<UrlhausApi>(UrlhausApi.BASE_URL) }
    private val threatFox by lazy { NetworkModule.create<ThreatFoxApi>(ThreatFoxApi.BASE_URL) }
    private val xposedOrNot by lazy { NetworkModule.create<XposedOrNotApi>(XposedOrNotApi.BASE_URL) }

    /** Real, free, keyless breach lookup — README §Data breach security. */
    suspend fun checkEmailBreaches(email: String): BreachCheckResult {
        val result = withGuard("XposedOrNot") { xposedOrNot.breachAnalytics(email) }
            ?: return BreachCheckResult(email, emptyList(), checked = false, error = "Couldn't reach the breach database — check your connection and try again.")
        val details = result.exposedBreaches?.breachesDetails.orEmpty()
        return BreachCheckResult(
            email = email,
            breaches = details.map { d ->
                BreachRecord(
                    name = d.breach ?: "Unknown site",
                    date = d.xposedDate ?: "",
                    dataExposed = d.xposedData?.replace(";", ", ") ?: "",
                    records = d.xposedRecords,
                    domain = d.domain,
                )
            },
            checked = true,
        )
    }

    suspend fun checkUrl(rawUrl: String, keys: ApiKeys): UrlVerdict = coroutineScope {
        val onDeviceFlags = UrlHeuristics.analyze(rawUrl).sortedByDescending { it.severity }
        val host = runCatching { URI(normalize(rawUrl)).host }.getOrNull()?.let { IDN.toASCII(it) }

        val technicalDeferred = async { runCatching { TechnicalInspector.inspect(rawUrl) }.getOrNull() }
        // Content is judged against the page the link actually lands on — a shortener's own domain
        // is meaningless to inspect, only where it ultimately redirects to matters — so this waits
        // on the HTTP trace inside technicalDeferred rather than fetching `rawUrl` directly. It runs
        // concurrently with every reputation-list job below, not after them.
        val contentDeferred = async {
            val tech = technicalDeferred.await() ?: return@async null
            val finalUrl = tech.http?.finalUrl ?: return@async null
            withGuard("Page content") { ContentInspector.inspect(finalUrl, tech.host) }
        }

        val jobs = buildList {
            add(async { safeBrowsingSignal(rawUrl, keys) })
            add(async { virusTotalSignal(rawUrl, keys) })
            add(async { urlhausSignal(rawUrl, keys) })
            if (host != null) add(async { threatFoxSignal(host, keys) })
            add(async { phishTankSignal(rawUrl, keys) })
            if (host != null) add(async { abuseIpdbSignal(host, keys) })
        }
        val networkSignals = jobs.mapNotNull { it.await() }
        val technical = technicalDeferred.await()
        val content = contentDeferred.await()
        val signals = networkSignals + listOfNotNull(
            domainAgeSignal(technical),
            tlsSignal(technical),
            dnsSecuritySignal(technical),
            redirectChainSignal(technical),
            contentSignal(content),
        )

        val maliciousCount = signals.count { it.verdict == Verdict.MALICIOUS }
        val suspiciousCount = signals.count { it.verdict == Verdict.SUSPICIOUS }
        val worstHeuristic = onDeviceFlags.firstOrNull()?.severity ?: 0

        val overall = when {
            maliciousCount > 0 -> Verdict.MALICIOUS
            worstHeuristic >= 3 -> Verdict.MALICIOUS
            suspiciousCount > 0 || worstHeuristic >= 1 -> Verdict.SUSPICIOUS
            signals.any { it.verdict == Verdict.SAFE } -> Verdict.SAFE
            else -> Verdict.UNKNOWN
        }

        val confidence = when {
            signals.isEmpty() && onDeviceFlags.isEmpty() -> 40
            else -> (55 + signals.size * 8 + onDeviceFlags.size * 4).coerceAtMost(99)
        }

        UrlVerdict(
            url = rawUrl,
            overall = overall,
            confidence = confidence,
            signals = signals,
            onDeviceFlags = onDeviceFlags.map { it.message },
            sourcesQueried = signals.size,
            technical = technical,
            content = content,
        )
    }

    private fun domainAgeSignal(technical: TechnicalDetails?): UrlSignal? {
        val ageDays = technical?.domain?.ageDays ?: return null
        return when {
            ageDays < 30 -> UrlSignal("Domain registration (RDAP)", Verdict.SUSPICIOUS, "Registered only $ageDays day${if (ageDays == 1L) "" else "s"} ago — many phishing sites use brand-new domains")
            else -> UrlSignal("Domain registration (RDAP)", Verdict.SAFE, "Registered $ageDays days ago${technical.domain.registrar?.let { " via $it" }.orEmpty()}")
        }
    }

    private fun tlsSignal(technical: TechnicalDetails?): UrlSignal? {
        val tls = technical?.tls ?: return null
        return when {
            tls.error != null -> UrlSignal("TLS certificate (live check)", Verdict.MALICIOUS, tls.error)
            tls.trusted -> UrlSignal("TLS certificate (live check)", Verdict.SAFE, "Valid, trusted certificate issued by ${tls.issuer ?: "a recognised authority"}")
            else -> null
        }
    }

    /**
     * DNSSEC's absence is common and not itself suspicious — most legitimate consumer sites still
     * don't sign their zones — so this only ever contributes positive corroboration, never a
     * malicious/suspicious mark. Unvalidated DNS is still shown in the technical-details card, just
     * not counted against the verdict.
     */
    private fun dnsSecuritySignal(technical: TechnicalDetails?): UrlSignal? {
        val dnssec = technical?.dnssec ?: return null
        return if (dnssec.validated) UrlSignal("DNS security (DNSSEC)", Verdict.SAFE, "DNS responses for this domain are cryptographically signed and validated")
        else null
    }

    /**
     * The "hidden pages behind a short link" the user actually lands on. Neutral (UNKNOWN) rather
     * than SUSPICIOUS by itself — most redirects are entirely ordinary (a link shortener, a tracking
     * hop, a CDN) — but shown so the user sees exactly what a shortener was hiding before deciding
     * whether to continue, per the on-device shortener flag in [UrlHeuristics].
     */
    private fun redirectChainSignal(technical: TechnicalDetails?): UrlSignal? {
        val http = technical?.http ?: return null
        if (http.redirectCount == 0) return null
        val chain = http.redirectHosts.joinToString(" → ")
        val landedOn = runCatching { URI(http.finalUrl ?: "").host }.getOrNull() ?: http.finalUrl ?: "?"
        return UrlSignal("Redirect chain (live check)", Verdict.UNKNOWN, "$chain → $landedOn")
    }

    /**
     * Only ever elevates to SUSPICIOUS, never MALICIOUS, on its own — a single content heuristic
     * isn't reliable enough by itself to make the strongest call; corroborating signals (domain age,
     * TLS, reputation lists) push the overall verdict to MALICIOUS through the normal aggregation in
     * [checkUrl] when they agree.
     */
    private fun contentSignal(content: ContentInspector.ContentReport?): UrlSignal? {
        if (content == null || content.error != null) return null
        val worst = content.findings.maxOfOrNull { it.severity }
            ?: return UrlSignal("Page content check", Verdict.SAFE, "No obvious phishing indicators found in the fetched page content")
        val verdict = if (worst >= 3) Verdict.SUSPICIOUS else Verdict.SAFE
        return UrlSignal("Page content check", verdict, content.findings.joinToString(" · ") { it.message })
    }

    private suspend fun <T> withGuard(sourceLabel: String, block: suspend () -> T?): T? =
        runCatching { withTimeoutOrNull(8_000) { block() } }.getOrNull()

    private suspend fun safeBrowsingSignal(url: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.SAFE_BROWSING]
        if (key.isEmpty()) return null
        return withGuard("Google Safe Browsing") {
            val response = safeBrowsing.findThreatMatches(key, SafeBrowsingRequest(threatInfo = SbThreatInfo(threatEntries = listOf(SbThreatEntry(url)))))
            if (response.matches.isNotEmpty()) {
                val types = response.matches.joinToString { it.threatType.lowercase().replace('_', ' ') }
                UrlSignal("Google Safe Browsing", Verdict.MALICIOUS, "Flagged for: $types")
            } else {
                UrlSignal("Google Safe Browsing", Verdict.SAFE, "No known threats found")
            }
        }
    }

    private suspend fun virusTotalSignal(url: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.VIRUS_TOTAL]
        if (key.isEmpty()) return null
        return withGuard("VirusTotal") {
            val id = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val report = runCatching { virusTotal.getUrlReport(key, id) }.getOrNull()
            val stats = report?.data?.attributes?.lastAnalysisStats
            when {
                stats == null -> null
                stats.malicious > 0 -> UrlSignal("VirusTotal", Verdict.MALICIOUS, "${stats.malicious}/${stats.engineTotal} engines flagged this")
                stats.suspicious > 0 -> UrlSignal("VirusTotal", Verdict.SUSPICIOUS, "${stats.suspicious}/${stats.engineTotal} engines marked this suspicious")
                else -> UrlSignal("VirusTotal", Verdict.SAFE, "0/${stats.engineTotal} engines flagged this")
            }
        }
    }

    private suspend fun urlhausSignal(url: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.URLHAUS]
        if (key.isEmpty()) return null
        return withGuard("URLhaus") {
            val response = urlhaus.lookupUrl(key, url)
            if (response.isListed) {
                UrlSignal("URLhaus", Verdict.MALICIOUS, "Listed as ${response.threat ?: "malware distribution"} (${response.urlStatus ?: "unknown"})")
            } else {
                UrlSignal("URLhaus", Verdict.SAFE, "Not listed in the malware URL database")
            }
        }
    }

    private suspend fun threatFoxSignal(host: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.THREATFOX]
        if (key.isEmpty()) return null
        return withGuard("ThreatFox") {
            val response = threatFox.searchIoc(key, ThreatFoxRequest(searchTerm = host))
            if (response.isListed) {
                val ioc = response.data?.firstOrNull()
                UrlSignal("ThreatFox", Verdict.MALICIOUS, "Reported as IOC for ${ioc?.malwarePrintable ?: "malware"} (confidence ${ioc?.confidenceLevel ?: 0}%)")
            } else {
                UrlSignal("ThreatFox", Verdict.SAFE, "No IOC reports for this host")
            }
        }
    }

    private suspend fun phishTankSignal(url: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.PHISHTANK]
        return withGuard("PhishTank") {
            val response = phishTank.checkUrl(url = url, appKey = key.ifEmpty { null })
            val result = response.results
            when {
                result == null -> null
                result.inDatabase && result.valid == "y" -> UrlSignal("PhishTank", Verdict.MALICIOUS, "Confirmed phishing report on file")
                result.inDatabase -> UrlSignal("PhishTank", Verdict.SUSPICIOUS, "Reported, not yet verified")
                else -> UrlSignal("PhishTank", Verdict.SAFE, "Not in the phishing database")
            }
        }
    }

    private suspend fun abuseIpdbSignal(host: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.ABUSEIPDB]
        if (key.isEmpty()) return null
        return withGuard("AbuseIPDB") {
            val ip = resolveHost(host) ?: return@withGuard null
            val response = abuseIpdb.check(apiKey = key, ipAddress = ip)
            val score = response.data?.abuseConfidenceScore ?: return@withGuard null
            when {
                score >= 60 -> UrlSignal("AbuseIPDB", Verdict.MALICIOUS, "Host IP has a $score% abuse confidence score")
                score >= 25 -> UrlSignal("AbuseIPDB", Verdict.SUSPICIOUS, "Host IP has a $score% abuse confidence score")
                else -> UrlSignal("AbuseIPDB", Verdict.SAFE, "Host IP has a low ($score%) abuse confidence score")
            }
        }
    }

    private suspend fun resolveHost(host: String): String? = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
    }

    /** Best-effort real CVE lookup for an app/library name — README's "outdated software" finding. */
    suspend fun searchCves(productName: String, apiKey: String?): List<NvdCve> = withGuard("NVD") {
        nvd.searchCves(apiKey = apiKey?.takeIf { it.isNotBlank() }, keyword = productName, resultsPerPage = 5)
            .vulnerabilities.map { it.cve }
    }.orEmpty()

    private fun normalize(rawUrl: String): String {
        var s = rawUrl.trim()
        if (!s.contains("://")) s = "https://$s"
        return s
    }
}
