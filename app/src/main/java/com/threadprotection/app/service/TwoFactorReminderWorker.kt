package com.threadprotection.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * README asked for a notification "if an app is configured with 2-factor authentication" — Android
 * has no API for that: whether Gmail or your bank has 2FA turned on lives in that service's own
 * account settings, on their server, invisible to every other app including this one. The honest
 * version of this feature is a periodic reminder to go check, not a fake detector.
 */
class TwoFactorReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NotificationHelper.postReminder(
            applicationContext,
            "Turn on 2-step verification",
            "We can't see whether your banking, email or social apps already use 2-step verification — that's stored in each app's own account, not on this phone. If you haven't turned it on for your most important accounts, it only takes a minute.",
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "two_factor_reminder"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TwoFactorReminderWorker>(14, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
