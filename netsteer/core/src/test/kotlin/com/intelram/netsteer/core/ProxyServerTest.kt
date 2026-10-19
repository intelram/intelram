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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Drives [ProxyServer] exactly like hev-socks5-tunnel does (user/pass auth,
 * CONNECT with domain or IP, FWD_UDP framing) over two loopback "links".
 */
class ProxyServerTest {
    private val loop = InetAddress.getLoopbackAddress()

    /** Loopback stand-in for a Wi-Fi or cellular Network. */
    private class FakeLink(override val id: String) : Uplink {
        @Volatile var broken = false
        val connects = AtomicInteger()
        override fun resolve(host: String): List<InetAddress> =
            if (host.endsWith(".test")) listOf(InetAddress.getLoopbackAddress()) else throw java.net.UnknownHostException(host)
        override fun connect(address: InetSocketAddress, timeoutMs: Int): Socket {
            connects.incrementAndGet()
            if (broken) throw IOException("connect failed: ENETUNREACH (Network is unreachable)")
            return Socket().apply { connect(address, timeoutMs) }
        }
        override fun datagramSocket() = DatagramSocket(0, InetAddress.getLoopbackAddress())
    }

    private val wifi = FakeLink("wifi")
    private val cell = FakeLink("cell")
    private val links = CopyOnWriteArrayList<Uplink>(listOf(wifi, cell))
    private val meter = TrafficMeter()
    private val scheduler = LinkScheduler()
    private lateinit var proxy: ProxyServer
    private val bg = Executors.newCachedThreadPool()
    private lateinit var echo: ServerSocket

    @Before fun setUp() {
        proxy = ProxyServer({ links }, scheduler, meter, "user", "pw", isBlocked = { false })
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
        proxy.close()
        echo.close()
        bg.shutdownNow()
    }

    private fun handshake(s: Socket, user: String = "user", pw: String = "pw"): Boolean {
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2)); o.flush()
        assertArrayEquals(byteArrayOf(5, 2), ByteArray(2).also { i.readFully(it) })
        o.write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() + byteArrayOf(pw.length.toByte()) + pw.toByteArray())
        o.flush()
        val r = ByteArray(2).also { i.readFully(it) }
        return r[1].toInt() == 0
    }

    /** Opens a proxied TCP connection; returns the socket and the SOCKS reply code. */
    private fun connect(host: String?, ip: InetAddress?, port: Int): Pair<Socket, Int> {
        val s = Socket(loop, proxy.port).apply { soTimeout = 10_000 }
        assertTrue(handshake(s))
        val addr = if (host != null) byteArrayOf(3, host.length.toByte()) + host.toByteArray() else byteArrayOf(1) + ip!!.address
        s.getOutputStream().write(byteArrayOf(5, 1, 0) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val rep = ByteArray(10).also { DataInputStream(s.getInputStream()).readFully(it) }
        return s to rep[1].toInt()
    }

    @Test fun relaysByNameAndCountsBytesOnTheChosenLink() {
        val (s, rep) = connect("echo.test", null, echo.localPort)
        assertEquals(0, rep)
        val data = Random(1).nextBytes(1 shl 20)
        bg.execute { s.getOutputStream().write(data); s.shutdownOutput() }
        val back = s.getInputStream().readBytes() // EOF arrives only if half-close propagates both ways
        assertArrayEquals(data, back)
        s.close()
        waitFor { proxy.activeConnections == 0 }
        val rates = meter.sample(System.currentTimeMillis())
        val used = rates.filterValues { it.rxTotal > 0 }
        assertEquals(1, used.size)
        assertEquals(data.size.toLong(), used.values.single().rxTotal)
        assertEquals(data.size.toLong(), used.values.single().txTotal)
    }

    @Test fun parallelConnectionsUseBothLinks() {
        val socks = (1..10).map { connect(null, loop, echo.localPort).also { assertEquals(0, it.second) }.first }
        waitFor { scheduler.active("wifi") + scheduler.active("cell") == 10 }
        assertEquals(5, scheduler.active("wifi"))
        assertEquals(5, scheduler.active("cell"))
        // Every connection still works end-to-end.
        socks.forEachIndexed { n, s ->
            s.getOutputStream().write(byteArrayOf(n.toByte()))
            assertEquals(n, s.getInputStream().read())
        }
        socks.forEach { it.close() }
        waitFor { scheduler.active("wifi") + scheduler.active("cell") == 0 }
    }

    @Test fun failsOverToTheOtherLinkWhenOneIsDown() {
        wifi.broken = true
        repeat(6) {
            val (s, rep) = connect("echo.test", null, echo.localPort)
            assertEquals(0, rep)
            s.getOutputStream().write(42)
            assertEquals(42, s.getInputStream().read())
            s.close()
        }
        // wifi was tried at most twice before cooling down; every connection succeeded on cell.
        assertTrue("wifi tries=${wifi.connects.get()}", wifi.connects.get() <= 2)
        assertEquals(6, cell.connects.get())
    }

    @Test fun reportsUnreachableWhenEveryLinkIsDown() {
        wifi.broken = true; cell.broken = true
        val (s, rep) = connect("echo.test", null, echo.localPort)
        assertEquals(Socks5.REP_HOST_UNREACHABLE, rep)
        s.close()
        waitFor { proxy.failedConnections == 1L }
    }

    @Test fun refusedDestinationIsNotRetriedOnAnotherLink() {
        val closedPort = ServerSocket(0, 1, loop).use { it.localPort }
        val (s, rep) = connect(null, loop, closedPort)
        assertEquals(Socks5.REP_CONNECTION_REFUSED, rep)
        assertEquals(1, wifi.connects.get() + cell.connects.get())
        s.close()
    }

    @Test fun noLinksMeansNetworkUnreachable() {
        links.clear()
        val (s, rep) = connect("echo.test", null, echo.localPort)
        assertEquals(Socks5.REP_NETWORK_UNREACHABLE, rep)
        s.close()
    }

    @Test fun wrongPasswordIsRejected() {
        Socket(loop, proxy.port).use { s ->
            s.soTimeout = 5_000
            assertTrue(!handshake(s, pw = "guess"))
            assertEquals(-1, s.getInputStream().read()) // server hung up
        }
    }

    @Test fun ownTunnelRangeIsNeverDialled() {
        assertTrue(ProxyServer.isBlockedDestination(InetAddress.getByName("198.18.0.2")))
        assertTrue(ProxyServer.isBlockedDestination(InetAddress.getByName("198.19.4.4")))
        assertTrue(ProxyServer.isBlockedDestination(InetAddress.getByName("127.0.0.1")))
        assertTrue(!ProxyServer.isBlockedDestination(InetAddress.getByName("198.20.0.1")))
        assertTrue(!ProxyServer.isBlockedDestination(InetAddress.getByName("8.8.8.8")))

        val strict = ProxyServer({ links }, LinkScheduler(), TrafficMeter(), "user", "pw")
        strict.start()
        try {
            Socket(loop, strict.port).use { s ->
                s.soTimeout = 5_000
                assertTrue(handshake(s))
                s.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 198.toByte(), 18, 0, 2, 0x03, 0x55))
                val rep = ByteArray(10).also { DataInputStream(s.getInputStream()).readFully(it) }
                assertEquals(Socks5.REP_NOT_ALLOWED, rep[1].toInt())
            }
            assertEquals(0, wifi.connects.get() + cell.connects.get())
        } finally {
            strict.close()
        }
    }

    @Test fun refusedMessageDetection() {
        assertTrue(ProxyServer.isRefused(java.net.ConnectException("failed to connect to /1.2.3.4 (port 80) after 7000ms: isConnected failed: ECONNREFUSED (Connection refused)")))
        assertTrue(ProxyServer.isRefused(java.net.ConnectException("Connection refused")))
        assertTrue(!ProxyServer.isRefused(java.net.ConnectException("failed to connect: connect failed: ENETUNREACH (Network is unreachable)")))
        assertTrue(!ProxyServer.isRefused(java.net.ConnectException()))
    }

    @Test fun udpInTcpRoundTripWithIpAndNameDestinations() {
        val udpEcho = DatagramSocket(0, loop)
        bg.execute {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (true) { p.length = buf.size; udpEcho.receive(p); udpEcho.send(DatagramPacket(buf, p.length, p.socketAddress)) }
            } catch (_: IOException) {
            }
        }
        try {
            Socket(loop, proxy.port).use { s ->
                s.soTimeout = 5_000
                assertTrue(handshake(s))
                val o = s.getOutputStream()
                val i = DataInputStream(s.getInputStream())
                o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)) // FWD_UDP, 0.0.0.0:0 as hev sends
                assertEquals(0, ByteArray(10).also { i.readFully(it) }[1].toInt())

                val port = udpEcho.localPort
                val p1 = "ping-by-ip".toByteArray()
                Socks5.writeUdpFrame(o, loop, port, p1, 0, p1.size)
                val f1 = Socks5.readUdpFrame(i)!!
                assertArrayEquals(p1, f1.payload)
                assertEquals(Socks5.Dest.Ip(loop, port), f1.dest)

                val name = "echo.test".toByteArray()
                val p2 = "ping-by-name".toByteArray()
                o.write(byteArrayOf(0, p2.size.toByte(), (3 + 2 + name.size + 2).toByte(), 3, name.size.toByte()) + name +
                    byteArrayOf((port shr 8).toByte(), port.toByte()) + p2)
                val f2 = Socks5.readUdpFrame(i)!!
                assertArrayEquals(p2, f2.payload)
            }
            waitFor { proxy.activeConnections == 0 }
            val total = meter.sample(System.currentTimeMillis()).values.sumOf { it.txTotal }
            assertEquals(("ping-by-ip".length + "ping-by-name".length).toLong(), total)
        } finally {
            udpEcho.close()
        }
    }

    @Test fun closeTearsDownLiveConnections() {
        val (s, rep) = connect(null, loop, echo.localPort)
        assertEquals(0, rep)
        val done = CountDownLatch(1)
        bg.execute { runCatching { s.getInputStream().read() }; done.countDown() }
        proxy.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        s.close()
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(20)
        }
    }
}
