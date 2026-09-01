package com.threadprotection.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * NVD (NIST) CVE API 2.0 — the one source here that works with **no key at all** (5 req/30s;
 * a free key raises that to 50/30s). Used for the "outdated software — known CVEs" finding.
 */
interface NvdApi {
    @GET("rest/json/cves/2.0")
    suspend fun searchCves(
        @Header("apiKey") apiKey: String? = null,
        @Query("keywordSearch") keyword: String,
        @Query("keywordExactMatch") exactMatch: Boolean? = null,
        @Query("resultsPerPage") resultsPerPage: Int = 5,
    ): NvdResponse

    /** Exact-id lookup for a single CVE (Analyst Mode's CVE Search/Detail — see analyst/). Same
     *  endpoint as [searchCves], filtered server-side by `cveId` instead of a keyword. */
    @GET("rest/json/cves/2.0")
    suspend fun getCveById(
        @Header("apiKey") apiKey: String? = null,
        @Query("cveId") cveId: String,
    ): NvdResponse

    companion object {
        const val BASE_URL = "https://services.nvd.nist.gov/"
    }
}

@Serializable
data class NvdResponse(
    @SerialName("totalResults") val totalResults: Int = 0,
    val vulnerabilities: List<NvdVulnWrapper> = emptyList(),
)

@Serializable
data class NvdVulnWrapper(val cve: NvdCve)

@Serializable
data class NvdCve(
    val id: String,
    val published: String? = null,
    val lastModified: String? = null,
    val descriptions: List<NvdDescription> = emptyList(),
    val metrics: NvdMetrics? = null,
    /** CWE ids live nested two levels deep — one entry per source that classified the CVE, each
     *  carrying its own list of CWE description values (verified against a live NVD API 2.0
     *  response: `weaknesses[].description[].value`, e.g. "CWE-79"). */
    val weaknesses: List<NvdWeakness> = emptyList(),
    val references: List<NvdReference> = emptyList(),
    val configurations: List<NvdConfiguration> = emptyList(),
    /** These four are populated by NVD directly from CISA's KEV catalog — `cisaExploitAdd` is
     *  non-null exactly when this CVE is a confirmed Known Exploited Vulnerability. Reading them
     *  here avoids a second client for CISA's separate multi-megabyte KEV JSON feed. */
    val cisaExploitAdd: String? = null,
    val cisaActionDue: String? = null,
    val cisaRequiredAction: String? = null,
    val cisaVulnerabilityName: String? = null,
) {
    val englishDescription: String? get() = descriptions.firstOrNull { it.lang == "en" }?.value ?: descriptions.firstOrNull()?.value

    /** Deduplicated, e.g. ["CWE-79", "CWE-89"]. */
    val cweIds: List<String>
        get() = weaknesses.flatMap { it.description }.map { it.value }.filter { it.startsWith("CWE-") }.distinct()

    /** Deduplicated CPE match criteria strings across every configuration node — the closest thing
     *  this schema has to a flat "affected products" list. */
    val affectedProductCriteria: List<String>
        get() = configurations.flatMap { it.nodes }.flatMap { it.cpeMatch }.map { it.criteria }.distinct()
}

@Serializable
data class NvdDescription(val lang: String = "en", val value: String = "")

@Serializable
data class NvdWeakness(val source: String? = null, val description: List<NvdDescription> = emptyList())

@Serializable
data class NvdReference(val url: String, val source: String? = null, val tags: List<String> = emptyList())

@Serializable
data class NvdConfiguration(val nodes: List<NvdConfigNode> = emptyList())

@Serializable
data class NvdConfigNode(val cpeMatch: List<NvdCpeMatch> = emptyList())

@Serializable
data class NvdCpeMatch(val vulnerable: Boolean = false, val criteria: String = "")

@Serializable
data class NvdMetrics(
    @SerialName("cvssMetricV31") val cvssV31: List<NvdCvssMetric>? = null,
    @SerialName("cvssMetricV30") val cvssV30: List<NvdCvssMetric>? = null,
    @SerialName("cvssMetricV2") val cvssV2: List<NvdCvssMetric>? = null,
) {
    val bestSeverity: String? get() = (cvssV31 ?: cvssV30)?.firstOrNull()?.cvssData?.baseSeverity
        ?: cvssV2?.firstOrNull()?.cvssData?.baseSeverity
    val bestScore: Double? get() = (cvssV31 ?: cvssV30 ?: cvssV2)?.firstOrNull()?.cvssData?.baseScore
    /** Prefers v3.1, falling back through v3.0 to v2 — same precedence as [bestScore]/[bestSeverity]. */
    val bestMetric: NvdCvssMetric? get() = (cvssV31 ?: cvssV30 ?: cvssV2)?.firstOrNull()
}

@Serializable
data class NvdCvssMetric(val cvssData: NvdCvssData)

@Serializable
data class NvdCvssData(
    val version: String? = null,
    val vectorString: String? = null,
    val baseScore: Double? = null,
    val baseSeverity: String? = null,
)
