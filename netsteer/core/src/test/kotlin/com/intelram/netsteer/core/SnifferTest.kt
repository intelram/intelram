package com.intelram.netsteer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import kotlin.random.Random

class SnifferTest {
    /** A real ClientHello produced by the JDK's TLS stack. */
    private fun clientHello(host: String?, protocols: Array<String> = arrayOf("TLSv1.3", "TLSv1.2")): ByteArray {
        val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine(host ?: "1.2.3.4", 443)
        engine.useClientMode = true
        engine.enabledProtocols = protocols
        val p = engine.sslParameters
        p.serverNames = if (host != null) listOf(SNIHostName(host)) else emptyList()
        engine.sslParameters = p
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.beginHandshake()
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test fun extractsSniFromRealClientHellos() {
        for (protos in listOf(arrayOf("TLSv1.3", "TLSv1.2"), arrayOf("TLSv1.2"))) {
            val ch = clientHello("ads.example.com", protos)
            assertEquals("ads.example.com", Sniffer.tlsSni(ch, ch.size))
            assertEquals(ch.size, Sniffer.tlsRecordTotal(ch, ch.size))
        }
    }

    @Test fun noSniOrIncompleteRecordGivesNull() {
        val ch = clientHello(null)
        assertNull(Sniffer.tlsSni(ch, ch.size))
        val full = clientHello("x.example.org")
        assertEquals(5, Sniffer.tlsRecordTotal(full, 3)) // need the header first
        assertNull(Sniffer.tlsSni(full, 40)) // truncated: never guesses
        assertNull(Sniffer.tlsRecordTotal("GET".toByteArray(), 3))
    }

    @Test fun neverThrowsOnGarbage() {
        val rnd = Random(3)
        repeat(20_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 600))
            if (b.isNotEmpty() && rnd.nextBoolean()) b[0] = 0x16
            Sniffer.tlsSni(b, b.size)
            Sniffer.httpHost(b, b.size)
            Dns.queryName(b, b.size)
        }
        // Mutated real ClientHellos
        val ch = clientHello("mutate.example.com")
        repeat(5_000) {
            val m = ch.copyOf()
            m[rnd.nextInt(5, m.size)] = rnd.nextInt(256).toByte()
            Sniffer.tlsSni(m, m.size)
        }
    }

    @Test fun httpHost() {
        val req = "GET /x HTTP/1.1\r\nUser-Agent: t\r\nhost: Ads.Example.com:8080\r\n\r\n".toByteArray()
        assertEquals("Ads.Example.com", Sniffer.httpHost(req, req.size))
        assertEquals(true, Sniffer.httpHeaderComplete(req, req.size))
        assertEquals(false, Sniffer.httpHeaderComplete(req, 20))
        val v6 = "GET / HTTP/1.0\r\nHost: [2001:db8::1]:80\r\n\r\n".toByteArray()
        assertEquals("2001:db8::1", Sniffer.httpHost(v6, v6.size))
        val notHttp = byteArrayOf(0x16, 3, 1, 0, 5)
        assertNull(Sniffer.httpHost(notHttp, notHttp.size))
        assertEquals(true, Sniffer.httpHeaderComplete(notHttp, notHttp.size))
    }

    private fun query(name: String, type: Int = 1): ByteArray {
        val q = java.io.ByteArrayOutputStream()
        q.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { q.write(it.length); q.write(it.toByteArray()) }
        q.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
        return q.toByteArray()
    }

    @Test fun dnsQueryNameAndNxdomain() {
        val q = query("ads.example.com")
        assertEquals("ads.example.com", Dns.queryName(q, q.size))
        val r = Dns.nxdomain(q, q.size)!!
        assertArrayEquals(byteArrayOf(0x12, 0x34), r.copyOfRange(0, 2)) // same id
        assertEquals(0x81, r[2].toInt() and 0xFF) // QR=1, RD kept
        assertEquals(0x83, r[3].toInt() and 0xFF) // RA=1, NXDOMAIN
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0), r.copyOfRange(4, 12))
        assertArrayEquals(q.copyOfRange(12, q.size), r.copyOfRange(12, r.size)) // question echoed
        val resp = q.copyOf().also { it[2] = 0x81.toByte() }
        assertNull(Dns.queryName(resp, resp.size)) // responses are ignored
    }
}
