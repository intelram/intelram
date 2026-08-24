package com.threadprotection.app.scan

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.concurrent.TimeUnit

/**
 * Wraps `UsageStatsManager` — the README's suggested source for "not used in 90 days" style
 * copy. This needs the user to flip on Special App Access → Usage access by hand (Android does
 * not offer a runtime-permission dialog for it), so every call here degrades to "unknown" rather
 * than crashing when access hasn't been granted yet.
 */
object UsageAccess {

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** package name -> last-used epoch millis, for every app used in the last year. */
    fun lastUsedByPackage(context: Context): Map<String, Long> {
        if (!isGranted(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(365)
        return runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, start, end)
                .associate { it.packageName to it.lastTimeUsed }
        }.getOrDefault(emptyMap())
    }

    fun daysSinceUsed(lastUsedMillis: Long?): Int? {
        if (lastUsedMillis == null || lastUsedMillis <= 0) return null
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastUsedMillis)
        return days.toInt().coerceAtLeast(0)
    }
}
