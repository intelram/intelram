package com.threadprotection.app.data

/**
 * How a finding is recognised as "the same problem" across separate scans.
 *
 * Two different questions have to be answered, and conflating them is what makes a resolved-threat
 * list either useless or dishonest:
 *
 *  - **Is this the same item?** [Finding.id] answers that. Every scanner already mints a stable,
 *    content-derived id (`os-patch`, `port-5555`, `sideload:com.example.app`) rather than a random
 *    UUID or a list index, so the same problem carries the same id on every scan.
 *  - **Is it still the same problem?** [fingerprintOf] answers that. An id alone is not enough:
 *    `os-patch` means "the security patch is old", and the patch being three months old is a
 *    materially different situation from it being eleven months old. If a resolved record were
 *    keyed on the id alone, marking that one reviewed would silence it forever, however much worse
 *    it got. The fingerprint covers the parts of a finding that describe the *severity of the
 *    situation*, so when the situation changes the finding comes back.
 *
 * The result is the behaviour asked for: a threat marked resolved stays out of the way across
 * scans and restarts, and reappears the moment the underlying issue actually reappears or worsens.
 */
object FindingIdentity {

    /**
     * A short digest of what makes this finding the problem it currently is. Deliberately excludes
     * prose ([Finding.desc], [Finding.advice]) — a reworded explanation is not a new problem — and
     * includes severity, risk score and the type line, which is where a real change shows up.
     */
    fun fingerprintOf(finding: Finding): String =
        listOf(finding.sev.name, finding.risk.toString(), finding.type).joinToString("|")

    /**
     * The subset of [findings] that a stored [resolved] record (id → fingerprint) still covers.
     *
     * A record whose fingerprint no longer matches is treated as *not* resolved: the issue came
     * back, or got worse, so the user needs to see it again.
     */
    fun stillResolved(findings: List<Finding>, resolved: Map<String, String>): Set<String> =
        findings.filter { resolved[it.id] == fingerprintOf(it) }.map { it.id }.toSet()

    /**
     * Records that no longer describe anything in [findings] — either the finding is gone (the
     * problem really was fixed and the scan confirms it) or its fingerprint moved on. Kept separate
     * from [stillResolved] so the caller can prune disk without guessing.
     *
     * A record for a finding the current scan did not produce at all is deliberately *kept*: a scan
     * that couldn't reach a data source (no permission, a restricted API) produces fewer findings,
     * and forgetting the user's resolutions on that basis would resurrect everything they had
     * already dealt with.
     */
    fun supersededRecords(findings: List<Finding>, resolved: Map<String, String>): Set<String> {
        val present = findings.associateBy { it.id }
        return resolved.filter { (id, fingerprint) ->
            val finding = present[id] ?: return@filter false
            fingerprintOf(finding) != fingerprint
        }.keys
    }

    /** Findings that are neither resolved nor ignored — the ones still counting against the user. */
    fun active(findings: List<Finding>, resolvedIds: Set<String>, ignoredIds: Set<String>): List<Finding> =
        findings.filter { it.id !in resolvedIds && it.id !in ignoredIds }
}
