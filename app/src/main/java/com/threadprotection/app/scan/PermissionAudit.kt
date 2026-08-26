package com.threadprotection.app.scan

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.threadprotection.app.data.AppPermission
import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.data.Remedy
import com.threadprotection.app.ui.theme.Severity

/**
 * Real, on-device permission audit — replaces the prototype's static `APP_PERMS` demo table
 * with what's actually installed and actually granted on this phone. See README's "App
 * permissions" and "Backend requirements → Permissions" sections for the platform contract this
 * follows: read-only via `PackageManager`, no attempt to revoke anything directly.
 */
class PermissionAudit(private val context: Context) {
    private val pm = context.packageManager

    data class AppSummary(val label: String, val packageName: String, val sideloaded: Boolean, val riskyPermissionCount: Int)

    data class Result(
        val apps: List<PermApp>,
        val totalInstalledCount: Int,
        val sideloadedApps: List<AppSummary>,
        val findings: List<Finding>,
    )

    fun audit(): Result {
        val lastUsed = UsageAccess.lastUsedByPackage(context)
        val enabledAccessibility = enabledAccessibilityPackages()
        val installedApps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList()).filter { it.packageName != context.packageName }

        val permApps = mutableListOf<PermApp>()
        val findings = mutableListOf<Finding>()
        val sideloaded = mutableListOf<AppSummary>()

        for (appInfo in installedApps) {
            val packageInfo = runCatching {
                pm.getPackageInfo(appInfo.packageName, PackageManager.GET_PERMISSIONS)
            }.getOrNull() ?: continue
            val requested = packageInfo.requestedPermissions?.toList() ?: emptyList()
            val hasAccessibility = appInfo.packageName in enabledAccessibility
            if (requested.isEmpty() && !hasAccessibility) continue

            val rows = mutableListOf<AppPermission>()
            for (permName in requested) {
                if (pm.checkPermission(permName, appInfo.packageName) != PackageManager.PERMISSION_GRANTED) continue
                val entry = PermissionCatalog.lookup(permName) ?: continue
                val days = UsageAccess.daysSinceUsed(lastUsed[appInfo.packageName])
                val stale = days != null && days >= 90
                val why = when {
                    stale -> "Not used in $days days"
                    days != null -> "Used $days day${if (days == 1) "" else "s"} ago"
                    entry.baselineRisky -> "Potentially unnecessary for this app"
                    else -> "Granted"
                }
                rows += AppPermission(
                    id = permName.substringAfterLast('.'),
                    name = entry.plainName,
                    description = entry.description,
                    why = why,
                    risk = entry.baselineRisky || stale,
                )
            }
            if (hasAccessibility) {
                rows += AppPermission(
                    id = "accessibility",
                    name = PermissionCatalog.accessibilityEntry.plainName,
                    description = PermissionCatalog.accessibilityEntry.description,
                    why = "Can read everything on screen and act on your behalf",
                    risk = true,
                )
            }
            if (rows.isEmpty()) continue

            val label = appInfo.loadLabel(pm).toString()
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val installer = installerOf(appInfo.packageName)
            val sideload = !isSystemApp && (installer == null || installer !in TRUSTED_INSTALLERS)
            val kind = when {
                isSystemApp -> "System app · built in"
                sideload -> "Sideloaded · unverified source"
                else -> "Installed app"
            }
            val riskyCount = rows.count { it.risk }
            val safetyScore = safetyScoreFor(riskyCount = riskyCount, totalPerms = rows.size, sideloaded = sideload, isSystemApp = isSystemApp)
            permApps += PermApp(
                app = label,
                packageName = appInfo.packageName,
                kind = kind,
                installerLabel = installerLabelOf(installer, isSystemApp),
                safetyScore = safetyScore,
                perms = rows.sortedByDescending { it.risk },
            )
            if (sideload) {
                sideloaded += AppSummary(label, appInfo.packageName, true, riskyCount)
                if (riskyCount > 0) {
                    findings += sideloadFinding(appInfo.packageName, label, rows.size, riskyCount)
                }
            }
        }

        return Result(
            apps = permApps.sortedByDescending { app -> app.perms.count { it.risk } },
            totalInstalledCount = installedApps.size,
            sideloadedApps = sideloaded,
            findings = findings,
        )
    }

    private fun sideloadFinding(packageName: String, label: String, permCount: Int, riskyCount: Int): Finding {
        val risk = (58 + riskyCount * 9).coerceAtMost(97)
        return Finding(
            id = "sideload:$packageName",
            name = "Sideloaded app \"$label\"",
            type = "Software · Installed outside an official app store",
            cat = Category.SOFTWARE,
            sev = if (risk >= 85) Severity.CRITICAL else if (risk >= 70) Severity.HIGH else Severity.MEDIUM,
            risk = risk,
            desc = "\"$label\" was installed from outside the Play Store and holds $permCount sensitive permission${if (permCount == 1) "" else "s"}, $riskyCount of which look unnecessary for what it does.",
            advice = "Open App permissions to review exactly what \"$label\" can access, turn off anything it doesn't need, and uninstall it if you don't recognise it.",
            fix = "Review app permissions",
            pros = listOf(
                "Removes an unverified app's access to sensitive data",
                "Closes a common route malware uses to spread",
            ),
            cons = listOf("The app may lose features it legitimately used those permissions for"),
            source = "On-device install-source + permission audit",
            remedy = Remedy.AppSettings(packageName),
        )
    }

    private fun enabledAccessibilityPackages(): Set<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return emptySet()
        return runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** Real 0-100 heuristic from this one app's own signals — same style of scoring the dashboard's
     *  overall security score already uses, just scoped to a single app. Not a lookup against any
     *  external reputation service. */
    private fun safetyScoreFor(riskyCount: Int, totalPerms: Int, sideloaded: Boolean, isSystemApp: Boolean): Int {
        if (isSystemApp) return 100
        var score = 100 - riskyCount * 12 - (totalPerms - riskyCount).coerceAtMost(6) * 2
        if (sideloaded) score -= 20
        return score.coerceIn(0, 100)
    }

    private fun installerLabelOf(installerPackage: String?, isSystemApp: Boolean): String = when {
        isSystemApp -> "Preinstalled (system)"
        installerPackage == null -> "Unknown source (sideloaded)"
        installerPackage in TRUSTED_INSTALLERS -> KNOWN_STORE_LABELS[installerPackage] ?: installerPackage
        installerPackage == "com.google.android.packageinstaller" || installerPackage == "com.android.packageinstaller" ->
            "Installed manually (package installer)"
        else -> installerPackage
    }

    private fun installerOf(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    companion object {
        private val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
        )
        private val KNOWN_STORE_LABELS = mapOf(
            "com.android.vending" to "Google Play Store",
            "com.amazon.venezia" to "Amazon Appstore",
            "com.sec.android.app.samsungapps" to "Samsung Galaxy Store",
            "com.huawei.appmarket" to "Huawei AppGallery",
        )
    }
}
