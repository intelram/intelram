package com.intelram.netsteer.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

/** Ad blocking inside [ProxyServer], driven the way hev drives it. */
class ProxyFilterTest {
    private val loop = InetAddress.getLoopbackAddress()
    private val connects = AtomicInteger()
    private val link = object : Uplink {
        override val id = "wifi"
        override fun resolve(host: String) = listOf(InetAddress.getLoopbackAddress())
        override fun connect(address: InetSocketAddress, timeoutMs: Int): Socket {
            connects.incrementAndGet()
            return Socket().apply { connect(address, timeoutMs) }
        }
        override fun datagramSocket() = DatagramSocket(0, InetAddress.getLoopbackAddress())
    }
    private val blockedHosts = CopyOnWriteArrayList<String>()
    private val f = DomainFilter.Builder().apply { addList("||ads.example.com^\n||tracker.test^") }.build()
    @Volatile private var filterOn = true
    private lateinit var proxy: ProxyServer
    private lateinit var echo: ServerSocket
    private val bg = Executors.newCachedThreadPool()

    @Before fun setUp() {
        proxy = ProxyServer(
            { listOf(link) }, LinkScheduler(), TrafficMeter(), "u", "p",
            isBlocked = { false }, filter = { if (filterOn) f else null }, onBlocked = { blockedHosts += it },
            sniffTimeoutMs = 500,
        )
        proxy.start()
        echo = ServerSocket(0, 50, loop)
        bg.execute {
            while (true) {
                val s = try { echo.accept() } catch (_: IOException) { break }
                bg.execute { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
    }

    @After fun tearDown() {
        proxy.close(); echo.close(); bg.shutdownNow()
    }

    private fun open(addr: ByteArray, port: Int): Pair<Socket, Int> {
        val s = Socket(loop, proxy.port).apply { soTimeout = 5_000 }
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2)); i.readFully(ByteArray(2))
        o.write(byteArrayOf(1, 1, 'u'.code.toByte(), 1, 'p'.code.toByte())); i.readFully(ByteArray(2))
        o.write(byteArrayOf(5, 1, 0) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val rep = ByteArray(10).also { i.readFully(it) }
        return s to rep[1].toInt()
    }

    private fun name(h: String) = byteArrayOf(3, h.length.toByte()) + h.toByteArray()
    private fun ip() = byteArrayOf(1) + loop.address

    /** Proxy that forwards IP:443/80 to our echo server, so a sniffed connection can be checked end to end. */
    private fun echoProxyFor(port: Int): ProxyServer {
        val fwd = object : Uplink by link {
            override fun connect(address: InetSocketAddress, timeoutMs: Int) =
                link.connect(InetSocketAddress(address.address, echo.localPort), timeoutMs)
        }
        return ProxyServer(
            { listOf(fwd) }, LinkScheduler(), TrafficMeter(), "u", "p",
            isBlocked = { false }, filter = { f }, onBlocked = { blockedHosts += it }, sniffTimeoutMs = 500,
        )
    }

    private fun clientHello(host: String): ByteArray {
        val e = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine(host, 443)
        e.useClientMode = true
        e.sslParameters = e.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        val out = ByteBuffer.allocate(e.session.packetBufferSize)
        e.beginHandshake(); e.wrap(ByteBuffer.allocate(0), out); out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test fun blockedNameIsRefusedWithoutConnecting() {
        val (s, rep) = open(name("img.ads.example.com"), echo.localPort)
        assertEquals(Socks5.REP_NOT_ALLOWED, rep)
        s.close()
        assertEquals(0, connects.get())
        assertEquals(listOf("img.ads.example.com"), blockedHosts)
    }

    @Test fun allowedNamePassesAndFilterCanBeTurnedOffLive() {
        val (s, rep) = open(name("www.example.com"), echo.localPort)
        assertEquals(0, rep)
        s.getOutputStream().write(7); assertEquals(7, s.getInputStream().read()); s.close()
        filterOn = false
        val (s2, rep2) = open(name("ads.example.com"), echo.localPort)
        assertEquals(0, rep2)
        s2.close()
    }

    @Test fun tlsToBareIpIsBlockedBySni() {
        val p = echoProxyFor(443).also { it.start() }
        try {
            val s = Socket(loop, p.port).apply { soTimeout = 5_000 }
            hevConnect(s, 443)
            s.getOutputStream().write(clientHello("cdn.tracker.test"))
            assertEquals(-1, s.getInputStream().read()) // closed, nothing forwarded
            assertEquals(0, connects.get())
            assertEquals(listOf("cdn.tracker.test"), blockedHosts)
        } finally {
            p.close()
        }
    }

    @Test fun allowedTlsIsForwardedByteForByte() {
        val p = echoProxyFor(443).also { it.start() }
        try {
            val s = Socket(loop, p.port).apply { soTimeout = 5_000 }
            hevConnect(s, 443)
            val ch = clientHello("www.example.org")
            s.getOutputStream().write(ch)
            val back = ByteArray(ch.size).also { DataInputStream(s.getInputStream()).readFully(it) }
            assertArrayEquals(ch, back)
            s.getOutputStream().write(byteArrayOf(1, 2, 3)) // rest of the stream still flows
            assertArrayEquals(byteArrayOf(1, 2, 3), ByteArray(3).also { DataInputStream(s.getInputStream()).readFully(it) })
            s.close()
        } finally {
            p.close()
        }
    }

    @Test fun httpToBareIpIsBlockedByHostHeader() {
        val p = echoProxyFor(80).also { it.start() }
        try {
            val s = Socket(loop, p.port).apply { soTimeout = 5_000 }
            hevConnect(s, 80)
            s.getOutputStream().write("GET /pixel HTTP/1.1\r\nHost: ads.example.com\r\n\r\n".toByteArray())
            assertEquals(-1, s.getInputStream().read())
            assertEquals(listOf("ads.example.com"), blockedHosts)
        } finally {
            p.close()
        }
    }

    @Test fun serverSpeaksFirstProtocolOn443StillWorks() {
        val p = echoProxyFor(443).also { it.start() }
        try {
            val s = Socket(loop, p.port).apply { soTimeout = 5_000 }
            hevConnect(s, 443)
            Thread.sleep(700) // client stays silent past the sniff timeout
            s.getOutputStream().write(42)
            assertEquals(42, s.getInputStream().read())
            s.close()
        } finally {
            p.close()
        }
    }

    private fun hevConnect(s: Socket, port: Int) {
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2)); i.readFully(ByteArray(2))
        o.write(byteArrayOf(1, 1, 'u'.code.toByte(), 1, 'p'.code.toByte())); i.readFully(ByteArray(2))
        o.write(byteArrayOf(5, 1, 0) + ip() + byteArrayOf((port shr 8).toByte(), port.toByte()))
        assertEquals(0, ByteArray(10).also { i.readFully(it) }[1].toInt())
    }

    @Test fun udpBlockedNameDroppedAndDnsAnsweredNxdomain() {
        val dnsServer = DatagramSocket(0, loop) // must never receive the blocked query
        val received = AtomicInteger()
        bg.execute {
            val b = ByteArray(512)
            try { while (true) { dnsServer.receive(DatagramPacket(b, b.size)); received.incrementAndGet() } } catch (_: IOException) {}
        }
        try {
            Socket(loop, proxy.port).use { s ->
                s.soTimeout = 5_000
                val o = s.getOutputStream()
                val i = DataInputStream(s.getInputStream())
                o.write(byteArrayOf(5, 1, 2)); i.readFully(ByteArray(2))
                o.write(byteArrayOf(1, 1, 'u'.code.toByte(), 1, 'p'.code.toByte())); i.readFully(ByteArray(2))
                o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); i.readFully(ByteArray(10))

                // QUIC-like datagram to a blocked mapped name: dropped.
                val n = "ads.example.com".toByteArray()
                o.write(byteArrayOf(0, 1, (3 + 2 + n.size + 2).toByte(), 3, n.size.toByte()) + n + byteArrayOf(0x01, 0xBB.toByte(), 9))

                // DNS query for a blocked name sent to an IP resolver: answered NXDOMAIN locally.
                val q = byteArrayOf(0x55, 0x66, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3) + "ads".toByteArray() +
                    byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
                val port = dnsServer.localPort
                // The proxy only intercepts port 53; use a forwarding check on 53 via the parser instead of binding 53.
                Socks5.writeUdpFrame(o, loop, 53, q, 0, q.size)
                val r = Socks5.readUdpFrame(i)!!
                assertEquals(Socks5.Dest.Ip(loop, 53), r.dest)
                assertEquals(0x83, r.payload[3].toInt() and 0xFF)
                assertEquals(0x55, r.payload[0].toInt())

                // An allowed query is forwarded normally.
                val ok = q.copyOf().also { it[13] = 'x'.code.toByte() } // "xds.example.com"
                Socks5.writeUdpFrame(o, loop, port, ok, 0, ok.size)
                val deadline = System.currentTimeMillis() + 3_000
                while (received.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
                assertEquals(1, received.get())
            }
            assertTrue(blockedHosts.containsAll(listOf("ads.example.com")))
            assertEquals(2, blockedHosts.size)
        } finally {
            dnsServer.close()
        }
    }
}
