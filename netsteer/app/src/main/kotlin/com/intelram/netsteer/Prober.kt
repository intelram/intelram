package com.intelram.netsteer

import android.net.Network
import com.intelram.netsteer.core.ProbeResult
import com.intelram.netsteer.core.ProbeStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Layered active probe pinned to one specific network, independent of the
 * process default. Every socket here is created from the Network object
 * (Network.getAllByName / socketFactory / openConnection), so a Wi-Fi probe
 * never leaks out over cellular or vice versa.
 */
class Prober(
    private val host: String = "www.google.com",
    private val path: String = "/generate_204",
    private val timeoutMs: Int = 4_000,
) {
    suspend fun probe(state: NetworkState): ProbeResult = withContext(Dispatchers.IO) {
        val net = state.network
        val snap = state.toSnapshot()

        val (addr, dnsMs) = timed { runCatching { net.getAllByName(host).first() }.getOrNull() }
        var tcpMs: Int? = null
        val tcpOk = addr != null && run {
            val (ok, ms) = timed { tcpConnect(net, InetSocketAddress(addr, 443)) }
            tcpMs = ms
            ok
        }

        var status: Int? = null
        var err: String? = null
        var httpMs: Int? = null
        if (tcpOk) {
            val (_, ms) = timed {
                try {
                    status = httpsStatus(net)
                } catch (e: Exception) {
                    err = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
            }
            httpMs = ms
        }

        ProbeResult(
            networkId = snap.id,
            transport = snap.transport,
            osValidated = snap.validated,
            osCaptivePortal = snap.captivePortal,
            blocked = snap.blocked,
            dnsOk = addr != null,
            dnsMs = dnsMs.takeIf { addr != null },
            tcpOk = tcpOk,
            tcpMs = tcpMs.takeIf { tcpOk },
            httpStatus = status,
            httpError = err,
            httpMs = httpMs.takeIf { status != null },
        )
    }

    /** Cheap periodic health sample used by the failover loop: N TCP connects. */
    suspend fun sample(state: NetworkState, attempts: Int = 3): ProbeStats = withContext(Dispatchers.IO) {
        val addr = runCatching { state.network.getAllByName(host).first() }.getOrNull()
        val rtts = mutableListOf<Int>()
        var failures = 0
        repeat(attempts) {
            if (addr == null) { failures++; return@repeat }
            val (ok, ms) = timed { tcpConnect(state.network, InetSocketAddress(addr, 443)) }
            if (ok) rtts += ms else failures++
        }
        ProbeStats(
            rttMs = rtts.sorted().getOrNull(rtts.size / 2),
            lossRatio = failures.toDouble() / attempts,
            sampledAtMs = System.currentTimeMillis(),
        )
    }

    private fun tcpConnect(net: Network, addr: InetSocketAddress): Boolean = try {
        net.socketFactory.createSocket().use { it.connect(addr, timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    private fun httpsStatus(net: Network): Int {
        val conn = net.openConnection(URL("https://$host$path")) as HttpsURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.responseCode
        } finally {
            (conn as HttpURLConnection).disconnect()
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Int> {
        val t0 = System.nanoTime()
        val r = block()
        return r to ((System.nanoTime() - t0) / 1_000_000).toInt()
    }
}
