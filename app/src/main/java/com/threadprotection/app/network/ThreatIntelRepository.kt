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

/** How much a single [UrlSignal] is allowed to move the verdict — see [UrlVerdictScoring]. */
enum class SignalWeight {
    /** A dedicated blocklist naming this exact URL/domain, or a certificate that fails validation. */
    DEFINITIVE,

    /** Meaningful but circumstantial: host-IP reputation, page content, registration age. */
    STRONG,

    /** Context the user should see, which must never decide the verdict on its own. */
    SUPPORTING,
}

data class UrlSignal(
    val source: String,
    val verdict: Verdict,
    val detail: String,
    val weight: SignalWeight = SignalWeight.SUPPORTING,
)

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
            threatDnsSignal(technical),
            domainAgeSignal(technical),
            tlsSignal(technical),
            dnsSecuritySignal(technical),
            redirectChainSignal(technical),
            contentSignal(content),
        )

        // "We reached the site and learned something" — without this, a domain that doesn't resolve
        // at all would fall through to SAFE purely for having produced no bad news.
        val hasAnyEvidence = signals.isNotEmpty() || technical?.resolvedIps?.isNotEmpty() == true

        val outcome = UrlVerdictScoring.evaluate(
            UrlVerdictScoring.Input(signals = signals, flags = onDeviceFlags, hasAnyEvidence = hasAnyEvidence),
        )

        UrlVerdict(
            url = rawUrl,
            overall = outcome.verdict,
            confidence = outcome.confidence,
            signals = signals,
            onDeviceFlags = onDeviceFlags.map { it.message },
            sourcesQueried = signals.size,
            technical = technical,
            content = content,
        )
    }

    /**
     * Cloudflare's malware/phishing resolver — the only real threat-intelligence source here that
     * needs no API key, and therefore the only one most users will ever actually have working. See
     * [ThreatDnsApi]'s doc for how the block signal was verified.
     */
    private fun threatDnsSignal(technical: TechnicalDetails?): UrlSignal? {
        val result = technical?.threatDns ?: return null
        if (!result.checked) return null
        return if (result.blocked) {
            UrlSignal("Cloudflare security DNS", Verdict.MALICIOUS, "Blocked as malware or phishing by Cloudflare's threat resolver", SignalWeight.DEFINITIVE)
        } else {
            UrlSignal("Cloudflare security DNS", Verdict.SAFE, "Not on Cloudflare's malware or phishing blocklist", SignalWeight.DEFINITIVE)
        }
    }

    /**
     * Registration age, the single most useful non-blocklist signal there is: phishing domains are
     * overwhelmingly days or weeks old, and a domain that has been continuously registered for
     * years is very hard to fake. Graded rather than binary — a two-year-old domain is genuinely
     * more reassuring than a four-month-old one.
     */
    private fun domainAgeSignal(technical: TechnicalDetails?): UrlSignal? {
        val domain = technical?.domain ?: return null
        val ageDays = domain.ageDays ?: return null
        val age = domain.ageHuman ?: "$ageDays days"
        val via = domain.registrar?.let { " · registered via $it" }.orEmpty()
        return when {
            ageDays < 30 -> UrlSignal("Domain age (RDAP)", Verdict.SUSPICIOUS, "Registered only $age ago — most phishing sites use brand-new domains$via", SignalWeight.STRONG)
            ageDays < 180 -> UrlSignal("Domain age (RDAP)", Verdict.SUSPICIOUS, "Registered $age ago — still fairly new$via", SignalWeight.SUPPORTING)
            ageDays < 365 -> UrlSignal("Domain age (RDAP)", Verdict.SAFE, "Registered $age ago$via", SignalWeight.SUPPORTING)
            else -> UrlSignal("Domain age (RDAP)", Verdict.SAFE, "Established domain — registered $age ago$via", SignalWeight.STRONG)
        }
    }

    private fun tlsSignal(technical: TechnicalDetails?): UrlSignal? {
        val tls = technical?.tls ?: return null
        val issuer = tls.issuer?.let { commonName(it) }
        return when {
            // A certificate that fails validation is the one TLS outcome that decides a verdict:
            // it means the connection cannot be trusted to reach who it claims to.
            tls.error != null -> UrlSignal("TLS certificate", Verdict.MALICIOUS, tls.error, SignalWeight.DEFINITIVE)
            tls.trusted -> UrlSignal(
                "TLS certificate",
                Verdict.SAFE,
                "Valid, trusted certificate from ${issuer ?: "a recognised authority"}" +
                    (tls.daysUntilExpiry?.let { " · expires in $it day${if (it == 1L) "" else "s"}" } ?: ""),
                SignalWeight.STRONG,
            )
            else -> null
        }
    }

    /** Pulls "DigiCert Inc" out of an X.500 name like `CN=DigiCert Inc,O=...,C=US`. */
    private fun commonName(x500: String): String =
        x500.split(',').firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
            ?.trim()?.removePrefix("CN=")?.removePrefix("cn=")
            ?: x500.take(60)

    /**
     * DNSSEC's absence is common and not itself suspicious — most legitimate consumer sites still
     * don't sign their zones — so this only ever contributes positive corroboration, never a
     * malicious/suspicious mark. Unvalidated DNS is still shown in the technical-details card, just
     * not counted against the verdict.
     */
    private fun dnsSecuritySignal(technical: TechnicalDetails?): UrlSignal? {
        val dnssec = technical?.dnssec ?: return null
        return if (dnssec.validated) {
            UrlSignal("DNS security (DNSSEC)", Verdict.SAFE, "DNS records are cryptographically signed and validated", SignalWeight.SUPPORTING)
        } else {
            null
        }
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
        return UrlSignal("Redirect chain", Verdict.UNKNOWN, "$chain → $landedOn", SignalWeight.SUPPORTING)
    }

    /**
     * Never DEFINITIVE on its own — one content heuristic isn't reliable enough to make the
     * strongest call by itself; corroborating signals push the overall verdict further through
     * [UrlVerdictScoring] when they agree. Severity 1 findings (a sign-in page, a missing title) are
     * reported to the user but deliberately don't count against the site at all: they describe
     * perfectly normal pages.
     */
    private fun contentSignal(content: ContentInspector.ContentReport?): UrlSignal? {
        if (content == null || content.error != null) return null
        val worst = content.findings.maxOfOrNull { it.severity }
            ?: return UrlSignal("Page content", Verdict.SAFE, "No phishing indicators found in the page itself", SignalWeight.SUPPORTING)
        val detail = content.findings.joinToString(" · ") { it.message }
        return when {
            worst >= 3 -> UrlSignal("Page content", Verdict.SUSPICIOUS, detail, SignalWeight.STRONG)
            worst == 2 -> UrlSignal("Page content", Verdict.SUSPICIOUS, detail, SignalWeight.SUPPORTING)
            else -> UrlSignal("Page content", Verdict.SAFE, detail, SignalWeight.SUPPORTING)
        }
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
                UrlSignal("Google Safe Browsing", Verdict.MALICIOUS, "Flagged for: $types", SignalWeight.DEFINITIVE)
            } else {
                UrlSignal("Google Safe Browsing", Verdict.SAFE, "No known threats found", SignalWeight.DEFINITIVE)
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
                stats.malicious > 0 -> UrlSignal("VirusTotal", Verdict.MALICIOUS, "${stats.malicious}/${stats.engineTotal} engines flagged this", SignalWeight.DEFINITIVE)
                stats.suspicious > 0 -> UrlSignal("VirusTotal", Verdict.SUSPICIOUS, "${stats.suspicious}/${stats.engineTotal} engines marked this suspicious", SignalWeight.STRONG)
                else -> UrlSignal("VirusTotal", Verdict.SAFE, "0/${stats.engineTotal} engines flagged this", SignalWeight.DEFINITIVE)
            }
        }
    }

    private suspend fun urlhausSignal(url: String, keys: ApiKeys): UrlSignal? {
        val key = keys[ApiKeyId.URLHAUS]
        if (key.isEmpty()) return null
        return withGuard("URLhaus") {
            val response = urlhaus.lookupUrl(key, url)
            if (response.isListed) {
                UrlSignal("URLhaus", Verdict.MALICIOUS, "Listed as ${response.threat ?: "malware distribution"} (${response.urlStatus ?: "unknown"})", SignalWeight.DEFINITIVE)
            } else {
                UrlSignal("URLhaus", Verdict.SAFE, "Not listed in the malware URL database", SignalWeight.DEFINITIVE)
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
                UrlSignal("ThreatFox", Verdict.MALICIOUS, "Reported as IOC for ${ioc?.malwarePrintable ?: "malware"} (confidence ${ioc?.confidenceLevel ?: 0}%)", SignalWeight.DEFINITIVE)
            } else {
                UrlSignal("ThreatFox", Verdict.SAFE, "No IOC reports for this host", SignalWeight.STRONG)
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
                result.inDatabase && result.valid == "y" -> UrlSignal("PhishTank", Verdict.MALICIOUS, "Confirmed phishing report on file", SignalWeight.DEFINITIVE)
                result.inDatabase -> UrlSignal("PhishTank", Verdict.SUSPICIOUS, "Reported, not yet verified", SignalWeight.STRONG)
                else -> UrlSignal("PhishTank", Verdict.SAFE, "Not in the phishing database", SignalWeight.STRONG)
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
                score >= 60 -> UrlSignal("AbuseIPDB", Verdict.MALICIOUS, "Host IP has a $score% abuse confidence score", SignalWeight.STRONG)
                score >= 25 -> UrlSignal("AbuseIPDB", Verdict.SUSPICIOUS, "Host IP has a $score% abuse confidence score", SignalWeight.SUPPORTING)
                else -> UrlSignal("AbuseIPDB", Verdict.SAFE, "Host IP has a low ($score%) abuse confidence score", SignalWeight.SUPPORTING)
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
