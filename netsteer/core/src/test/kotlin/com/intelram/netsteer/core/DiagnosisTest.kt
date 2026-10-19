package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosisTest {
    private val ok = ProbeResult(1, Transport.WIFI, true, false, false, true, 10, true, 20, 204, null, 80)

    @Test fun healthy() = assertEquals(Verdict.HEALTHY, DiagnosisClassifier.classify(ok).verdict)

    @Test fun layeredFailuresAreOrdered() {
        assertEquals(Verdict.BLOCKED_BY_POLICY, DiagnosisClassifier.classify(ok.copy(blocked = true, dnsOk = false)).verdict)
        assertEquals(Verdict.CAPTIVE_PORTAL, DiagnosisClassifier.classify(ok.copy(osCaptivePortal = true)).verdict)
        assertEquals(Verdict.CAPTIVE_PORTAL, DiagnosisClassifier.classify(ok.copy(httpStatus = 302)).verdict)
        assertEquals(Verdict.DNS_FAILURE, DiagnosisClassifier.classify(ok.copy(dnsOk = false, tcpOk = false)).verdict)
        assertEquals(Verdict.NO_ROUTE, DiagnosisClassifier.classify(ok.copy(tcpOk = false)).verdict)
        assertEquals(Verdict.TLS_OR_HTTP_INTERCEPTED, DiagnosisClassifier.classify(ok.copy(httpStatus = null, httpError = "SSLHandshakeException")).verdict)
        assertEquals(Verdict.UNEXPECTED_HTTP, DiagnosisClassifier.classify(ok.copy(httpStatus = 503)).verdict)
        assertEquals(Verdict.NOT_VALIDATED_BUT_WORKING, DiagnosisClassifier.classify(ok.copy(osValidated = false)).verdict)
        assertEquals(Verdict.HIGH_LATENCY, DiagnosisClassifier.classify(ok.copy(httpMs = 900)).verdict)
    }
}
