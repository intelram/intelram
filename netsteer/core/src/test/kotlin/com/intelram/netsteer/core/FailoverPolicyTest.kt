package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {
    private val cfg = PolicyConfig(hysteresisMargin = 15, confirmRounds = 3, minDwellMs = 10_000)

    private fun wifi(validated: Boolean = true, rtt: Int? = 20, loss: Double = 0.0, at: Long = 0) =
        NetworkSnapshot(1, Transport.WIFI, validated, false, false, false, 50_000, 10_000, ProbeStats(rtt, loss, at))

    private fun cell(validated: Boolean = true, rtt: Int? = 60, loss: Double = 0.0, at: Long = 0) =
        NetworkSnapshot(2, Transport.CELLULAR, validated, false, true, false, 30_000, 5_000, ProbeStats(rtt, loss, at))

    @Test fun initialPicksBestNetwork() {
        val d = FailoverPolicy(cfg).evaluate(null, listOf(wifi(), cell()), Preference.AUTO, 0)
        assertEquals(Decision.Switch(null, 1, SwitchReason.INITIAL), d)
    }

    @Test fun failsOverImmediatelyWhenCurrentDisappears() {
        val p = FailoverPolicy(cfg)
        p.evaluate(null, listOf(wifi(), cell()), Preference.AUTO, 0)
        val d = p.evaluate(1, listOf(cell(at = 1_000)), Preference.AUTO, 1_000)
        assertEquals(Decision.Switch(1, 2, SwitchReason.CURRENT_LOST), d)
    }

    @Test fun failsOverImmediatelyWhenCurrentLosesValidation() {
        val p = FailoverPolicy(cfg)
        p.evaluate(null, listOf(wifi(), cell()), Preference.AUTO, 0)
        val d = p.evaluate(1, listOf(wifi(validated = false), cell()), Preference.AUTO, 500)
        assertEquals(Decision.Switch(1, 2, SwitchReason.CURRENT_DEGRADED), d)
    }

    @Test fun heavyProbeLossCountsAsDegraded() {
        val p = FailoverPolicy(cfg)
        p.evaluate(null, listOf(wifi(), cell()), Preference.AUTO, 0)
        val d = p.evaluate(1, listOf(wifi(loss = 0.8, at = 100), cell(at = 100)), Preference.AUTO, 100)
        assertEquals(Decision.Switch(1, 2, SwitchReason.CURRENT_DEGRADED), d)
    }

    @Test fun doesNotFlapBackWithoutHysteresisAndConfirmation() {
        val p = FailoverPolicy(cfg)
        p.evaluate(null, listOf(cell()), Preference.AUTO, 0) // on cellular
        val nets = listOf(wifi(), cell())
        // Wi-Fi is clearly better, but dwell time has not elapsed.
        assertTrue(p.evaluate(2, nets, Preference.AUTO, 5_000) is Decision.Stay)
        // After dwell: needs confirmRounds consecutive wins.
        assertTrue(p.evaluate(2, nets, Preference.AUTO, 20_000) is Decision.Stay)
        assertTrue(p.evaluate(2, nets, Preference.AUTO, 25_000) is Decision.Stay)
        assertEquals(Decision.Switch(2, 1, SwitchReason.BETTER_CANDIDATE), p.evaluate(2, nets, Preference.AUTO, 30_000))
    }

    @Test fun smallAdvantageNeverSwitches() {
        val p = FailoverPolicy(cfg)
        p.evaluate(null, listOf(cell(rtt = 30)), Preference.AUTO, 0)
        val nets = listOf(wifi(rtt = 40), cell(rtt = 30)) // nearly equal after metered penalty
        repeat(10) { i -> assertTrue(p.evaluate(2, nets, Preference.AUTO, 20_000L + i * 5_000) is Decision.Stay) }
    }

    @Test fun onlyModesFilterTransports() {
        val d = FailoverPolicy(cfg).evaluate(null, listOf(wifi(), cell()), Preference.CELLULAR_ONLY, 0)
        assertEquals(Decision.Switch(null, 2, SwitchReason.INITIAL), d)
        val none = FailoverPolicy(cfg).evaluate(null, listOf(cell()), Preference.WIFI_ONLY, 0)
        assertEquals(Decision.NoUsableNetwork, none)
    }

    @Test fun preferenceChangeSwitchesWithoutWaitingForDwell() {
        val p = FailoverPolicy(cfg)
        p.evaluate(null, listOf(wifi(), cell()), Preference.AUTO, 0)
        val d = p.evaluate(1, listOf(wifi(), cell()), Preference.PREFER_CELLULAR, 1_000)
        assertEquals(Decision.Switch(1, 2, SwitchReason.PREFERENCE_CHANGED), d)
    }

    @Test fun captivePortalIsNeverUsable() {
        val portal = wifi().copy(captivePortal = true)
        assertEquals(null, Scorer.score(portal, Preference.AUTO, cfg, 0))
    }

    @Test fun staleProbeIsIgnored() {
        val lossyButOld = wifi(loss = 1.0, at = 0)
        assertTrue(Scorer.usable(lossyButOld, cfg, nowMs = cfg.probeStaleMs + 1))
    }
}
