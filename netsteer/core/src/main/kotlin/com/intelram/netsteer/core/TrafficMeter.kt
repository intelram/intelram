package com.intelram.netsteer.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-link byte counters (updated from relay threads) and rate sampling
 * (called periodically from one thread).
 */
class TrafficMeter(private val peakWindowSamples: Int = 30) {
    class Counters {
        val rx = AtomicLong()
        val tx = AtomicLong()
    }

    data class Rate(val rxBps: Double, val txBps: Double, val peakBps: Double, val rxTotal: Long, val txTotal: Long)

    private class History(var lastRx: Long, var lastTx: Long, var lastAt: Long) {
        val totals = ArrayDeque<Double>()
        var rxBps = 0.0
        var txBps = 0.0
    }

    private val counters = ConcurrentHashMap<String, Counters>()
    private val history = HashMap<String, History>()

    fun counters(id: String): Counters = counters.getOrPut(id) { Counters() }

    /** Compute rates since the previous call. */
    @Synchronized
    fun sample(nowMs: Long): Map<String, Rate> = counters.mapValues { (id, c) ->
        val rx = c.rx.get()
        val tx = c.tx.get()
        val h = history.getOrPut(id) { History(rx, tx, nowMs) }
        val dt = (nowMs - h.lastAt) / 1000.0
        if (dt > 0) {
            h.rxBps = (rx - h.lastRx) * 8 / dt
            h.txBps = (tx - h.lastTx) * 8 / dt
            h.totals.addLast(h.rxBps + h.txBps)
            while (h.totals.size > peakWindowSamples) h.totals.removeFirst()
            h.lastRx = rx; h.lastTx = tx; h.lastAt = nowMs
        }
        Rate(h.rxBps, h.txBps, h.totals.maxOrNull() ?: 0.0, rx, tx)
    }

    /** Latest peak throughput per link, for the scheduler. */
    @Synchronized
    fun peak(id: String): Double = history[id]?.totals?.maxOrNull() ?: 0.0
}
