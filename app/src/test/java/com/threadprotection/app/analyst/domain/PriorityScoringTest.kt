package com.threadprotection.app.analyst.domain

import com.threadprotection.app.analyst.domain.model.CvePriority
import org.junit.Assert.assertEquals
import org.junit.Test

/** Test cases for the composite CVE priority formula — the reason this exists at all is that raw
 *  CVSS is a bad standalone signal, so each case below pins one part of why. */
class PriorityScoringTest {

    @Test
    fun `confirmed KEV is always critical regardless of scores`() {
        assertEquals(CvePriority.CRITICAL, PriorityScoring.calculate(cvssScore = 4.0, epssProbability = 0.01, isKev = true))
    }

    @Test
    fun `high EPSS alone is critical even with low CVSS`() {
        // The case a CVSS-only view gets most wrong: low severity, but actively being exploited.
        assertEquals(CvePriority.CRITICAL, PriorityScoring.calculate(cvssScore = 3.0, epssProbability = 0.85, isKev = false))
    }

    @Test
    fun `severe CVSS with meaningful EPSS is high`() {
        assertEquals(CvePriority.HIGH, PriorityScoring.calculate(cvssScore = 9.8, epssProbability = 0.35, isKev = false))
    }

    @Test
    fun `severe CVSS with negligible EPSS is not high`() {
        // This is the headline case: CVSS 9+ that nobody is actually exploiting is not an emergency.
        val result = PriorityScoring.calculate(cvssScore = 9.8, epssProbability = 0.02, isKev = false)
        assertEquals(CvePriority.MEDIUM, result)
    }

    @Test
    fun `moderate CVSS alone is medium`() {
        assertEquals(CvePriority.MEDIUM, PriorityScoring.calculate(cvssScore = 7.5, epssProbability = 0.0, isKev = false))
    }

    @Test
    fun `moderate EPSS alone is medium`() {
        assertEquals(CvePriority.MEDIUM, PriorityScoring.calculate(cvssScore = 2.0, epssProbability = 0.15, isKev = false))
    }

    @Test
    fun `low everything is low`() {
        assertEquals(CvePriority.LOW, PriorityScoring.calculate(cvssScore = 3.0, epssProbability = 0.02, isKev = false))
    }

    @Test
    fun `missing scores are never treated as urgent`() {
        assertEquals(CvePriority.LOW, PriorityScoring.calculate(cvssScore = null, epssProbability = null, isKev = false))
    }

    @Test
    fun `a missing epss score with high cvss still falls to medium not critical`() {
        assertEquals(CvePriority.MEDIUM, PriorityScoring.calculate(cvssScore = 9.9, epssProbability = null, isKev = false))
    }

    @Test
    fun `boundary - exactly 9_0 cvss and exactly 0_3 epss is not high (both must exceed, not equal)`() {
        // 0.3 is not > 0.3, so this falls through to MEDIUM via the cvss>=7 branch.
        assertEquals(CvePriority.MEDIUM, PriorityScoring.calculate(cvssScore = 9.0, epssProbability = 0.3, isKev = false))
    }

    @Test
    fun `boundary - exactly 7_0 cvss qualifies for medium`() {
        assertEquals(CvePriority.MEDIUM, PriorityScoring.calculate(cvssScore = 7.0, epssProbability = 0.0, isKev = false))
    }

    @Test
    fun `boundary - just under 7_0 cvss with no epss is low`() {
        assertEquals(CvePriority.LOW, PriorityScoring.calculate(cvssScore = 6.99, epssProbability = 0.0, isKev = false))
    }
}
