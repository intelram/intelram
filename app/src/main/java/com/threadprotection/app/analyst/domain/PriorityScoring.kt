package com.threadprotection.app.analyst.domain

import com.threadprotection.app.analyst.domain.model.CvePriority

/**
 * Composite CVE priority: `f(CVSS score, EPSS probability, KEV status)`.
 *
 * Pure function, no I/O — kept separate from the repository so it's directly unit-testable and so
 * the exact rule lives in one place rather than being duplicated between the search-list and
 * detail-screen code paths (both call this).
 *
 * Rule, in the order it's evaluated (first match wins):
 *  - **CRITICAL**: confirmed CISA KEV, OR EPSS > 0.7 (more likely than not to be exploited in the
 *    next 30 days) — a confirmed-exploited or near-certain-to-be-exploited CVE outranks CVSS
 *    entirely, on purpose: a "medium" CVSS actively being used in the wild is not a medium problem.
 *  - **HIGH**: CVSS ≥ 9.0 AND EPSS > 0.3 — a severe *and* meaningfully likely-to-be-exploited flaw.
 *  - **MEDIUM**: CVSS ≥ 7.0 OR EPSS > 0.1 — either signal alone at a moderate level.
 *  - **LOW**: everything else, including a high CVSS with negligible EPSS — the case a
 *    CVSS-only view gets wrong most often.
 *
 * Missing data is never treated as urgent: a null CVSS or EPSS score contributes exactly as if it
 * were the least-alarming value (0.0), so an unscored CVE doesn't default to CRITICAL by omission.
 */
object PriorityScoring {

    fun calculate(cvssScore: Double?, epssProbability: Double?, isKev: Boolean): CvePriority {
        val cvss = cvssScore ?: 0.0
        val epss = epssProbability ?: 0.0
        return when {
            isKev || epss > 0.7 -> CvePriority.CRITICAL
            cvss >= 9.0 && epss > 0.3 -> CvePriority.HIGH
            cvss >= 7.0 || epss > 0.1 -> CvePriority.MEDIUM
            else -> CvePriority.LOW
        }
    }
}
