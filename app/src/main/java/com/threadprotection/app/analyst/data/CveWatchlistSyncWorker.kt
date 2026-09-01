package com.threadprotection.app.analyst.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.threadprotection.app.MainActivity
import com.threadprotection.app.analyst.data.local.AnalystDatabase
import com.threadprotection.app.analyst.data.remote.EpssApi
import com.threadprotection.app.analyst.domain.PriorityScoring
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.network.NetworkModule
import com.threadprotection.app.network.NvdApi
import com.threadprotection.app.service.NotificationHelper
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily background check of every watched CVE for a KEV-status change — the master spec's "push
 * notification when a watched CVE is added to CISA KEV." A plain `CoroutineWorker`, matching every
 * other worker in this app (`ScheduledScanWorker`, `TwoFactorReminderWorker`): WorkManager
 * constructs it directly, outside the Hilt graph, so it builds its own dependencies the same way
 * those two already do rather than pulling in `hilt-work` for one worker.
 */
class CveWatchlistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AnalystDatabase.getInstance(applicationContext)
        val watchlist = db.cveDao().getAll()
        if (watchlist.isEmpty()) return Result.success()

        val settings = SettingsRepository(applicationContext)
        val apiKeys = settings.apiKeysFlow.first()
        val nvdApi = NetworkModule.create<NvdApi>(NvdApi.BASE_URL)
        val epssApi = NetworkModule.create<EpssApi>(EpssApi.BASE_URL)
        val nvdKey = apiKeys[ApiKeyId.NVD].ifBlank { null }

        for (entry in watchlist) {
            val cve = runCatching { nvdApi.getCveById(apiKey = nvdKey, cveId = entry.cveId) }
                .getOrNull()?.vulnerabilities?.firstOrNull()?.cve ?: continue
            val epssScore = runCatching { epssApi.getScore(entry.cveId) }
                .getOrNull()?.data?.firstOrNull()?.epssScore
            val isKev = cve.cisaExploitAdd != null
            val cvssScore = cve.metrics?.bestScore

            // The transition that matters: it was not in KEV before, and it is now, and we
            // haven't already notified for this. Not "is it in KEV" on its own, or a watchlist
            // that included an already-KEV CVE at add time would fire a notification every day.
            val justAddedToKev = isKev && !entry.lastKnownIsKev && entry.kevNotifiedAtMs == null
            if (justAddedToKev) {
                NotificationHelper.postAlert(
                    applicationContext,
                    "${entry.cveId} added to CISA KEV",
                    cve.cisaVulnerabilityName ?: "This watched CVE is now a confirmed Known Exploited Vulnerability.",
                    MainActivity.TARGET_ANALYST_CVE,
                )
            }

            db.cveDao().update(
                entry.copy(
                    lastCheckedAtMs = System.currentTimeMillis(),
                    lastKnownCvssScore = cvssScore,
                    lastKnownEpssScore = epssScore,
                    lastKnownIsKev = isKev,
                    kevNotifiedAtMs = if (justAddedToKev) System.currentTimeMillis() else entry.kevNotifiedAtMs,
                ),
            )
            // Priority is recomputed on read (CveRepository), not stored — these cached columns
            // exist only to detect the KEV transition above, not to duplicate PriorityScoring.
            PriorityScoring.calculate(cvssScore, epssScore, isKev)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "cve_watchlist_sync"

        /** Idempotent — call whenever a CVE is added to the watchlist; `KEEP` means this is a
         *  no-op once the periodic job is already scheduled. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<CveWatchlistSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
