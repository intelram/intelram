package com.threadprotection.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** VirusTotal API v3 — free Public API tier, 4 req/min & 500/day per the user's own key. */
interface VirusTotalApi {
    @GET("api/v3/urls/{id}")
    suspend fun getUrlReport(@Header("x-apikey") apiKey: String, @Path("id") urlId: String): VtUrlReportResponse

    @FormUrlEncoded
    @POST("api/v3/urls")
    suspend fun submitUrl(@Header("x-apikey") apiKey: String, @Field("url") url: String): VtSubmitResponse

    @GET("api/v3/files/{hash}")
    suspend fun getFileReport(@Header("x-apikey") apiKey: String, @Path("hash") sha256: String): VtUrlReportResponse

    companion object {
        const val BASE_URL = "https://www.virustotal.com/"
    }
}

@Serializable
data class VtUrlReportResponse(val data: VtData? = null)

@Serializable
data class VtSubmitResponse(val data: VtSubmitData? = null)

@Serializable
data class VtSubmitData(val id: String? = null)

@Serializable
data class VtData(val id: String? = null, val attributes: VtAttributes? = null)

@Serializable
data class VtAttributes(
    @SerialName("last_analysis_stats") val lastAnalysisStats: VtAnalysisStats? = null,
    val reputation: Int? = null,
    @SerialName("total_votes") val totalVotes: VtVotes? = null,
)

@Serializable
data class VtAnalysisStats(
    val malicious: Int = 0,
    val suspicious: Int = 0,
    val harmless: Int = 0,
    val undetected: Int = 0,
    val timeout: Int = 0,
) {
    val engineTotal: Int get() = malicious + suspicious + harmless + undetected + timeout
}

@Serializable
data class VtVotes(val harmless: Int = 0, val malicious: Int = 0)
