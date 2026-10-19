package com.intelram.netsteer.core

import kotlin.math.ln
import kotlin.math.min

/**
 * Scores networks and decides when to move this app's traffic between them.
 *
 * This is failover / network selection. It picks ONE network at a time for
 * the app's process; it does not aggregate bandwidth.
 */
data class PolicyConfig(
    /** A healthy current network is only replaced by one scoring this much higher. */
    val hysteresisMargin: Int = 15,
    /** ...for this many consecutive evaluations. */
    val confirmRounds: Int = 3,
    /** Minimum time on a network before a non-emergency switch. */
    val minDwellMs: Long = 15_000,
    /** Above this probe loss the network is treated as failed. */
    val maxUsableLoss: Double = 0.5,
    /** Probes older than this are ignored for scoring. */
    val probeStaleMs: Long = 60_000,
)

enum class SwitchReason { INITIAL, CURRENT_LOST, CURRENT_DEGRADED, BETTER_CANDIDATE, PREFERENCE_CHANGED }

sealed interface Decision {
    data class Stay(val networkId: Long, val note: String) : Decision
    data class Switch(val from: Long?, val to: Long, val reason: SwitchReason) : Decision
    data object NoUsableNetwork : Decision
}

object Scorer {
    fun usable(s: NetworkSnapshot, cfg: PolicyConfig, nowMs: Long): Boolean {
        if (!s.validated || s.captivePortal || s.blocked) return false
        val p = freshProbe(s, cfg, nowMs) ?: return true
        return p.lossRatio <= cfg.maxUsableLoss
    }

    fun score(s: NetworkSnapshot, pref: Preference, cfg: PolicyConfig, nowMs: Long): Int? {
        if (!usable(s, cfg, nowMs)) return null
        var score = 100
        score += when (pref) {
            Preference.PREFER_WIFI -> if (s.transport == Transport.WIFI) 40 else 0
            Preference.PREFER_CELLULAR -> if (s.transport == Transport.CELLULAR) 40 else 0
            else -> 0
        }
        if (pref == Preference.AUTO && s.metered) score -= 10
        // log-scaled bandwidth bonus, capped: estimates are coarse, don't let them dominate
        score += min(20, (ln(s.downKbps / 1000.0 + 1.0) * 5).toInt())
        freshProbe(s, cfg, nowMs)?.let { p ->
            score -= min(40, (p.rttMs ?: 400) / 10)
            score -= (p.lossRatio * 60).toInt()
        }
        return score
    }

    private fun freshProbe(s: NetworkSnapshot, cfg: PolicyConfig, nowMs: Long): ProbeStats? =
        s.probe?.takeIf { nowMs - it.sampledAtMs <= cfg.probeStaleMs }
}

/**
 * Stateful hysteresis wrapper around [Scorer]. Not thread-safe; drive it from
 * a single coroutine.
 */
class FailoverPolicy(private val cfg: PolicyConfig = PolicyConfig()) {
    private var pendingCandidate: Long? = null
    private var pendingRounds = 0
    private var lastSwitchMs = Long.MIN_VALUE / 2
    private var lastPreference: Preference? = null

    fun evaluate(
        currentId: Long?,
        networks: List<NetworkSnapshot>,
        pref: Preference,
        nowMs: Long,
    ): Decision {
        val allowed = networks.filter { allowedBy(pref, it.transport) }
        val scored = allowed.mapNotNull { n -> Scorer.score(n, pref, cfg, nowMs)?.let { n to it } }
            .sortedByDescending { it.second }
        val prefChanged = lastPreference != null && lastPreference != pref
        lastPreference = pref

        val best = scored.firstOrNull() ?: run { resetPending(); return Decision.NoUsableNetwork }
        val current = scored.firstOrNull { it.first.id == currentId }

        if (currentId == null) return switchTo(null, best.first.id, SwitchReason.INITIAL, nowMs)

        if (current == null) {
            // Current network vanished, was filtered by preference, or became unusable: fail over now.
            val reason = when {
                prefChanged -> SwitchReason.PREFERENCE_CHANGED
                networks.none { it.id == currentId } -> SwitchReason.CURRENT_LOST
                else -> SwitchReason.CURRENT_DEGRADED
            }
            return switchTo(currentId, best.first.id, reason, nowMs)
        }

        if (best.first.id == currentId) {
            resetPending()
            return Decision.Stay(currentId, "current is best (score ${current.second})")
        }

        if (prefChanged && best.second > current.second) {
            return switchTo(currentId, best.first.id, SwitchReason.PREFERENCE_CHANGED, nowMs)
        }

        if (best.second < current.second + cfg.hysteresisMargin) {
            resetPending()
            return Decision.Stay(currentId, "candidate not better by margin (${best.second} vs ${current.second})")
        }
        if (nowMs - lastSwitchMs < cfg.minDwellMs) {
            return Decision.Stay(currentId, "dwell time not elapsed")
        }
        if (pendingCandidate != best.first.id) {
            pendingCandidate = best.first.id
            pendingRounds = 0
        }
        pendingRounds++
        if (pendingRounds < cfg.confirmRounds) {
            return Decision.Stay(currentId, "confirming candidate ${best.first.id} ($pendingRounds/${cfg.confirmRounds})")
        }
        return switchTo(currentId, best.first.id, SwitchReason.BETTER_CANDIDATE, nowMs)
    }

    private fun switchTo(from: Long?, to: Long, reason: SwitchReason, nowMs: Long): Decision {
        resetPending()
        lastSwitchMs = nowMs
        return Decision.Switch(from, to, reason)
    }

    private fun resetPending() {
        pendingCandidate = null
        pendingRounds = 0
    }

    companion object {
        fun allowedBy(pref: Preference, t: Transport): Boolean = when (pref) {
            Preference.WIFI_ONLY -> t == Transport.WIFI
            Preference.CELLULAR_ONLY -> t == Transport.CELLULAR
            else -> t == Transport.WIFI || t == Transport.CELLULAR || t == Transport.ETHERNET
        }
    }
}
