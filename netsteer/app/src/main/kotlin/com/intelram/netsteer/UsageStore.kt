package com.intelram.netsteer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bytes carried per link today (resets at midnight), persisted so it survives restarts. */
class UsageStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("netsteer_usage", Context.MODE_PRIVATE)
    private var day = today()
    private val bytes = HashMap<String, Long>()
    private val lastTotals = HashMap<String, Long>()

    init {
        if (prefs.getString("day", "") == day) {
            prefs.all.forEach { (k, v) -> if (k.startsWith("b_") && v is Long) bytes[k.removePrefix("b_")] = v }
        }
    }

    /** Feed cumulative rx+tx totals per link (since VPN start); deltas are added to today. */
    @Synchronized
    fun record(totals: Map<String, Long>) {
        val d = today()
        if (d != day) {
            day = d
            bytes.clear()
        }
        for ((id, t) in totals) {
            val prev = lastTotals[id] ?: 0L
            if (t >= prev) bytes[id] = (bytes[id] ?: 0L) + (t - prev)
            lastTotals[id] = t
        }
    }

    /** Call when the VPN (and so its cumulative counters) restarts. */
    @Synchronized
    fun resetBaseline() = lastTotals.clear()

    @Synchronized
    fun today(id: String): Long = if (today() == day) bytes[id] ?: 0L else 0L

    @Synchronized
    fun save() {
        val e = prefs.edit().clear().putString("day", day)
        bytes.forEach { (k, v) -> e.putLong("b_$k", v) }
        e.apply()
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    companion object {
        fun human(b: Long): String = when {
            b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
            else -> "$b B"
        }
    }
}
