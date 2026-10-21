package com.intelram.netsteer.core

/**
 * Chooses which link carries each NEW connection.
 *
 * This is per-connection load distribution: a single connection always stays
 * on one link, so one big download only gets one link's speed, while many
 * parallel connections (web pages, multi-thread downloads, multi-stream speed
 * tests) use all links at once.
 *
 * Load is measured by what links are actually doing, not by how many sockets
 * they hold: phones keep dozens of idle long-lived connections (push, chat,
 * keep-alives) that carry almost nothing and must not make a link look busy.
 *
 *   capacity = max(recent peak throughput, floor, [minShare] × best link's capacity)
 *   cost     = (current throughput + recently assigned connections × [newConnBps]) / capacity
 *
 * The link with the lowest cost wins; "recently assigned" (last [rampMs]) covers
 * connections that haven't ramped up yet, so a burst of new connections is
 * spread instead of herding onto one link. Links that fail [failuresToCooldown]
 * connects in a row are skipped for [cooldownMs].
 *
 * Thread-safe.
 */
class LinkScheduler(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minShare: Double = 0.25,
    private val floorBps: Double = 1_000_000.0,
    private val newConnBps: Double = 1_000_000.0,
    private val rampMs: Long = 3_000,
    private val failuresToCooldown: Int = 2,
    private val cooldownMs: Long = 15_000,
    private val stickyTtlMs: Long = 10 * 60_000,
) {
    /** @param currentBps throughput right now (rx + tx), @param peakBps recent peak. */
    data class LinkInfo(val id: String, val peakBps: Double, val currentBps: Double = 0.0)

    private class State {
        var active = 0
        var consecutiveFailures = 0
        var cooldownUntil = 0L
        val recent = ArrayDeque<Long>()
    }

    private val states = HashMap<String, State>()
    private val sticky = HashMap<String, Pair<String, Long>>()
    private var rr = 0

    /**
     * @param host destination key for compatibility (sticky) mode, or null.
     * @param exclude links already tried for this connection.
     * @return chosen link id, or null if [links] minus [exclude] is empty.
     */
    @Synchronized
    fun pick(links: List<LinkInfo>, host: String? = null, stickyMode: Boolean = false, exclude: Set<String> = emptySet()): String? {
        val candidates = links.filter { it.id !in exclude }
        if (candidates.isEmpty()) return null
        val now = clock()

        val healthy = candidates.filter { st(it.id).cooldownUntil <= now }
        // If every link is cooling down, still try one rather than fail outright.
        val pool = healthy.ifEmpty { candidates }

        if (stickyMode && host != null) {
            sticky[host]?.let { (id, expires) ->
                if (expires > now && pool.any { it.id == id }) return record(id, now)
            }
        }

        val best = pool.maxOf { maxOf(it.peakBps, floorBps) }
        fun capacity(l: LinkInfo) = maxOf(l.peakBps, floorBps, best * minShare)
        fun cost(l: LinkInfo): Double {
            val s = st(l.id)
            while (s.recent.isNotEmpty() && now - s.recent.first() > rampMs) s.recent.removeFirst()
            return (l.currentBps + (s.recent.size + 1) * newConnBps) / capacity(l)
        }
        val costs = pool.associateWith(::cost)
        val minCost = costs.values.min()
        // Round-robin among ties so equal links alternate instead of always picking the first.
        val tied = pool.filter { costs.getValue(it) <= minCost * 1.000001 }
        val chosen = tied[Math.floorMod(rr++, tied.size)].id

        if (stickyMode && host != null) sticky[host] = chosen to (now + stickyTtlMs)
        if (sticky.size > 4096) sticky.entries.removeIf { it.value.second <= now }
        return record(chosen, now)
    }

    private fun record(id: String, now: Long): String {
        val s = st(id)
        s.recent.addLast(now)
        if (s.recent.size > 1_000) s.recent.removeFirst()
        return id
    }

    @Synchronized fun opened(id: String) { st(id).active++ }

    @Synchronized fun closed(id: String) { st(id).let { it.active = maxOf(0, it.active - 1) } }

    @Synchronized fun connectSucceeded(id: String) { st(id).consecutiveFailures = 0 }

    @Synchronized
    fun connectFailed(id: String) {
        val s = st(id)
        s.consecutiveFailures++
        if (s.consecutiveFailures >= failuresToCooldown) {
            s.cooldownUntil = clock() + cooldownMs
            s.consecutiveFailures = 0
        }
    }

    /** Open connections on [id] (for display; not used for load). */
    @Synchronized fun active(id: String): Int = states[id]?.active ?: 0

    /** Forget a link that disappeared (e.g. Wi-Fi turned off). */
    @Synchronized
    fun remove(id: String) {
        states.remove(id)
        sticky.entries.removeIf { it.value.first == id }
    }

    private fun st(id: String) = states.getOrPut(id) { State() }
}
