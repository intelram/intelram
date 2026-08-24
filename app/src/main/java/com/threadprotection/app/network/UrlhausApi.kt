package com.threadprotection.app.network

import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

/** URLhaus (abuse.ch) — free with an Auth-Key from https://auth.abuse.ch/. Malware-distribution URL/host database. */
interface UrlhausApi {
    @FormUrlEncoded
    @POST("v1/url/")
    suspend fun lookupUrl(@Header("Auth-Key") authKey: String, @Field("url") url: String): UrlhausUrlResponse

    @FormUrlEncoded
    @POST("v1/host/")
    suspend fun lookupHost(@Header("Auth-Key") authKey: String, @Field("host") host: String): UrlhausHostResponse

    companion object {
        const val BASE_URL = "https://urlhaus-api.abuse.ch/"
    }
}

@Serializable
data class UrlhausUrlResponse(
    @kotlinx.serialization.SerialName("query_status") val queryStatus: String = "",
    val url: String? = null,
    @kotlinx.serialization.SerialName("url_status") val urlStatus: String? = null,
    val host: String? = null,
    val threat: String? = null,
    val tags: List<String>? = null,
    @kotlinx.serialization.SerialName("date_added") val dateAdded: String? = null,
    @kotlinx.serialization.SerialName("urlhaus_reference") val reference: String? = null,
) {
    val isListed: Boolean get() = queryStatus == "ok"
}

@Serializable
data class UrlhausHostResponse(
    @kotlinx.serialization.SerialName("query_status") val queryStatus: String = "",
    val host: String? = null,
    @kotlinx.serialization.SerialName("url_count") val urlCount: Int = 0,
) {
    val isListed: Boolean get() = queryStatus == "ok" && urlCount > 0
}
