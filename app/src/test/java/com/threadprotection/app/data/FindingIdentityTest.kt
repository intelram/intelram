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

    // ── confirmed-gone vs couldn't-look ─────────────────────────────────────────────────────

    @Test
    fun `a record is retired when a scan that could see the category found nothing`() {
        val records = resolved(finding("port-5555"))
        val cleared = FindingIdentity.clearedRecords(
            findings = emptyList(),
            resolved = records,
            resolvedCategories = mapOf("port-5555" to Category.PORTS),
            coveredCategories = setOf(Category.PORTS, Category.SOFTWARE),
        )
        assertEquals(setOf("port-5555"), cleared)
    }

    /** The safeguard: a scan that couldn't probe ports doesn't get to declare the port closed. */
    @Test
    fun `a record is not retired when the scan could not examine that category`() {
        val cleared = FindingIdentity.clearedRecords(
            findings = emptyList(),
            resolved = resolved(finding("port-5555")),
            resolvedCategories = mapOf("port-5555" to Category.PORTS),
            coveredCategories = setOf(Category.SOFTWARE),
        )
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun `a record for a finding still present is never retired`() {
        val f = finding("port-5555")
        val cleared = FindingIdentity.clearedRecords(
            findings = listOf(f),
            resolved = resolved(f),
            resolvedCategories = mapOf("port-5555" to Category.PORTS),
            coveredCategories = setOf(Category.PORTS),
        )
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun `a record with no recorded category is left alone`() {
        // Records written before the category was stored must not be retired on a guess.
        val cleared = FindingIdentity.clearedRecords(
            findings = emptyList(),
            resolved = resolved(finding("legacy")),
            resolvedCategories = emptyMap(),
            coveredCategories = Category.entries.toSet(),
        )
        assertTrue(cleared.isEmpty())
    }

    // ── re-emergence is reported as a new threat ────────────────────────────────────────────

    /**
     * The scenario the whole retirement mechanism exists for: resolve a threat, a scan confirms it
     * gone, then months later the identical problem returns. Without retiring the record it would
     * still match by fingerprint and be silently suppressed — hiding a real, current threat.
     */
    @Test
    fun `an identical problem returning after being confirmed gone is active again`() {
        val f = finding("port-5555")
        // Retired: the record no longer suppresses anything, so `resolved` no longer contains it.
        val afterRetirement = emptyMap<String, String>()
        assertTrue(FindingIdentity.stillResolved(listOf(f), afterRetirement).isEmpty())
        assertEquals(listOf("port-5555"), FindingIdentity.active(listOf(f), emptySet(), emptySet()).map { it.id })
    }

    @Test
    fun `a returning threat is flagged as one the user dealt with before`() {
        val f = finding("port-5555")
        val returned = FindingIdentity.reEmerged(
            findings = listOf(f),
            previouslyResolvedIds = setOf("port-5555"),
            resolved = emptyMap(),
        )
        assertEquals(setOf("port-5555"), returned)
    }

    @Test
    fun `a threat the user never resolved is not flagged as returning`() {
        val returned = FindingIdentity.reEmerged(
            findings = listOf(finding("brand-new")),
            previouslyResolvedIds = setOf("something-else"),
            resolved = emptyMap(),
        )
        assertTrue(returned.isEmpty())
    }

    @Test
    fun `a still-suppressed threat is not flagged as returning`() {
        val f = finding("port-5555")
        val returned = FindingIdentity.reEmerged(
            findings = listOf(f),
            previouslyResolvedIds = setOf("port-5555"),
            resolved = resolved(f),
        )
        assertTrue("it is still resolved, so it has not come back", returned.isEmpty())
    }

    @Test
    fun `a threat that came back worse is flagged as returning`() {
        val returned = FindingIdentity.reEmerged(
            findings = listOf(finding("os-patch", risk = 70)),
            previouslyResolvedIds = setOf("os-patch"),
            resolved = resolved(finding("os-patch", risk = 40)),
        )
        assertEquals(setOf("os-patch"), returned)
    }

    @Test
    fun `the full lifecycle - resolve, confirmed gone, returns as new`() {
        val f = finding("port-5555")
        // 1. Resolved.
        var records = resolved(f)
        assertEquals(setOf("port-5555"), FindingIdentity.stillResolved(listOf(f), records))
        // 2. A scan that could see ports finds nothing — the record is retired.
        val retire = FindingIdentity.clearedRecords(
            findings = emptyList(),
            resolved = records,
            resolvedCategories = mapOf("port-5555" to Category.PORTS),
            coveredCategories = setOf(Category.PORTS),
        )
        assertEquals(setOf("port-5555"), retire)
        records = records - retire
        // 3. It comes back. Active again, and known to be a return rather than a first sighting.
        assertTrue(FindingIdentity.stillResolved(listOf(f), records).isEmpty())
        assertEquals(setOf("port-5555"), FindingIdentity.reEmerged(listOf(f), setOf("port-5555"), records))
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
