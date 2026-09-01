package com.threadprotection.app.analyst.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One CVE on the analyst's watchlist, matching the master spec's `cve_watchlist` table shape.
 *
 * The `lastKnown*` columns are what [com.threadprotection.app.analyst.data.CveWatchlistSyncWorker]
 * compares against on each daily poll: a watched CVE that was NOT in KEV yesterday and IS today is
 * exactly the transition that fires a push notification. Storing the last-seen values (rather than
 * only "is it in KEV now") is what makes that edge-detection possible without re-deriving history.
 */
@Entity(tableName = "cve_watchlist")
data class WatchedCveEntity(
    @PrimaryKey val cveId: String,
    val addedAtMs: Long,
    val lastCheckedAtMs: Long = 0L,
    val lastKnownCvssScore: Double? = null,
    val lastKnownEpssScore: Double? = null,
    val lastKnownIsKev: Boolean = false,
    /** Set once a KEV-addition notification has fired for this CVE, so a later sync (or the
     *  worker retrying) doesn't re-notify for the same transition. */
    val kevNotifiedAtMs: Long? = null,
)
