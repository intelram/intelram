package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSchedulerTest {
    private var now = 0L
    private val s = LinkScheduler(clock = { now })
    private val equal = listOf(LinkScheduler.LinkInfo("wifi", 0.0), LinkScheduler.LinkInfo("cell", 0.0))

    private fun openN(links: List<LinkScheduler.LinkInfo>, n: Int): Map<String, Int> {
        val counts = HashMap<String, Int>()
        repeat(n) {
            val id = s.pick(links)!!
            s.opened(id)
            counts[id] = (counts[id] ?: 0) + 1
        }
        return counts
    }

    @Test fun equalLinksSplitEvenly() {
        assertEquals(mapOf("wifi" to 5, "cell" to 5), openN(equal, 10))
    }

    @Test fun fasterLinkGetsProportionallyMore() {
        val links = listOf(LinkScheduler.LinkInfo("wifi", 30e6), LinkScheduler.LinkInfo("cell", 10e6))
        val c = openN(links, 40)
        assertEquals(30, c["wifi"])
        assertEquals(10, c["cell"])
    }

    @Test fun slowLinkStillGetsMinimumShare() {
        // cell measured at ~0 because it was never used: floor keeps it in rotation (25% of best)
        val links = listOf(LinkScheduler.LinkInfo("wifi", 40e6), LinkScheduler.LinkInfo("cell", 0.0))
        val c = openN(links, 50)
        assertEquals(40, c["wifi"])
        assertEquals(10, c["cell"])
    }

    @Test fun closingFreesCapacity() {
        val a = s.pick(equal)!!; s.opened(a)
        val b = s.pick(equal)!!; s.opened(b)
        assertTrue(a != b)
        s.closed(a)
        assertEquals(a, s.pick(equal))
        s.closed(a); s.closed(a) // over-close never goes negative
        assertEquals(0, s.active(a))
    }

    @Test fun failingLinkCoolsDownThenRecovers() {
        s.connectFailed("wifi")
        s.connectFailed("wifi")
        repeat(6) { assertEquals("cell", s.pick(equal)) }
        now += 15_001
        val picks = (1..4).map { s.pick(equal) }.toSet()
        assertEquals(setOf("wifi", "cell"), picks)
    }

    @Test fun successResetsFailureStreak() {
        s.connectFailed("wifi")
        s.connectSucceeded("wifi")
        s.connectFailed("wifi")
        assertEquals(setOf("wifi", "cell"), (1..4).map { s.pick(equal) }.toSet())
    }

    @Test fun allCoolingDownStillReturnsALink() {
        repeat(2) { s.connectFailed("wifi"); s.connectFailed("cell") }
        assertTrue(s.pick(equal) in setOf("wifi", "cell"))
    }

    @Test fun excludeAndEmpty() {
        assertEquals("cell", s.pick(equal, exclude = setOf("wifi")))
        assertNull(s.pick(equal, exclude = setOf("wifi", "cell")))
        assertNull(s.pick(emptyList()))
    }

    @Test fun stickyModeKeepsHostOnOneLinkUntilExpiry() {
        val first = s.pick(equal, "bank.example", stickyMode = true)!!
        repeat(5) { assertEquals(first, s.pick(equal, "bank.example", stickyMode = true)) }
        now += 10 * 60_000 + 1
        val other = s.pick(equal, "bank.example", stickyMode = true)
        assertTrue(other in setOf("wifi", "cell"))
    }

    @Test fun stickyHostMovesWhenItsLinkDisappears() {
        val first = s.pick(equal, "h", stickyMode = true)!!
        s.remove(first)
        val remaining = equal.filter { it.id != first }
        assertEquals(remaining.single().id, s.pick(remaining, "h", stickyMode = true))
    }
}
