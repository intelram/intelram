package com.intelram.netsteer.core

/**
 * Raw result of one layered probe against one network
 * (link -> DNS -> TCP -> TLS/HTTP), produced by the app layer.
 */
data class ProbeResult(
    val networkId: Long,
    val transport: Transport,
    val osValidated: Boolean,
    val osCaptivePortal: Boolean,
    val blocked: Boolean,
    val dnsOk: Boolean,
    val dnsMs: Int?,
    val tcpOk: Boolean,
    val tcpMs: Int?,
    /** null when the HTTPS request did not complete (TLS or I/O error). */
    val httpStatus: Int?,
    val httpError: String?,
    val httpMs: Int?,
)

enum class Verdict {
    HEALTHY,
    HIGH_LATENCY,
    BLOCKED_BY_POLICY,
    CAPTIVE_PORTAL,
    DNS_FAILURE,
    NO_ROUTE,
    TLS_OR_HTTP_INTERCEPTED,
    UNEXPECTED_HTTP,
    NOT_VALIDATED_BUT_WORKING,
}

data class Diagnosis(val verdict: Verdict, val detail: String, val remedy: String)

object DiagnosisClassifier {
    const val HIGH_LATENCY_MS = 300

    fun classify(r: ProbeResult): Diagnosis {
        val name = r.transport.name.lowercase()
        if (r.blocked) return Diagnosis(
            Verdict.BLOCKED_BY_POLICY,
            "Android is blocking this app on $name (Data Saver or background data restriction).",
            "Allow unrestricted data for this app, or disable Data Saver.",
        )
        if (r.osCaptivePortal || r.httpStatus in 300..399 || r.httpStatus == 200) return Diagnosis(
            Verdict.CAPTIVE_PORTAL,
            "A sign-in page is intercepting traffic on $name (expected HTTP 204, got ${r.httpStatus ?: "portal flag"}).",
            "Open the Wi-Fi sign-in notification and authenticate.",
        )
        if (!r.dnsOk) return Diagnosis(
            Verdict.DNS_FAILURE,
            "Name resolution failed on $name.",
            if (r.tcpOk) "DNS server is broken but IP routing works: check Private DNS setting or the network's resolver."
            else "Link may have no upstream; toggle the network or move closer to the access point / tower.",
        )
        if (!r.tcpOk) return Diagnosis(
            Verdict.NO_ROUTE,
            "DNS answered but TCP/443 to the probe host failed on $name.",
            "Upstream is down or filtered. Fail over to the other network.",
        )
        if (r.httpStatus == null) return Diagnosis(
            Verdict.TLS_OR_HTTP_INTERCEPTED,
            "TCP connects but HTTPS fails on $name (${r.httpError ?: "unknown error"}).",
            "Possible captive portal, TLS-intercepting proxy or firewall. Do not trust this network for sensitive traffic.",
        )
        if (r.httpStatus != 204) return Diagnosis(
            Verdict.UNEXPECTED_HTTP,
            "Probe returned HTTP ${r.httpStatus} on $name.",
            "A middlebox is rewriting responses. Treat as degraded.",
        )
        val worst = listOfNotNull(r.tcpMs, r.httpMs).maxOrNull() ?: 0
        if (!r.osValidated) return Diagnosis(
            Verdict.NOT_VALIDATED_BUT_WORKING,
            "Our probe succeeded but Android has not marked $name validated yet.",
            "Usually transient; wait for revalidation or call reportNetworkConnectivity().",
        )
        if (worst > HIGH_LATENCY_MS) return Diagnosis(
            Verdict.HIGH_LATENCY,
            "Working but slow on $name (worst step ${worst} ms).",
            "Expect poor interactive performance; failover policy will prefer the other link if it is healthier.",
        )
        return Diagnosis(Verdict.HEALTHY, "$name healthy: DNS ${r.dnsMs} ms, TCP ${r.tcpMs} ms, HTTPS ${r.httpMs} ms.", "None.")
    }
}
