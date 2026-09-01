package com.threadprotection.app.analyst.domain.model

/**
 * Composite risk tier — the whole point of computing this at all is that a raw CVSS score is a
 * bad prioritization signal on its own: plenty of CVSS 9+ vulnerabilities are never exploited,
 * while a CVSS 7.5 that is a *confirmed* CISA Known Exploited Vulnerability is an emergency. See
 * [PriorityScoring] for the exact rule.
 */
enum class CvePriority { CRITICAL, HIGH, MEDIUM, LOW }

/** One reference URL attached to a CVE, as NVD reports it (verified against a live NVD API 2.0
 *  response — field names are `url`/`source`/`tags`, not invented). */
data class CveReference(val url: String, val source: String, val tags: List<String> = emptyList())

/**
 * CISA Known Exploited Vulnerabilities status for one CVE.
 *
 * Sourced from NVD's own `cisaExploitAdd`/`cisaActionDue`/`cisaRequiredAction`/
 * `cisaVulnerabilityName` fields on the CVE record itself, rather than a second client for CISA's
 * separate KEV JSON feed — NVD ingests directly from CISA, so the two are the same underlying
 * data, and reading it off the record already being fetched avoids downloading and parsing a
 * multi-megabyte file (currently 1,300+ entries and growing) on every lookup.
 */
data class KevStatus(
    val isKnownExploited: Boolean,
    val dateAdded: String? = null,
    val dueDate: String? = null,
    val requiredAction: String? = null,
    val vulnerabilityName: String? = null,
)

/** One row in a CVE search result list — deliberately smaller than [CveDetail], since a search
 *  hit doesn't need the full reference list or every CPE match. */
data class CveSummary(
    val id: String,
    val description: String,
    val publishedDate: String?,
    val cvssScore: Double?,
    val cvssSeverity: String?,
    val isKev: Boolean,
    val priority: CvePriority,
)

/** The full record shown on the CVE Detail screen. */
data class CveDetail(
    val id: String,
    val description: String,
    val publishedDate: String?,
    val lastModifiedDate: String?,
    val cvssVersion: String?,
    val cvssVectorString: String?,
    val cvssScore: Double?,
    val cvssSeverity: String?,
    /** CWE ids, e.g. "CWE-79" — deduplicated across every weakness entry NVD reports. */
    val cweIds: List<String>,
    val references: List<CveReference>,
    /** Deduplicated CPE match criteria strings from every configuration node — the closest thing
     *  NVD's schema has to a flat "affected products" list. */
    val affectedProducts: List<String>,
    /** 0.0–1.0 probability of exploitation in the next 30 days (FIRST.org EPSS). Null when EPSS
     *  has no score for this CVE (it doesn't score every CVE — very new or very old ones may be
     *  absent) or the lookup failed; never defaulted to 0.0 silently at this layer, so the UI can
     *  tell "scored zero risk" from "unscored". */
    val epssScore: Double?,
    val epssPercentile: Double?,
    val kev: KevStatus,
    val priority: CvePriority,
    val isWatched: Boolean,
)
