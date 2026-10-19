package com.intelram.netsteer.core

/**
 * Chooses which link (Wi-Fi or cellular) carries each NEW connection.
 *
 * This is per-connection load distribution: a single connection always stays
 * on one link, so one big download only gets one link's speed, while many
 * parallel connections (web pages, multi-thread downloads, multi-stream speed
 * tests) use both links at once.
 *
 * Policy: pick the link with the lowest (active + 1) / weight, where weight is
 * the link's recently observed peak throughput. Each link's weight is floored
 * at [minShare] of the best link's, so a link that has not been stressed yet
 * still receives traffic and can prove its capacity. Links that fail
 * [failuresToCooldown] connects in a row are skipped for [cooldownMs].
 *
 * Thread-safe.
 */
class LinkScheduler(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minShare: Double = 0.25,
    private val floorBps: Double = 1_000_000.0,
    private val failuresToCooldown: Int = 2,
    private val cooldownMs: Long = 15_000,
    private val stickyTtlMs: Long = 10 * 60_000,
) {
    data class LinkInfo(val id: String, val peakBps: Double)

    private class State {
        var active = 0
        var consecutiveFailures = 0
        var cooldownUntil = 0L
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
                if (expires > now && pool.any { it.id == id }) return id
            }
        }

        val best = pool.maxOf { maxOf(it.peakBps, floorBps) }
        fun weight(l: LinkInfo) = maxOf(l.peakBps, floorBps, best * minShare)
        val minCost = pool.minOf { (st(it.id).active + 1) / weight(it) }
        // Round-robin among ties so equal links alternate instead of always picking the first.
        val tied = pool.filter { (st(it.id).active + 1) / weight(it) <= minCost * 1.000001 }
        val chosen = tied[Math.floorMod(rr++, tied.size)].id

        if (stickyMode && host != null) sticky[host] = chosen to (now + stickyTtlMs)
        if (sticky.size > 4096) sticky.entries.removeIf { it.value.second <= now }
        return chosen
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

    @Synchronized fun active(id: String): Int = states[id]?.active ?: 0

    /** Forget a link that disappeared (e.g. Wi-Fi turned off). */
    @Synchronized
    fun remove(id: String) {
        states.remove(id)
        sticky.entries.removeIf { it.value.first == id }
    }

    private fun st(id: String) = states.getOrPut(id) { State() }
}
