package com.threadprotection.app.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/** ipwho.is — free, keyless, HTTPS IP geolocation/hosting lookup. Verified live (no key, no signup). */
interface IpInfoApi {
    @GET("{ip}")
    suspend fun lookup(@Path("ip") ip: String): IpInfoResponse

    companion object {
        const val BASE_URL = "https://ipwho.is/"
    }
}

@Serializable
data class IpInfoResponse(
    val success: Boolean = false,
    val country: String? = null,
    val city: String? = null,
    val connection: IpConnection? = null,
)

@Serializable
data class IpConnection(val isp: String? = null, val org: String? = null, val asn: Int? = null)
