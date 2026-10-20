package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostControllerTest {
    private val b = BoostController(onBps = 2e6, onSustainMs = 3_000, offBps = 0.5e6, offSustainMs = 30_000, lingerMs = 60_000)
    private var t = 0L
    private fun step(bps: Double, wifi: Boolean = true, combine: Boolean = true, smart: Boolean = true) =
        b.update(t, bps, wifi, combine, smart).also { t += 1_000 }

    @Test fun lightTrafficNeverWakesMobile() {
        repeat(600) {
            val d = step(if (it % 10 == 0) 3e6 else 0.2e6) // short bursts only
            assertFalse(d.requestMobile)
            assertFalse(d.mobileForNewConnections)
        }
    }

    @Test fun sustainedHeavyTrafficBoostsThenCoolsDownAndReleases() {
        repeat(3) { assertFalse(step(5e6).boosting) }
        assertTrue(step(5e6).boosting) // >= 3 s above threshold
        repeat(20) { val d = step(1e6); assertTrue(d.boosting && d.requestMobile && d.mobileForNewConnections) } // between thresholds: stay
        repeat(30) { assertTrue(step(0.1e6).boosting) }
        val off = step(0.1e6)
        assertFalse(off.boosting)
        assertFalse(off.mobileForNewConnections)
        assertTrue(off.requestMobile) // lingering: existing mobile connections keep working
        repeat(58) { assertTrue(step(0.1e6).requestMobile) }
        repeat(3) { step(0.1e6) }
        assertFalse(step(0.1e6).requestMobile)
    }

    @Test fun modesAndMissingWifi() {
        assertEquals(BoostController.Decision(true, true, false), step(0.0, smart = false))
        assertEquals(BoostController.Decision(false, false, false), step(9e9, combine = false))
        assertEquals(BoostController.Decision(false, true, false), step(0.0, wifi = false, combine = false))
        assertEquals(BoostController.Decision(false, true, false), step(0.0, wifi = false))
    }
}
