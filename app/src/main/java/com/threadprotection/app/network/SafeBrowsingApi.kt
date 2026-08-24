package com.threadprotection.app.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

/** Google Safe Browsing v4 Lookup API — free for non-commercial use with your own API key. */
interface SafeBrowsingApi {
    @POST("v4/threatMatches:find")
    suspend fun findThreatMatches(@Query("key") apiKey: String, @Body body: SafeBrowsingRequest): SafeBrowsingResponse

    companion object {
        const val BASE_URL = "https://safebrowsing.googleapis.com/"
    }
}

@Serializable
data class SafeBrowsingRequest(
    val client: SbClient = SbClient(),
    val threatInfo: SbThreatInfo,
)

@Serializable
data class SbClient(val clientId: String = "thread-protection-android", val clientVersion: String = "2.4.1")

@Serializable
data class SbThreatInfo(
    val threatTypes: List<String> = listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"),
    val platformTypes: List<String> = listOf("ANY_PLATFORM"),
    val threatEntryTypes: List<String> = listOf("URL"),
    val threatEntries: List<SbThreatEntry>,
)

@Serializable
data class SbThreatEntry(val url: String)

@Serializable
data class SafeBrowsingResponse(val matches: List<SbThreatMatch> = emptyList())

@Serializable
data class SbThreatMatch(val threatType: String = "", val platformType: String? = null)
