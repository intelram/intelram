package com.threadprotection.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.IDN
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Real X.509 certificate details read straight off the live TLS handshake — not looked up, actually connected to. */
data class TlsDetails(
    val trusted: Boolean,
    val issuer: String?,
    val subject: String?,
    val validFrom: String?,
    val validTo: String?,
    val daysUntilExpiry: Long?,
    val selfSigned: Boolean,
    val error: String? = null,
)

data class DomainRegistration(
    val registrar: String?,
    val registeredOn: String?,
    val expiresOn: String?,
    val ageDays: Long?,
)

data class IpIntel(val ip: String, val country: String?, val city: String?, val isp: String?, val org: String?, val asn: Int?)

data class HttpTrace(
    val finalUrl: String?,
    val statusCode: Int?,
    val serverHeader: String?,
    val redirectCount: Int,
    val redirectHosts: List<String>,
    val usedHttps: Boolean,
    val error: String? = null,
)

/** Whether this domain's DNS answers are DNSSEC-signed and validated — see [DnsApi]'s doc comment. */
data class DnssecStatus(val validated: Boolean, val nameservers: List<String>)

data class TechnicalDetails(
    val host: String,
    val resolvedIps: List<String>,
    val ipIntel: IpIntel?,
    val domain: DomainRegistration?,
    val tls: TlsDetails?,
    val http: HttpTrace?,
    val dnssec: DnssecStatus?,
)

/**
 * Everything about a URL/domain that's independently verifiable, on top of the reputation-list
 * signals in [ThreatIntelRepository] — README's "show the technical details as proof": who owns
 * the IP, when the domain was registered, whether its TLS certificate is real and trusted, and
 * what actually happens when you request it (redirect chain, real server, real status code).
 * Every field here comes from a live lookup or a live connection made right now, never a guess.
 */
object TechnicalInspector {

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val rdap by lazy { NetworkModule.create<RdapApi>(RdapApi.BASE_URL) }
    private val ipInfo by lazy { NetworkModule.create<IpInfoApi>(IpInfoApi.BASE_URL) }
    private val dns by lazy { NetworkModule.create<DnsApi>(DnsApi.BASE_URL) }

    suspend fun inspect(rawUrl: String): TechnicalDetails? = coroutineScope {
        val normalized = if ("://" in rawUrl) rawUrl.trim() else "https://${rawUrl.trim()}"
        val host = runCatching { java.net.URI(normalized).host }.getOrNull()?.let { runCatching { IDN.toASCII(it) }.getOrDefault(it) }
            ?: return@coroutineScope null

        val ips = resolveHost(host)
        val ipDeferred = async { ips.firstOrNull()?.let { fetchIpIntel(it) } }
        val domainDeferred = async { fetchDomainRegistration(host) }
        val tlsDeferred = async { fetchTlsDetails(host) }
        val httpDeferred = async { fetchHttpTrace(normalized) }
        val dnssecDeferred = async { fetchDnssecStatus(host) }

        TechnicalDetails(
            host = host,
            resolvedIps = ips,
            ipIntel = ipDeferred.await(),
            domain = domainDeferred.await(),
            tls = tlsDeferred.await(),
            http = httpDeferred.await(),
            dnssec = dnssecDeferred.await(),
        )
    }

    private suspend fun resolveHost(host: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    private suspend fun fetchIpIntel(ip: String): IpIntel? = withTimeoutOrNull(6_000) {
        runCatching { ipInfo.lookup(ip) }.getOrNull()
            ?.takeIf { it.success }
            ?.let { IpIntel(ip, it.country, it.city, it.connection?.isp, it.connection?.org, it.connection?.asn) }
    }

    private suspend fun fetchDomainRegistration(host: String): DomainRegistration? = withTimeoutOrNull(8_000) {
        val registrable = registrableDomain(host) ?: return@withTimeoutOrNull null
        val response = runCatching { rdap.lookup(registrable) }.getOrNull() ?: return@withTimeoutOrNull null
        val registeredOn = response.registeredOn
        val ageDays = registeredOn?.let { parseRdapInstant(it) }?.let { ChronoUnit.DAYS.between(it, Instant.now()) }
        DomainRegistration(
            registrar = response.registrarName,
            registeredOn = registeredOn,
            expiresOn = response.expiresOn,
            ageDays = ageDays,
        )
    }

    private fun parseRdapInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    /** Naive eTLD+1 extraction: exact for the vast majority of domains, imperfect for uncommon multi-label TLDs — no bundled public-suffix list. */
    private fun registrableDomain(host: String): String? {
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        val lastTwo = labels.takeLast(2).joinToString(".")
        val secondLevel = labels.getOrNull(labels.size - 2)?.lowercase()
        return if (secondLevel in MULTI_LABEL_SUFFIX_SECOND_LEVEL && labels.size >= 3) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    private val MULTI_LABEL_SUFFIX_SECOND_LEVEL = setOf(
        "co", "com", "org", "gov", "ac", "net", "edu",
    )

    private suspend fun fetchTlsDetails(host: String): TlsDetails? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(6_000) {
            runCatching {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(host, 443) as SSLSocket).use { socket ->
                    socket.soTimeout = 6_000
                    socket.startHandshake()
                    val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: return@runCatching TlsDetails(trusted = false, issuer = null, subject = null, validFrom = null, validTo = null, daysUntilExpiry = null, selfSigned = false, error = "No certificate presented")
                    val daysLeft = ChronoUnit.DAYS.between(Instant.now(), cert.notAfter.toInstant())
                    TlsDetails(
                        trusted = true,
                        issuer = cert.issuerX500Principal.name,
                        subject = cert.subjectX500Principal.name,
                        validFrom = ISO_UTC.format(cert.notBefore),
                        validTo = ISO_UTC.format(cert.notAfter),
                        daysUntilExpiry = daysLeft,
                        selfSigned = cert.issuerX500Principal == cert.subjectX500Principal,
                    )
                }
            }.getOrElse { e ->
                when (e) {
                    is SSLException -> TlsDetails(trusted = false, issuer = null, subject = null, validFrom = null, validTo = null, daysUntilExpiry = null, selfSigned = false, error = "Certificate not trusted: ${e.message ?: "validation failed"}")
                    else -> null
                }
            }
        }
    }

    /**
     * Real DNSSEC validation status via Google's DoH resolver — see [DnsApi]'s doc comment for why
     * asking an already-validating resolver is the honest approach here, not a claim that this app
     * validates DNSSEC signatures itself. The absence of DNSSEC is common (most consumer sites still
     * don't sign their zones) and is deliberately never treated as a red flag on its own — only its
     * presence is used as positive corroboration; see [ThreatIntelRepository]'s `dnsSecuritySignal`.
     */
    private suspend fun fetchDnssecStatus(host: String): DnssecStatus? = withTimeoutOrNull(6_000) {
        runCatching {
            val answer = dns.resolve(host, "A")
            val nsAnswer = runCatching { dns.resolve(host, "NS") }.getOrNull()
            DnssecStatus(
                validated = answer.Status == 0 && answer.AD,
                nameservers = nsAnswer?.Answer?.mapNotNull { it.data?.trimEnd('.') }.orEmpty(),
            )
        }.getOrNull()
    }

    private suspend fun fetchHttpTrace(url: String): HttpTrace? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) {
            runCatching {
                val client = NetworkModule.okHttpClient.newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .callTimeout(8, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).head().build()
                client.newCall(request).execute().use { response ->
                    val hosts = mutableListOf<String>()
                    var hop: okhttp3.Response? = response.priorResponse
                    while (hop != null) {
                        hosts += hop.request.url.host
                        hop = hop.priorResponse
                    }
                    HttpTrace(
                        finalUrl = response.request.url.toString(),
                        statusCode = response.code,
                        serverHeader = response.header("Server"),
                        redirectCount = hosts.size,
                        redirectHosts = hosts.reversed(),
                        usedHttps = response.request.url.isHttps,
                    )
                }
            }.getOrElse { e -> HttpTrace(null, null, null, 0, emptyList(), false, error = e.message ?: "Could not connect") }
        }
    }
}
