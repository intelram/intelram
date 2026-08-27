package com.threadprotection.app.state

import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding

import com.threadprotection.app.ui.theme.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cases for the results screen staying internally consistent: severity grouping, the security
 * score moving the instant a threat is resolved, and the Start Fixing button never disagreeing
 * with the list above it.
 */
class ResultsSyncTest {

    private fun finding(id: String, sev: Severity, risk: Int) = Finding(
        id = id, name = id, type = "t", cat = Category.SOFTWARE, sev = sev, risk = risk,
        desc = "", advice = "", fix = "", pros = emptyList(), cons = emptyList(), source = "",
    )

    private fun state(
        findings: List<Finding>,
        fixed: Set<String> = emptySet(),
        ignored: Set<String> = emptySet(),
        fixing: String? = null,
    ) = AppUiState(
        hasScanned = true,
        scanData = ScanData(findings = findings),
        fixed = fixed,
        ignoredFindings = ignored,
        fixInProgressId = fixing,
    )

    private val critical = finding("crit", Severity.CRITICAL, 92)
    private val high = finding("high", Severity.HIGH, 70)
    private val medium = finding("med", Severity.MEDIUM, 45)
    private val low = finding("low", Severity.LOW, 20)

    // ── grouping by severity ────────────────────────────────────────────────────────────────

    @Test
    fun `groups are ordered most severe first`() {
        val groups = Derived.threatsBySeverity(state(listOf(medium, low, critical, high)))
        assertEquals(
            listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW),
            groups.map { it.severity },
        )
    }

    @Test
    fun `critical and high are separate groups with their own labels`() {
        val groups = Derived.threatsBySeverity(state(listOf(critical, high)))
        assertEquals(listOf("Critical", "Major / High"), groups.map { it.label })
    }

    @Test
    fun `empty severity bands are dropped rather than shown as zero`() {
        val groups = Derived.threatsBySeverity(state(listOf(medium)))
        assertEquals(1, groups.size)
        assertEquals(Severity.MEDIUM, groups[0].severity)
    }

    @Test
    fun `within a group the riskiest comes first`() {
        val a = finding("a", Severity.MEDIUM, 30)
        val b = finding("b", Severity.MEDIUM, 55)
        val group = Derived.threatsBySeverity(state(listOf(a, b))).single()
        assertEquals(listOf("b", "a"), group.findings.map { it.id })
    }

    @Test
    fun `a resolved threat leaves its severity group immediately`() {
        val groups = Derived.threatsBySeverity(state(listOf(critical, high), fixed = setOf("crit")))
        assertEquals(listOf(Severity.HIGH), groups.map { it.severity })
        assertEquals(listOf("crit"), Derived.resolvedThreats(state(listOf(critical, high), fixed = setOf("crit"))).map { it.id })
    }

    // ── the score reacts to the same state the list does ────────────────────────────────────

    @Test
    fun `resolving a threat raises the security score`() {
        val before = Derived.securityScore(state(listOf(critical, high)))
        val after = Derived.securityScore(state(listOf(critical, high), fixed = setOf("crit")))
        assertTrue("score should rise from $before, got $after", after > before)
    }

    @Test
    fun `resolving every threat gives a clean score`() {
        val all = state(listOf(critical, high, medium), fixed = setOf("crit", "high", "med"))
        assertEquals(100, Derived.securityScore(all))
        assertEquals(ScanStatus.PROTECTED, Derived.scanStatus(all))
    }

    @Test
    fun `un-resolving a threat drops the score back`() {
        val resolved = state(listOf(critical), fixed = setOf("crit"))
        val undone = state(listOf(critical))
        assertTrue(Derived.securityScore(undone) < Derived.securityScore(resolved))
    }

    @Test
    fun `ignoring counts the same as resolving for the score`() {
        val ignoredState = state(listOf(critical), ignored = setOf("crit"))
        val fixedState = state(listOf(critical), fixed = setOf("crit"))
        assertEquals(Derived.securityScore(fixedState), Derived.securityScore(ignoredState))
    }

    // ── the Start Fixing button ─────────────────────────────────────────────────────────────

    @Test
    fun `with threats outstanding the button has somewhere to go`() {
        val p = Derived.fixProgress(state(listOf(critical, high)))
        assertEquals(2, p.remaining)
        assertEquals("crit", p.nextId)
        assertFalse(p.allResolved)
        assertFalse(p.fixing)
    }

    @Test
    fun `the button opens the most severe outstanding threat`() {
        val p = Derived.fixProgress(state(listOf(low, medium, critical)))
        assertEquals("crit", p.nextId)
    }

    @Test
    fun `partway through, progress reflects what is actually done`() {
        val p = Derived.fixProgress(state(listOf(critical, high, medium), fixed = setOf("crit")))
        assertEquals(3, p.total)
        assertEquals(1, p.resolved)
        assertEquals(2, p.remaining)
        assertEquals(1f / 3f, p.fraction, 0.0001f)
        assertEquals("high", p.nextId)
    }

    @Test
    fun `all resolved leaves nothing to open`() {
        val p = Derived.fixProgress(state(listOf(critical), fixed = setOf("crit")))
        assertTrue(p.allResolved)
        assertNull(p.nextId)
        assertEquals(1f, p.fraction, 0.0001f)
    }

    @Test
    fun `a clean scan is not reported as all resolved`() {
        val p = Derived.fixProgress(state(emptyList()))
        assertFalse("nothing was found, so nothing was resolved", p.allResolved)
        assertFalse(p.hasThreats)
        assertNull(p.nextId)
    }

    @Test
    fun `ignored items count toward progress but are reported separately`() {
        val p = Derived.fixProgress(state(listOf(critical, high), fixed = setOf("crit"), ignored = setOf("high")))
        assertEquals(1, p.resolved)
        assertEquals(1, p.ignored)
        assertEquals(0, p.remaining)
        assertTrue(p.allResolved)
    }

    @Test
    fun `fixing is only in progress while the finding is still outstanding`() {
        assertTrue(Derived.fixProgress(state(listOf(critical), fixing = "crit")).fixing)
        // Once confirmed, the same id must not keep the button in its "finish fixing" state.
        assertFalse(Derived.fixProgress(state(listOf(critical), fixed = setOf("crit"), fixing = "crit")).fixing)
        // A stale id for a finding this scan no longer reports is likewise not in progress.
        assertFalse(Derived.fixProgress(state(listOf(high), fixing = "crit")).fixing)
    }

    @Test
    fun `the button, the list and the score always describe the same set`() {
        val s = state(listOf(critical, high, medium, low), fixed = setOf("crit"), ignored = setOf("low"))
        val listed = Derived.threatsBySeverity(s).flatMap { it.findings }.map { it.id }
        val p = Derived.fixProgress(s)
        assertEquals("the button's count is exactly what the list shows", listed.size, p.remaining)
        assertTrue("the next target is one of the listed rows", p.nextId in listed)
        assertEquals(listOf("high", "med"), listed)
    }
}
