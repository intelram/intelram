package com.intelram.netsteer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Download speed test: each link alone, then all links at once, each with
 * several parallel HTTPS streams bound to that network. Shows whether
 * combining actually adds up on this phone, carrier and router.
 */
class SpeedTest(private val phaseMs: Long = 6_000, private val warmupMs: Long = 1_000, private val streams: Int = 4) {
    data class Result(val perLink: List<Pair<String, Double>>, val combinedBps: Double, val bytesUsed: Long)

    suspend fun run(links: List<AndroidUplink>, progress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        require(links.isNotEmpty()) { "no network to test" }
        var used = 0L
        val per = ArrayList<Pair<String, Double>>()
        for (l in links) {
            progress("Testing ${LinkRegistry.labelFor(l.id)}…")
            val (bps, bytes) = phase(listOf(l))
            used += bytes
            per += l.id to bps
        }
        var combined = per.firstOrNull()?.second ?: 0.0
        if (links.size > 1) {
            progress("Testing all networks together…")
            val (bps, bytes) = phase(links)
            used += bytes
            combined = bps
        }
        Result(per, combined, used)
    }

    /** Returns (bits/s measured after warm-up, total bytes downloaded). */
    private suspend fun phase(links: List<AndroidUplink>): Pair<Double, Long> = coroutineScope {
        val total = AtomicLong()
        val measured = AtomicLong()
        val start = System.currentTimeMillis()
        val end = start + phaseMs
        links.flatMap { l -> (1..streams).map { l } }.map { l ->
            async(Dispatchers.IO) { stream(l, end, start + warmupMs, total, measured) }
        }.awaitAll()
        val secs = (phaseMs - warmupMs) / 1000.0
        measured.get() * 8 / secs to total.get()
    }

    private suspend fun stream(l: AndroidUplink, end: Long, measureFrom: Long, total: AtomicLong, measured: AtomicLong) {
        val buf = ByteArray(64 * 1024)
        var urlIndex = 0
        while (System.currentTimeMillis() < end && coroutineContext.isActive && urlIndex < URLS.size) {
            val c = try {
                l.network.openConnection(URL(URLS[urlIndex])) as HttpsURLConnection
            } catch (_: Exception) {
                urlIndex++
                continue
            }
            try {
                c.connectTimeout = 5_000
                c.readTimeout = 5_000
                c.useCaches = false
                c.setRequestProperty("Accept-Encoding", "identity")
                if (c.responseCode != 200) {
                    urlIndex++ // rate-limited or unavailable here: try the next server
                    continue
                }
                c.inputStream.use { input ->
                    while (System.currentTimeMillis() < end) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total.addAndGet(n.toLong())
                        if (System.currentTimeMillis() >= measureFrom) measured.addAndGet(n.toLong())
                    }
                }
            } catch (_: Exception) {
                urlIndex++
            } finally {
                c.disconnect()
            }
        }
    }

    private companion object {
        val URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=200000000",
            "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
        )
    }
}
