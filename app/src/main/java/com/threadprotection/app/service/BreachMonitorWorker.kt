package com.threadprotection.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.data.StoredBreachAlert
import com.threadprotection.app.network.BreachAdvisor
import com.threadprotection.app.network.BreachAdvisor.toStored
import com.threadprotection.app.network.BreachAlertPlan
import com.threadprotection.app.network.BreachCheckResult
import com.threadprotection.app.network.ThreatIntelRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background breach monitoring for the signed-in email — what Settings' "Data breach alerts"
 * toggle promises. Runs every 12 hours while that toggle is on and someone is signed in. A plain
 * `CoroutineWorker` like every other worker in this app (see `CveWatchlistSyncWorker`).
 *
 * 12 hours keeps a newly published breach from going unnoticed for long while staying far inside
 * XposedOrNot's free limits (25 requests/hour, 100/day per IP). A failed check is not retried with
 * WorkManager backoff for the same reason: hammering a rate-limited free API makes the next
 * scheduled check fail too. It simply tries again next period.
 */
class BreachMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (!settings.protectionSettingsFlow.first().breach) return Result.success()
        val email = settings.accountFlow.first()?.email?.trim()?.takeIf { it.contains('@') }
            ?: return Result.success()

        val result = ThreatIntelRepository().checkEmailBreaches(email)
        if (!result.checked) {
            Log.w(TAG, "Breach check skipped this period: ${result.error}")
            return Result.success()
        }
        val alert = BreachMonitoring.recordCheck(settings, result, alertOnFirstLook = true)
        if (alert != null) NotificationHelper.postBreachAlert(applicationContext, alert)
        return Result.success()
    }

    companion object {
        private const val TAG = "BreachMonitor"
        private const val WORK_NAME = "breach_monitor"

        /** Idempotent: `KEEP` makes this a no-op once scheduled. A newly scheduled periodic job runs
         *  its first check right away, so signing in gets an answer now, not in 12 hours. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<BreachMonitorWorker>(12, TimeUnit.HOURS)
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

/**
 * The one place a breach check's result is reconciled with what was already known — used by both
 * [BreachMonitorWorker] and the in-app "Check now", so the two can never disagree about what's new.
 */
object BreachMonitoring {

    /** How many breaches a pending alert carries. The popup shows the worst few; the full list is
     *  always one tap away on the Data breach screen. */
    private const val MAX_ALERT_BREACHES = 10

    /**
     * Updates the stored baseline and, if this check found something the user should be told
     * about, merges it into the pending alert and returns that. [alertOnFirstLook] is false for an
     * in-app check — the user is already looking at the full results, so a "you're in N breaches"
     * popup on top of them would just repeat the screen.
     */
    suspend fun recordCheck(settings: SettingsRepository, result: BreachCheckResult, alertOnFirstLook: Boolean): StoredBreachAlert? {
        if (!result.checked) return null
        val baseline = settings.breachMonitorFlow.first()
        val plan = BreachAdvisor.plan(result, baseline)
        settings.saveBreachMonitor(BreachAdvisor.updatedBaseline(result, baseline, System.currentTimeMillis()))

        val alert = when (plan) {
            BreachAlertPlan.None -> null
            is BreachAlertPlan.FirstLook -> if (!alertOnFirstLook) null else StoredBreachAlert(
                email = result.email,
                breaches = plan.breaches.take(MAX_ALERT_BREACHES).map { it.toStored() },
                totalBreaches = result.breaches.size,
                firstLook = true,
                createdAtMs = System.currentTimeMillis(),
            )
            is BreachAlertPlan.NewBreaches -> StoredBreachAlert(
                email = result.email,
                breaches = plan.breaches.take(MAX_ALERT_BREACHES).map { it.toStored() },
                totalBreaches = result.breaches.size,
                firstLook = false,
                createdAtMs = System.currentTimeMillis(),
            )
        } ?: return null
        return settings.mergePendingBreachAlert(alert)
    }
}
