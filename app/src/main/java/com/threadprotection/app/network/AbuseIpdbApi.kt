package com.threadprotection.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** AbuseIPDB API v2 — free tier, 1,000 checks/day per the user's own key. */
interface AbuseIpdbApi {
    @GET("api/v2/check")
    suspend fun check(
        @Header("Key") apiKey: String,
        @Header("Accept") accept: String = "application/json",
        @Query("ipAddress") ipAddress: String,
        @Query("maxAgeInDays") maxAgeInDays: Int = 90,
    ): AbuseIpdbResponse

    companion object {
        const val BASE_URL = "https://api.abuseipdb.com/"
    }
}

@Serializable
data class AbuseIpdbResponse(val data: AbuseIpdbData? = null)

@Serializable
data class AbuseIpdbData(
    @SerialName("ipAddress") val ipAddress: String = "",
    @SerialName("abuseConfidenceScore") val abuseConfidenceScore: Int = 0,
    @SerialName("totalReports") val totalReports: Int = 0,
    @SerialName("countryCode") val countryCode: String? = null,
    val isp: String? = null,
    val domain: String? = null,
    @SerialName("isTor") val isTor: Boolean = false,
)
