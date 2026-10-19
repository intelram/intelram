package com.intelram.netsteer.core

import java.util.ArrayDeque

/**
 * Work-stealing queue of byte ranges for the in-app multi-network downloader.
 *
 * Each network's worker repeatedly takes the next range, so a faster link
 * naturally downloads more chunks. A failed chunk is put back for another
 * worker. This is the only true aggregation the app performs, and it only
 * works for single resources served with HTTP Range support.
 */
class ChunkQueue(totalBytes: Long, chunkBytes: Long) {
    init {
        require(totalBytes > 0) { "totalBytes must be > 0" }
        require(chunkBytes > 0) { "chunkBytes must be > 0" }
    }

    private val pending = ArrayDeque<LongRange>()
    private var outstanding = 0
    private val doneByWorker = HashMap<String, Long>()

    init {
        var start = 0L
        while (start < totalBytes) {
            val end = minOf(start + chunkBytes, totalBytes) - 1
            pending.add(start..end)
            start = end + 1
        }
    }

    /** Next range to fetch, or null if nothing is left to hand out. */
    @Synchronized
    fun take(): LongRange? = pending.pollFirst()?.also { outstanding++ }

    @Synchronized
    fun complete(range: LongRange, worker: String) {
        outstanding--
        doneByWorker[worker] = (doneByWorker[worker] ?: 0) + (range.last - range.first + 1)
    }

    /** Return a range after a failure; it goes to the front so it is retried first. */
    @Synchronized
    fun giveBack(range: LongRange) {
        outstanding--
        pending.addFirst(range)
    }

    @Synchronized
    fun isFinished(): Boolean = pending.isEmpty() && outstanding == 0

    @Synchronized
    fun bytesByWorker(): Map<String, Long> = HashMap(doneByWorker)
}
