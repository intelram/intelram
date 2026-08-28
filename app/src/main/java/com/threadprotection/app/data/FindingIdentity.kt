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
     * Records superseded because the finding they cover came back in a *different* shape — the
     * problem got worse, or changed. Reported so the caller can clear them from disk: a record that
     * no longer describes the situation must not keep suppressing it.
     *
     * A record for a finding the current scan did not produce at all is deliberately *not*
     * superseded here: a scan that couldn't reach a data source (no permission, a restricted API)
     * produces fewer findings, and forgetting the user's resolutions on that basis would resurrect
     * everything they had already dealt with. Confirmed-absent findings are handled by
     * [clearedRecords] instead, which only counts a finding absent when the scan could actually
     * see that category.
     */
    fun supersededRecords(findings: List<Finding>, resolved: Map<String, String>): Set<String> {
        val present = findings.associateBy { it.id }
        return resolved.filter { (id, fingerprint) ->
            val finding = present[id] ?: return@filter false
            fingerprintOf(finding) != fingerprint
        }.keys
    }

    /**
     * Records whose problem a scan has now *confirmed gone*, so they can be retired.
     *
     * This is what makes a genuine re-emergence detectable. Without it, a threat resolved once
     * would be suppressed forever by its own record: the port closes, the finding disappears, then
     * months later the port is open again with an identical fingerprint — and the stale record
     * would hide a real, current problem. Retiring the record the moment a scan proves the issue is
     * gone means its later return arrives as what it is: a new active threat.
     *
     * [coveredCategories] is the safeguard. A record is only retired when the scan actually
     * examined the category that finding belongs to. A scan that couldn't probe ports doesn't get
     * to conclude the port is closed.
     */
    fun clearedRecords(
        findings: List<Finding>,
        resolved: Map<String, String>,
        resolvedCategories: Map<String, Category>,
        coveredCategories: Set<Category>,
    ): Set<String> {
        val presentIds = findings.map { it.id }.toSet()
        return resolved.keys.filter { id ->
            if (id in presentIds) return@filter false
            val category = resolvedCategories[id] ?: return@filter false
            category in coveredCategories
        }.toSet()
    }

    /**
     * Findings that were resolved before, disappeared, and have now come back — reported as new,
     * active threats rather than quietly re-listed.
     *
     * [previouslyResolvedIds] is the set of ids that have ever carried a resolution record, kept
     * even after the record itself is retired, so the app can still say "this one is back".
     */
    fun reEmerged(findings: List<Finding>, previouslyResolvedIds: Set<String>, resolved: Map<String, String>): Set<String> =
        findings.filter { it.id in previouslyResolvedIds && resolved[it.id] != fingerprintOf(it) }
            .map { it.id }
            .toSet()

    /** Findings that are neither resolved nor ignored — the ones still counting against the user. */
    fun active(findings: List<Finding>, resolvedIds: Set<String>, ignoredIds: Set<String>): List<Finding> =
        findings.filter { it.id !in resolvedIds && it.id !in ignoredIds }
}
