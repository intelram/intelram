package com.threadprotection.app.data

import com.threadprotection.app.ui.theme.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cases for resolving threats: persisting resolution across scans, not showing a resolved
 * threat again, and bringing it back when the underlying issue actually reappears.
 */
class FindingIdentityTest {

    private fun finding(
        id: String,
        sev: Severity = Severity.HIGH,
        risk: Int = 60,
        type: String = "Operating system · Patch level 2025-01-01",
        name: String = "Security patch 6 months old",
    ) = Finding(
        id = id,
        name = name,
        type = type,
        cat = Category.OS,
        sev = sev,
        risk = risk,
        desc = "",
        advice = "",
        fix = "",
        pros = emptyList(),
        cons = emptyList(),
        source = "",
    )

    private fun resolved(vararg findings: Finding): Map<String, String> =
        findings.associate { it.id to FindingIdentity.fingerprintOf(it) }

    // ── identity ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the same problem fingerprints identically across scans`() {
        assertEquals(
            FindingIdentity.fingerprintOf(finding("os-patch")),
            FindingIdentity.fingerprintOf(finding("os-patch")),
        )
    }

    /** Rewording an explanation is not a new problem. */
    @Test
    fun `prose changes do not change the fingerprint`() {
        val a = finding("os-patch").copy(desc = "one wording", advice = "a", name = "Patch is old")
        val b = finding("os-patch").copy(desc = "another wording", advice = "b", name = "Patch is old")
        assertEquals(FindingIdentity.fingerprintOf(a), FindingIdentity.fingerprintOf(b))
    }

    @Test
    fun `a worse risk score is a different fingerprint`() {
        assertTrue(
            FindingIdentity.fingerprintOf(finding("os-patch", risk = 40)) !=
                FindingIdentity.fingerprintOf(finding("os-patch", risk = 70)),
        )
    }

    @Test
    fun `an escalated severity is a different fingerprint`() {
        assertTrue(
            FindingIdentity.fingerprintOf(finding("p", sev = Severity.MEDIUM)) !=
                FindingIdentity.fingerprintOf(finding("p", sev = Severity.CRITICAL)),
        )
    }

    // ── resolved threats stay resolved ──────────────────────────────────────────────────────

    @Test
    fun `a resolved threat is not shown again on the next scan`() {
        val f = finding("port-5555")
        val records = resolved(f)
        assertEquals(setOf("port-5555"), FindingIdentity.stillResolved(listOf(f), records))
        assertTrue(FindingIdentity.active(listOf(f), setOf("port-5555"), emptySet()).isEmpty())
    }

    @Test
    fun `resolving one threat leaves the others active`() {
        val a = finding("a")
        val b = finding("b")
        val active = FindingIdentity.active(listOf(a, b), setOf("a"), emptySet())
        assertEquals(listOf("b"), active.map { it.id })
    }

    @Test
    fun `an ignored threat is also dropped from the active list`() {
        val a = finding("a")
        val b = finding("b")
        assertTrue(FindingIdentity.active(listOf(a, b), setOf("a"), setOf("b")).isEmpty())
    }

    // ── but come back when the problem does ─────────────────────────────────────────────────

    @Test
    fun `a threat that got worse is active again despite being resolved before`() {
        val whenResolved = finding("os-patch", risk = 40, sev = Severity.MEDIUM)
        val records = resolved(whenResolved)
        val nowWorse = finding("os-patch", risk = 70, sev = Severity.HIGH)

        assertTrue(FindingIdentity.stillResolved(listOf(nowWorse), records).isEmpty())
        assertEquals(listOf("os-patch"), FindingIdentity.active(listOf(nowWorse), emptySet(), emptySet()).map { it.id })
    }

    @Test
    fun `a superseded record is reported so it can be cleared from disk`() {
        val records = resolved(finding("os-patch", risk = 40))
        val superseded = FindingIdentity.supersededRecords(listOf(finding("os-patch", risk = 70)), records)
        assertEquals(setOf("os-patch"), superseded)
    }

    @Test
    fun `an unchanged record is not superseded`() {
        val f = finding("os-patch")
        assertTrue(FindingIdentity.supersededRecords(listOf(f), resolved(f)).isEmpty())
    }

    /**
     * A scan that couldn't reach a data source produces fewer findings. Forgetting resolutions on
     * that basis would resurrect everything the user had already dealt with, so a record for a
     * finding this scan didn't produce is kept rather than pruned.
     */
    @Test
    fun `a record for a finding this scan did not produce is kept`() {
        val records = resolved(finding("port-5555"))
        assertTrue(FindingIdentity.supersededRecords(emptyList(), records).isEmpty())
        assertTrue(FindingIdentity.supersededRecords(listOf(finding("other")), records).isEmpty())
    }

    @Test
    fun `a finding this scan did not produce simply is not in the resolved set`() {
        val records = resolved(finding("port-5555"))
        assertTrue(FindingIdentity.stillResolved(listOf(finding("other")), records).isEmpty())
    }

    // ── duplicates and repeated scans ───────────────────────────────────────────────────────

    @Test
    fun `duplicate detections of one id collapse to a single resolved entry`() {
        val f = finding("sideload:com.example")
        // The same problem reported twice in one scan must not produce two resolved ids.
        val stillResolved = FindingIdentity.stillResolved(listOf(f, f.copy()), resolved(f))
        assertEquals(1, stillResolved.size)
    }

    @Test
    fun `resolving survives repeated scans that keep finding the same thing`() {
        val f = finding("sideload:com.example")
        val records = resolved(f)
        repeat(5) {
            assertEquals(setOf(f.id), FindingIdentity.stillResolved(listOf(f), records))
        }
    }

    @Test
    fun `re-resolving a returned threat stores it against its current shape`() {
        val old = finding("os-patch", risk = 40)
        val worse = finding("os-patch", risk = 70)
        // Record captured when it came back and was dealt with again.
        val reRecords = resolved(worse)
        assertEquals(setOf("os-patch"), FindingIdentity.stillResolved(listOf(worse), reRecords))
        // The stale record no longer applies to the worse finding.
        assertFalse(FindingIdentity.fingerprintOf(old) == FindingIdentity.fingerprintOf(worse))
    }
}
