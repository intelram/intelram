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
import java.time.OffsetDateTime
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
    /** Every name the certificate is valid for — what actually decides whether it matches this site. */
    val subjectAltNames: List<String> = emptyList(),
    val signatureAlgorithm: String? = null,
    val error: String? = null,
)

data class DomainRegistration(
    val registrar: String?,
    val registeredOn: String?,
    val expiresOn: String?,
    val lastChangedOn: String? = null,
    val ageDays: Long?,
    /** Days until the registration lapses — negative means it has already expired. */
    val daysUntilExpiry: Long? = null,
    /** Registry status codes, e.g. "client transfer prohibited". */
    val status: List<String> = emptyList(),
    val nameservers: List<String> = emptyList(),
    /** The registry's own DNSSEC answer, when it publishes one. */
    val delegationSigned: Boolean? = null,
) {
    /** "28 years, 4 months" rather than a raw day count — what the user actually asked to see. */
    val ageHuman: String?
        get() {
            val days = ageDays ?: return null
            if (days < 0) return null
            val years = days / 365
            val months = (days % 365) / 30
            return when {
                years > 0 && months > 0 -> "$years year${plural(years)}, $months month${plural(months)}"
                years > 0 -> "$years year${plural(years)}"
                months > 0 -> "$months month${plural(months)}"
                else -> "$days day${plural(days)}"
            }
        }

    private fun plural(n: Long) = if (n == 1L) "" else "s"
}

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

/** Whether this domain's DNS answers are DNSSEC-signed and validated — see [DnsApi]'s doc. */
data class DnssecStatus(val validated: Boolean, val nameservers: List<String>)

/** One published DNS record set, e.g. all the MX records, shown verbatim in the technical details. */
data class DnsRecordSet(val type: String, val records: List<String>)

/**
 * Cloudflare's malware/phishing verdict for this domain — see [ThreatDnsApi]'s doc for how the
 * sinkhole answer was verified against Cloudflare's own documented test domains.
 */
data class ThreatDnsResult(val blocked: Boolean, val checked: Boolean)

data class TechnicalDetails(
    val host: String,
    val resolvedIps: List<String>,
    val ipIntel: IpIntel?,
    val domain: DomainRegistration?,
    val tls: TlsDetails?,
    val http: HttpTrace?,
    val dnssec: DnssecStatus?,
    /** A/AAAA/MX/NS/TXT/CNAME/SOA, in that order, omitting the ones the domain doesn't publish. */
    val dnsRecords: List<DnsRecordSet> = emptyList(),
    val threatDns: ThreatDnsResult? = null,
)

/**
 * Everything about a URL/domain that's independently verifiable, on top of the reputation-list
 * signals in [ThreatIntelRepository] — README's "show the technical details as proof": who owns
 * the IP, when the domain was registered and when it expires, every DNS record set it publishes,
 * whether its TLS certificate is real and trusted, whether a major security resolver blocks it, and
 * what actually happens when you request it (redirect chain, real server, real status code).
 * Every field here comes from a live lookup or a live connection made right now, never a guess.
 */
object TechnicalInspector {

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    /** The record types worth showing a user, in the order they're displayed. */
    private val RECORD_TYPES = listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA")

    private val rdap by lazy { NetworkModule.create<RdapApi>(RdapApi.BASE_URL) }
    private val ipInfo by lazy { NetworkModule.create<IpInfoApi>(IpInfoApi.BASE_URL) }
    private val dns by lazy { NetworkModule.create<DnsApi>(DnsApi.BASE_URL) }
    private val threatDns by lazy { NetworkModule.create<ThreatDnsApi>(ThreatDnsApi.BASE_URL) }

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
        val recordsDeferred = async { fetchDnsRecords(host) }
        val threatDeferred = async { fetchThreatDnsVerdict(host) }

        val registration = domainDeferred.await()
        val dnssec = dnssecDeferred.await()
        TechnicalDetails(
            host = host,
            resolvedIps = ips,
            ipIntel = ipDeferred.await(),
            domain = registration,
            tls = tlsDeferred.await(),
            http = httpDeferred.await(),
            // The registry's own answer wins over the resolver's per-query AD flag when both exist.
            dnssec = registration?.delegationSigned?.let { signed ->
                DnssecStatus(validated = signed, nameservers = dnssec?.nameservers ?: registration.nameservers)
            } ?: dnssec,
            dnsRecords = recordsDeferred.await(),
            threatDns = threatDeferred.await(),
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

    private suspend fun fetchDomainRegistration(host: String): DomainRegistration? = withTimeoutOrNull(10_000) {
        val registrable = BrandRegistry.registrableDomain(host).takeIf { it.contains('.') } ?: return@withTimeoutOrNull null
        val response = runCatching { rdap.lookup(registrable) }.getOrNull() ?: return@withTimeoutOrNull null
        val registeredOn = response.registeredOn
        val now = Instant.now()
        val ageDays = registeredOn?.let { parseRdapInstant(it) }?.let { ChronoUnit.DAYS.between(it, now) }
        val daysLeft = response.expiresOn?.let { parseRdapInstant(it) }?.let { ChronoUnit.DAYS.between(now, it) }
        DomainRegistration(
            registrar = response.registrarName,
            registeredOn = registeredOn,
            expiresOn = response.expiresOn,
            lastChangedOn = response.lastChanged,
            ageDays = ageDays,
            daysUntilExpiry = daysLeft,
            status = response.status,
            nameservers = response.nameserverNames,
            delegationSigned = response.secureDNS?.delegationSigned,
        )
    }

    /**
     * RDAP dates are ISO-8601 but registries differ on whether they use `Z` or a numeric offset
     * (`1997-09-15T04:00:00-04:00`). `Instant.parse` rejects the offset form outright, which
     * silently cost the domain age — and therefore the strongest positive signal there is — at
     * every registry that formats dates that way.
     */
    internal fun parseRdapInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()

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

    /** Every record set the domain publishes — the "show all the record sets" the user asked for. */
    private suspend fun fetchDnsRecords(host: String): List<DnsRecordSet> = coroutineScope {
        RECORD_TYPES.map { type ->
            async {
                val answer = withTimeoutOrNull(6_000) { runCatching { dns.resolve(host, type) }.getOrNull() }
                val records = answer?.Answer.orEmpty().mapNotNull { it.data?.trim() }.filter { it.isNotBlank() }
                if (records.isEmpty()) null else DnsRecordSet(type, records)
            }
        }.mapNotNull { it.await() }
    }

    private suspend fun fetchThreatDnsVerdict(host: String): ThreatDnsResult? = withTimeoutOrNull(6_000) {
        val response = runCatching { threatDns.resolve(host, "A") }.getOrNull()
            ?: return@withTimeoutOrNull ThreatDnsResult(blocked = false, checked = false)
        ThreatDnsResult(
            blocked = ThreatDnsVerdictReader.isBlocked(response),
            checked = ThreatDnsVerdictReader.isBlocked(response) || ThreatDnsVerdictReader.isClean(response),
        )
    }

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
                        subjectAltNames = runCatching {
                            cert.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1)?.toString() }
                        }.getOrDefault(emptyList()),
                        signatureAlgorithm = cert.sigAlgName,
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
