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
    val descriptions: List<NvdDescription> = emptyList(),
    val metrics: NvdMetrics? = null,
) {
    val englishDescription: String? get() = descriptions.firstOrNull { it.lang == "en" }?.value ?: descriptions.firstOrNull()?.value
}

@Serializable
data class NvdDescription(val lang: String = "en", val value: String = "")

@Serializable
data class NvdMetrics(
    @SerialName("cvssMetricV31") val cvssV31: List<NvdCvssMetric>? = null,
    @SerialName("cvssMetricV30") val cvssV30: List<NvdCvssMetric>? = null,
    @SerialName("cvssMetricV2") val cvssV2: List<NvdCvssMetric>? = null,
) {
    val bestSeverity: String? get() = (cvssV31 ?: cvssV30)?.firstOrNull()?.cvssData?.baseSeverity
        ?: cvssV2?.firstOrNull()?.cvssData?.baseSeverity
    val bestScore: Double? get() = (cvssV31 ?: cvssV30 ?: cvssV2)?.firstOrNull()?.cvssData?.baseScore
}

@Serializable
data class NvdCvssMetric(val cvssData: NvdCvssData)

@Serializable
data class NvdCvssData(val baseScore: Double? = null, val baseSeverity: String? = null)
