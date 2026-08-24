package com.threadprotection.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** ThreatFox (abuse.ch) — free with an Auth-Key from https://auth.abuse.ch/. IOC (host/IP/URL) reputation. */
interface ThreatFoxApi {
    @POST("api/v1/")
    suspend fun searchIoc(@Header("Auth-Key") authKey: String, @Body body: ThreatFoxRequest): ThreatFoxResponse

    companion object {
        const val BASE_URL = "https://threatfox-api.abuse.ch/"
    }
}

@Serializable
data class ThreatFoxRequest(
    val query: String = "search_ioc",
    @SerialName("search_term") val searchTerm: String,
)

@Serializable
data class ThreatFoxResponse(
    @SerialName("query_status") val queryStatus: String = "",
    val data: List<ThreatFoxIoc>? = null,
) {
    val isListed: Boolean get() = queryStatus == "ok" && !data.isNullOrEmpty()
}

@Serializable
data class ThreatFoxIoc(
    val ioc: String = "",
    @SerialName("threat_type") val threatType: String? = null,
    @SerialName("malware_printable") val malwarePrintable: String? = null,
    @SerialName("confidence_level") val confidenceLevel: Int = 0,
    @SerialName("first_seen") val firstSeen: String? = null,
    val reporter: String? = null,
)
