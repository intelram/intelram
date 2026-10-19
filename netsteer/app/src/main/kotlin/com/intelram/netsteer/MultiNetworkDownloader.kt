package com.intelram.netsteer

import android.net.Network
import com.intelram.netsteer.core.ChunkQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Downloads ONE resource over several networks at once by fetching disjoint
 * HTTP byte ranges on each (the approach OEM "download booster" features use).
 *
 * This is real bandwidth aggregation, but only for:
 *  - downloads performed by this app,
 *  - from servers that honour `Range` (respond 206) and expose a stable
 *    validator (ETag or Last-Modified), so both links fetch the same bytes.
 * It does nothing for other apps, uploads, streaming, or single TCP flows.
 */
class MultiNetworkDownloader(
    private val chunkBytes: Long = 1L shl 20,
    private val timeoutMs: Int = 10_000,
    private val maxFailuresPerNetwork: Int = 3,
) {
    data class Result(val bytes: Long, val millis: Long, val bytesByNetwork: Map<String, Long>)

    class NotRangeCapable(msg: String) : IOException(msg)

    suspend fun download(url: URL, dest: File, networks: Map<String, Network>): Result =
        withContext(Dispatchers.IO) {
            require(networks.isNotEmpty()) { "need at least one network" }
            val t0 = System.currentTimeMillis()
            val (total, validator) = probeRange(url, networks.values.first())
            val queue = ChunkQueue(total, chunkBytes)

            RandomAccessFile(dest, "rw").use { raf ->
                raf.setLength(total)
                val ch = raf.channel
                coroutineScope {
                    networks.map { (name, net) ->
                        async {
                            var failures = 0
                            while (!queue.isFinished() && failures < maxFailuresPerNetwork) {
                                val r = queue.take()
                                if (r == null) { delay(50); continue } // peer may give a chunk back
                                try {
                                    val body = fetch(url, net, r, validator)
                                    ch.write(ByteBuffer.wrap(body), r.first) // positional write is thread-safe
                                    queue.complete(r, name)
                                } catch (e: IOException) {
                                    if (e is NotRangeCapable) throw e
                                    queue.giveBack(r)
                                    failures++
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (!queue.isFinished()) throw IOException("all networks failed before completion")
            }
            Result(total, System.currentTimeMillis() - t0, queue.bytesByWorker())
        }

    /** Returns total length and the validator used for If-Range on every chunk. */
    private fun probeRange(url: URL, net: Network): Pair<Long, String> {
        val c = open(url, net).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            if (c.responseCode != 206) throw NotRangeCapable("server answered ${c.responseCode} to a Range request")
            val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw NotRangeCapable("missing Content-Range total")
            val validator = c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: c.getHeaderField("Last-Modified")
                ?: throw NotRangeCapable("no strong ETag / Last-Modified; cannot guarantee consistent bytes")
            return total to validator
        } finally {
            c.disconnect()
        }
    }

    private fun fetch(url: URL, net: Network, r: LongRange, validator: String): ByteArray {
        val c = open(url, net).apply {
            setRequestProperty("Range", "bytes=${r.first}-${r.last}")
            // If the resource changed, server sends 200 + full body instead of 206: detected below.
            setRequestProperty("If-Range", validator)
        }
        try {
            if (c.responseCode != 206) throw NotRangeCapable("resource changed or range refused (${c.responseCode})")
            val expected = (r.last - r.first + 1).toInt()
            val body = c.inputStream.use { it.readBytes() }
            if (body.size != expected) throw IOException("short chunk ${body.size}/$expected")
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: URL, net: Network) = (net.openConnection(url) as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity") // byte offsets must refer to the raw entity
    }
}
