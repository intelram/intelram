package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosisAndChunkTest {
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

    @Test fun chunksCoverRangeExactly() {
        val q = ChunkQueue(totalBytes = 10, chunkBytes = 3)
        val got = generateSequence { q.take() }.toList()
        assertEquals(listOf(0L..2L, 3L..5L, 6L..8L, 9L..9L), got)
        got.forEach { q.complete(it, "w") }
        assertTrue(q.isFinished())
        assertEquals(10L, q.bytesByWorker()["w"])
    }

    @Test fun failedChunkIsRetriedFirst() {
        val q = ChunkQueue(6, 3)
        val a = q.take()!!
        q.giveBack(a)
        assertEquals(a, q.take())
    }

    @Test fun concurrentWorkersCompleteEverythingOnce() {
        val total = 1_000_000L
        val q = ChunkQueue(total, 4_096)
        val pool = Executors.newFixedThreadPool(2)
        listOf("wifi", "cell").forEach { w ->
            pool.execute {
                var n = 0
                while (true) {
                    val r = q.take() ?: break
                    if (w == "cell" && n++ % 5 == 0) q.giveBack(r) else q.complete(r, w)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        // A worker may exit while its peer still has a given-back chunk pending; drain it.
        generateSequence { q.take() }.forEach { q.complete(it, "wifi") }
        assertTrue(q.isFinished())
        assertEquals(total, q.bytesByWorker().values.sum())
        assertNull(q.take())
    }
}
