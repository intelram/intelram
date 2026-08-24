package com.threadprotection.app.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * RDAP (RFC 9083) — the IETF/ICANN-mandated, free, keyless successor to WHOIS. `rdap.org` is the
 * public bootstrap client that redirects to whichever registry actually holds the domain, so one
 * request works for (almost) any TLD. Used for "when was this domain registered" — a brand-new
 * domain is one of the strongest, most standard phishing signals there is.
 */
interface RdapApi {
    @GET("domain/{domain}")
    suspend fun lookup(@Path("domain") domain: String): RdapResponse

    companion object {
        const val BASE_URL = "https://rdap.org/"
    }
}

@Serializable
data class RdapResponse(
    val ldhName: String? = null,
    val events: List<RdapEvent> = emptyList(),
    val entities: List<RdapEntity> = emptyList(),
    val status: List<String> = emptyList(),
) {
    val registeredOn: String? get() = events.firstOrNull { it.eventAction == "registration" }?.eventDate
    val expiresOn: String? get() = events.firstOrNull { it.eventAction == "expiration" }?.eventDate
    val lastChanged: String? get() = events.firstOrNull { it.eventAction == "last changed" }?.eventDate
    val registrarName: String? get() = entities.firstOrNull { "registrar" in it.roles }?.vcardFn
}

@Serializable
data class RdapEvent(val eventAction: String? = null, val eventDate: String? = null)

@Serializable
data class RdapEntity(
    val roles: List<String> = emptyList(),
    val vcardArray: JsonElement? = null,
) {
    /** vCard is `["vcard", [[prop, params, type, value], ...]]` — dig out the "fn" (formatted name) entry. */
    val vcardFn: String?
        get() {
            val arr = vcardArray as? JsonArray ?: return null
            val props = arr.getOrNull(1) as? JsonArray ?: return null
            for (propEl in props) {
                val prop = propEl as? JsonArray ?: continue
                val name = (prop.getOrNull(0) as? JsonPrimitive)?.content
                if (name == "fn") {
                    return (prop.getOrNull(3) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            }
            return null
        }
}
