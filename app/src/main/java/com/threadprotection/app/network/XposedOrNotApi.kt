package com.threadprotection.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * XposedOrNot breach-analytics — free, keyless, live (verified: 2 req/s, 25/hr, 100/day per IP,
 * no signup). The one honest "data breach security" source available without a paid key —
 * Have I Been Pwned's equivalent API stopped being free in 2024. See README §Data breach security.
 */
interface XposedOrNotApi {
    @GET("v1/breach-analytics")
    suspend fun breachAnalytics(@Query("email") email: String): XonAnalyticsResponse

    companion object {
        const val BASE_URL = "https://api.xposedornot.com/"
    }
}

@Serializable
data class XonAnalyticsResponse(
    @SerialName("ExposedBreaches") val exposedBreaches: XonExposedBreaches? = null,
)

@Serializable
data class XonExposedBreaches(
    @SerialName("breaches_details") val breachesDetails: List<XonBreachDetail>? = null,
)

@Serializable
data class XonBreachDetail(
    val breach: String? = null,
    val details: String? = null,
    val domain: String? = null,
    val industry: String? = null,
    @SerialName("password_risk") val passwordRisk: String? = null,
    @SerialName("xposed_data") val xposedData: String? = null,
    @SerialName("xposed_date") val xposedDate: String? = null,
    @SerialName("xposed_records") val xposedRecords: Long? = null,
    val verified: String? = null,
)
