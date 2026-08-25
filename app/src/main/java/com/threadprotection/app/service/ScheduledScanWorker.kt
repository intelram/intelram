package com.threadprotection.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.threadprotection.app.MainActivity
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.network.ThreatIntelRepository
import com.threadprotection.app.scan.DeviceScanner
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs the real `DeviceScanner` pipeline at the time/frequency the user picked in Settings
 * (README's "Scheduled scan" — customizable, not the prototype's hardcoded "every day at
 * 3:00 AM"). WorkManager has no built-in "run at this exact clock time" primitive, so each run
 * schedules its own successor with a computed delay — the standard, Doze-friendly pattern for
 * this. Posts a real notification only if the scan actually finds something.
 */
class ScheduledScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = SettingsRepository(applicationContext)
        val settings = repo.protectionSettingsFlow.first()
        if (!settings.autoScan) return Result.success()

        val apiKeys = repo.apiKeysFlow.first()
        val result = runCatching {
            DeviceScanner(applicationContext, ThreatIntelRepository()).scan(apiKeys) { }
        }.getOrNull()

        if (result != null && result.findings.isNotEmpty()) {
            val top = result.findings.maxByOrNull { it.risk }
            NotificationHelper.postAlert(
                applicationContext,
                "Scheduled scan found ${result.findings.size} issue${if (result.findings.size == 1) "" else "s"}",
                top?.name ?: "Open Thread Protection to see details.",
                MainActivity.TARGET_DASHBOARD,
            )
        }

        scheduleNext(applicationContext, settings.scanHour, settings.scanMinute, settings.scanFrequency, settings.scanDayOfWeek)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "scheduled_scan"

        fun scheduleNext(context: Context, hour: Int, minute: Int, frequency: String, dayOfWeek: Int) {
            val delay = delayUntilNext(hour, minute, frequency, dayOfWeek)
            val request = OneTimeWorkRequestBuilder<ScheduledScanWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Walks day-by-day (never more than 7 iterations) to the next matching, still-in-the-future occurrence — avoids Calendar.DAY_OF_WEEK's "same week" quirks. */
        private fun delayUntilNext(hour: Int, minute: Int, frequency: String, dayOfWeek: Int): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            var guard = 0
            while (guard++ < 8 && (!target.after(now) || (frequency == "WEEKLY" && target.get(Calendar.DAY_OF_WEEK) != dayOfWeek))) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        }
    }
}
