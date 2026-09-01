package com.threadprotection.app.analyst.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * FIRST.org's Exploit Prediction Scoring System — free, public, no API key. Returns the
 * probability (0.0–1.0) that a CVE will be exploited in the wild in the next 30 days, which is
 * what [com.threadprotection.app.analyst.domain.PriorityScoring] uses alongside CVSS and KEV.
 *
 * Response schema verified against a live call (`curl https://api.first.org/data/v1/epss?cve=…`)
 * rather than assumed: `epss` and `percentile` are returned as **strings**
 * (e.g. `"0.999990000"`), not JSON numbers, so [EpssRecord] models them as `String` and exposes
 * parsed `Double` properties — deserializing them as `Double` directly would fail.
 */
interface EpssApi {
    @GET("data/v1/epss")
    suspend fun getScore(@Query("cve") cve: String): EpssResponse

    companion object {
        const val BASE_URL = "https://api.first.org/"
    }
}

@Serializable
data class EpssResponse(
    val status: String = "",
    val data: List<EpssRecord> = emptyList(),
)

@Serializable
data class EpssRecord(
    val cve: String,
    val epss: String,
    val percentile: String,
    val date: String? = null,
) {
    val epssScore: Double? get() = epss.toDoubleOrNull()
    val percentileScore: Double? get() = percentile.toDoubleOrNull()
}
