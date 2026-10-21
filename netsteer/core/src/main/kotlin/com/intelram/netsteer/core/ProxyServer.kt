package com.intelram.netsteer.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One real network (Wi-Fi or cellular). On Android this wraps android.net.Network. */
interface Uplink {
    val id: String
    /** Resolve using this network's DNS. */
    fun resolve(host: String): List<InetAddress>
    /** A TCP socket connected over this network. */
    fun connect(address: InetSocketAddress, timeoutMs: Int): Socket
    /** An unconnected UDP socket bound to this network. */
    fun datagramSocket(): DatagramSocket
}

/**
 * Local SOCKS5 server that hev-socks5-tunnel forwards every TUN flow to.
 * Each incoming flow is re-originated on the link chosen by [scheduler];
 * if that link cannot reach the destination, the next link is tried.
 */
class ProxyServer(
    private val uplinks: () -> List<Uplink>,
    private val scheduler: LinkScheduler,
    private val meter: TrafficMeter,
    private val user: String,
    private val pass: String,
    private val stickyMode: () -> Boolean = { false },
    private val connectTimeoutMs: Int = 7_000,
    private val halfCloseTimeoutMs: Int = 120_000,
    private val isBlocked: (InetAddress) -> Boolean = ::isBlockedDestination,
    /** Ad/tracker filter; null when ad blocking is off. Read per connection, so it can change live. */
    private val filter: () -> HostFilter? = { null },
    private val onBlocked: (String) -> Unit = {},
    private val sniffTimeoutMs: Int = 3_000,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val threadSeq = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        // Small stacks: relays only copy buffers, and there can be hundreds of them.
        Thread(null, r, "netsteer-relay-${threadSeq.incrementAndGet()}", 256 * 1024L).apply { isDaemon = true }
    }
    private val open = ConcurrentHashMap.newKeySet<Closeable>()
    private val live = AtomicInteger()
    private val failures = AtomicLong()
    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: -1
    val activeConnections: Int get() = live.get()
    val failedConnections: Long get() = failures.get()

    fun start(): Int {
        // Explicit IPv4: on Android InetAddress.getLoopbackAddress() is ::1, but hev dials 127.0.0.1.
        val s = ServerSocket(0, 512, LISTEN_ADDRESS)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                track(c)
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    override fun close() {
        server?.close()
        open.forEach { runCatching { it.close() } }
        open.clear()
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        live.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000 // handshake only
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val out = client.getOutputStream()
            if (!Socks5.serverHandshake(input, out, user, pass)) {
                log("SOCKS auth rejected")
                return
            }
            val req = Socks5.readRequest(input)
            client.soTimeout = 0
            when (req.cmd) {
                Socks5.CMD_CONNECT -> handleConnect(client, input, out, req.dest)
                Socks5.CMD_FWD_UDP -> handleUdp(client, input, out)
                else -> Socks5.writeReply(out, Socks5.REP_COMMAND_NOT_SUPPORTED)
            }
        } catch (e: IOException) {
            // Client went away mid-handshake or relay ended abnormally; nothing to report back.
        } catch (e: RuntimeException) {
            log("handler error: $e")
        } finally {
            close(client)
            live.decrementAndGet()
        }
    }

    private fun handleConnect(client: Socket, input: InputStream, out: OutputStream, dest: Socks5.Dest) {
        if (dest is Socks5.Dest.Ip && isBlocked(dest.address)) {
            if (failures.incrementAndGet() <= 20) log("blocked destination in tunnel/local range")
            Socks5.writeReply(out, Socks5.REP_NOT_ALLOWED)
            return
        }
        val f = filter()
        if (f != null && dest is Socks5.Dest.Name && f.blocks(dest.host)) {
            onBlocked(dest.host)
            Socks5.writeReply(out, Socks5.REP_NOT_ALLOWED) // hev resets the app's connection at once
            return
        }

        var head: ByteArray? = null
        var sniffedHost: String? = null
        val sniff = f != null && dest is Socks5.Dest.Ip && (dest.port == 443 || dest.port == 80)
        if (sniff) {
            // The app connected to a bare IP (own DNS, DoH, hardcoded address). Accept, read the
            // first bytes to learn the real host (TLS SNI / HTTP Host), then decide. Nothing is decrypted.
            Socks5.writeReply(out, Socks5.REP_SUCCEEDED)
            val h = readHead(client, input, dest.port == 443)
            head = h
            sniffedHost = if (dest.port == 443) Sniffer.tlsSni(h, h.size) else Sniffer.httpHost(h, h.size)
            if (sniffedHost != null && f!!.blocks(sniffedHost)) {
                onBlocked(sniffedHost)
                return
            }
        }

        val hostKey = sniffedHost ?: when (dest) {
            is Socks5.Dest.Ip -> dest.address.hostAddress
            is Socks5.Dest.Name -> dest.host
        }
        val (remote, link, rep) = connectAny(dest, hostKey)
        if (remote == null || link == null) {
            if (failures.incrementAndGet() <= 20) log("connect failed: rep=$rep")
            if (!sniff) Socks5.writeReply(out, rep) // after a sniff the reply was already sent: just close
            return
        }
        track(remote)
        val c = meter.counters(link.id)
        c.opened.incrementAndGet()
        scheduler.opened(link.id)
        try {
            if (!sniff) Socks5.writeReply(out, Socks5.REP_SUCCEEDED)
            // Replay the sniffed bytes first. (Not via SequenceInputStream: at EOF it closes the
            // socket's input stream, which closes the socket and loses the server's response.)
            if (head != null && head.isNotEmpty()) {
                remote.getOutputStream().write(head)
                c.tx.addAndGet(head.size.toLong())
            }
            relay(client, input, remote, c)
        } finally {
            scheduler.closed(link.id)
            close(remote)
        }
    }

    /** Tries links in scheduler order until one connects. */
    private fun connectAny(dest: Socks5.Dest, hostKey: String): Triple<Socket?, Uplink?, Int> {
        val tried = HashSet<String>()
        var rep = Socks5.REP_NETWORK_UNREACHABLE
        while (true) {
            val links = uplinks()
            val id = scheduler.pick(links.map { LinkScheduler.LinkInfo(it.id, meter.peak(it.id), meter.current(it.id)) }, hostKey, stickyMode(), tried)
                ?: return Triple(null, null, rep)
            val candidate = links.first { it.id == id }
            tried += id
            try {
                val s = connectVia(candidate, dest)
                scheduler.connectSucceeded(id)
                return Triple(s, candidate, Socks5.REP_SUCCEEDED)
            } catch (e: ConnectException) {
                if (isRefused(e)) {
                    // The destination actively refused: the link works, another link won't help.
                    return Triple(null, null, Socks5.REP_CONNECTION_REFUSED)
                }
                rep = Socks5.REP_HOST_UNREACHABLE
                scheduler.connectFailed(id)
            } catch (e: UnknownHostException) {
                rep = Socks5.REP_HOST_UNREACHABLE // may be link DNS trouble: try the other link
            } catch (e: NoRouteToHostException) {
                rep = Socks5.REP_NETWORK_UNREACHABLE
                scheduler.connectFailed(id)
            } catch (e: IOException) {
                rep = Socks5.REP_HOST_UNREACHABLE
                scheduler.connectFailed(id)
            }
        }
    }

    /**
     * Reads the start of the client's stream: one full TLS record, or HTTP headers.
     * Gives up after [sniffTimeoutMs] (server-speaks-first protocols) with whatever arrived.
     */
    private fun readHead(client: Socket, input: InputStream, tls: Boolean): ByteArray {
        val buf = ByteArray(Sniffer.MAX_BYTES)
        var n = 0
        client.soTimeout = sniffTimeoutMs
        try {
            while (n < buf.size) {
                val want = if (tls) Sniffer.tlsRecordTotal(buf, n) ?: if (n > 0) break else buf.size else buf.size
                if (n >= want) break
                if (!tls && n > 0 && Sniffer.httpHeaderComplete(buf, n)) break
                val r = input.read(buf, n, (if (tls) want else buf.size) - n)
                if (r < 0) break
                n += r
            }
        } catch (_: SocketTimeoutException) {
        } finally {
            client.soTimeout = 0
        }
        return buf.copyOf(n)
    }

    private fun connectVia(link: Uplink, dest: Socks5.Dest): Socket {
        val addrs = when (dest) {
            is Socks5.Dest.Ip -> listOf(dest.address)
            is Socks5.Dest.Name -> link.resolve(dest.host).filterNot(isBlocked).ifEmpty {
                throw UnknownHostException(dest.host)
            }
        }
        // Prefer IPv4 (works on every link, including CLAT-only cellular); then IPv6.
        val ordered = (addrs.filterIsInstance<Inet4Address>() + addrs.filterNot { it is Inet4Address }).take(3)
        var last: IOException? = null
        for (a in ordered) {
            try {
                return link.connect(InetSocketAddress(a, dest.port), connectTimeoutMs).apply { tcpNoDelay = true }
            } catch (e: IOException) {
                if (e is ConnectException && isRefused(e)) throw e
                last = e // e.g. ENETUNREACH for an IPv6 address on an IPv4-only link: try the next one
            }
        }
        throw last ?: UnknownHostException("no address")
    }

    /** Copies both directions until both are finished; any error tears down both sockets. */
    private fun relay(client: Socket, clientIn: InputStream, remote: Socket, c: TrafficMeter.Counters) {
        val lastDown = AtomicLong(System.currentTimeMillis())
        val down = pool.submit {
            pump(remote.getInputStream(), client.getOutputStream(), c.rx, lastDown, remote, client)
        }
        pump(clientIn, remote.getOutputStream(), c.tx, AtomicLong(), client, remote)
        // Upstream finished (client closed or half-closed). A peer that never closes its side
        // would pin the downstream thread forever, so end it after a period of inactivity.
        while (true) {
            try {
                down.get(5, TimeUnit.SECONDS)
                return
            } catch (_: TimeoutException) {
                if (System.currentTimeMillis() - lastDown.get() > halfCloseTimeoutMs) {
                    close(remote); close(client)
                }
            } catch (_: Exception) {
                return
            }
        }
    }

    /** One direction. On EOF, half-close the destination; on error, tear down both sockets. */
    private fun pump(src: InputStream, dst: OutputStream, counter: AtomicLong, lastActivity: AtomicLong, srcSock: Socket, dstSock: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                counter.addAndGet(n.toLong())
                lastActivity.set(System.currentTimeMillis())
            }
            runCatching { dstSock.shutdownOutput() }
        } catch (e: IOException) {
            close(srcSock); close(dstSock)
        }
    }

    private fun handleUdp(client: Socket, input: DataInputStream, out: OutputStream) {
        val links = uplinks()
        val id = scheduler.pick(links.map { LinkScheduler.LinkInfo(it.id, meter.peak(it.id), meter.current(it.id)) })
        val link = links.firstOrNull { it.id == id }
        if (link == null) {
            failures.incrementAndGet()
            Socks5.writeReply(out, Socks5.REP_NETWORK_UNREACHABLE)
            return
        }
        val ds = try {
            link.datagramSocket()
        } catch (e: IOException) {
            failures.incrementAndGet()
            Socks5.writeReply(out, Socks5.REP_GENERAL_FAILURE)
            return
        }
        track(ds)
        val c = meter.counters(link.id)
        c.opened.incrementAndGet()
        scheduler.opened(link.id)
        try {
            Socks5.writeReply(out, Socks5.REP_SUCCEEDED)
            val down = pool.submit {
                val buf = ByteArray(65_535)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        pkt.setLength(buf.size)
                        ds.receive(pkt)
                        synchronized(out) { Socks5.writeUdpFrame(out, pkt.address, pkt.port, buf, 0, pkt.length) }
                        c.rx.addAndGet(pkt.length.toLong())
                    }
                } catch (_: IOException) {
                    close(client)
                }
            }
            val names = HashMap<String, InetAddress>()
            try {
                while (true) {
                    val f = Socks5.readUdpFrame(input) ?: break
                    val flt = filter()
                    if (flt != null) {
                        val d = f.dest
                        if (d is Socks5.Dest.Name && flt.blocks(d.host)) {
                            onBlocked(d.host) // e.g. QUIC to an ad domain: drop, the app falls back and is blocked on TCP too
                            continue
                        }
                        if (d is Socks5.Dest.Ip && d.port == 53) {
                            // DNS query to the app's own resolver: answer NXDOMAIN for blocked names.
                            val q = Dns.queryName(f.payload, f.payload.size)
                            if (q != null && flt.blocks(q)) {
                                onBlocked(q)
                                Dns.nxdomain(f.payload, f.payload.size)?.let { r ->
                                    synchronized(out) { Socks5.writeUdpFrame(out, d.address, 53, r, 0, r.size) }
                                }
                                continue
                            }
                        }
                    }
                    val target = when (val d = f.dest) {
                        is Socks5.Dest.Ip -> d.address
                        is Socks5.Dest.Name -> names[d.host] ?: runCatching {
                            link.resolve(d.host).let { r -> r.firstOrNull { it is Inet4Address } ?: r.first() }
                        }.getOrNull()?.also { if (names.size < 256) names[d.host] = it }
                    } ?: continue // unresolvable: drop the datagram, as a real network would
                    if (isBlocked(target)) continue
                    ds.send(DatagramPacket(f.payload, f.payload.size, InetSocketAddress(target, f.dest.port)))
                    c.tx.addAndGet(f.payload.size.toLong())
                }
            } finally {
                close(ds)
                runCatching { down.get() }
            }
        } finally {
            scheduler.closed(link.id)
            close(ds)
        }
    }

    private fun track(c: Closeable) { open += c }

    private fun close(c: Closeable) {
        open -= c
        runCatching { c.close() }
    }

    companion object {
        val LISTEN_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Android reports every connect errno as ConnectException; only ECONNREFUSED means "destination said no". */
        fun isRefused(e: ConnectException): Boolean {
            val m = e.message ?: return false
            return m.contains("ECONNREFUSED") || m.contains("Connection refused", ignoreCase = true)
        }

        /** Our own tunnel/mapped-DNS range (198.18.0.0/15) and local addresses must never be dialled out. */
        fun isBlockedDestination(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
            if (a is Inet4Address) {
                val b = a.address
                return (b[0].toInt() and 0xFF) == 198 && (b[1].toInt() and 0xFE) == 18
            }
            return false
        }
    }
}
