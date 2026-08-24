package com.threadprotection.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/** PhishTank checkurl API — works fully keyless (verified live); an optional free key from phishtank.org just raises the rate limit. */
interface PhishTankApi {
    @FormUrlEncoded
    @POST("checkurl/")
    suspend fun checkUrl(
        @Field("url") url: String,
        @Field("format") format: String = "json",
        @Field("app_key") appKey: String? = null,
    ): PhishTankResponse

    companion object {
        const val BASE_URL = "https://checkurl.phishtank.com/"
    }
}

@Serializable
data class PhishTankResponse(val results: PhishTankResult? = null)

@Serializable
data class PhishTankResult(
    val url: String? = null,
    @SerialName("in_database") val inDatabase: Boolean = false,
    val valid: String? = null,
    val verified: String? = null,
    @SerialName("phish_detail_page") val detailPage: String? = null,
)
