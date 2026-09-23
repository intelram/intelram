package com.intelram.shield.scan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Enumerates installed packages and scores each one for risk using on-device
 * heuristics: dangerous-permission exposure, risky permission combinations, and
 * matches against [ThreatIntel]'s known-bad package name / APK hash lists.
 */
class AppScanner(private val context: Context, private val feedbackStore: FindingFeedbackStore) {

    fun scanInstalledApps(): List<ScannedApp> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        return packages.map { pkgInfo ->
            val appInfo: ApplicationInfo? = pkgInfo.applicationInfo
            val packageName = pkgInfo.packageName
            val appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName
            val isSystemApp = appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val installer = getInstallerPackageName(pm, packageName)
            val requestedPermissions = pkgInfo.requestedPermissions?.toList().orEmpty()
            val dangerous = requestedPermissions.filter { it in ThreatIntel.dangerousPermissions }
            val sha256 = appInfo?.sourceDir?.let { sha256OfFile(it) }

            val findings = mutableListOf<Finding>()
            val uninstall = FixAction.UninstallApp(packageName)

            if (packageName in ThreatIntel.knownMalwarePackageNames) {
                findings += Finding(
                    severity = RiskLevel.CRITICAL,
                    category = FindingCategory.APP,
                    title = "Matches a known-malware package name",
                    description = "\"$appName\" matches an entry in the on-device threat list.",
                    whyItMatters = "Apps that match known-malware naming patterns are frequently " +
                        "repackaged scams or trojans distributed outside official app stores.",
                    fixAction = uninstall,
                    sourceApp = appName,
                )
            }

            if (sha256 != null && sha256 in ThreatIntel.knownMalwareSha256) {
                findings += Finding(
                    severity = RiskLevel.CRITICAL,
                    category = FindingCategory.APP,
                    title = "Matches a known-malware APK signature",
                    description = "The installed APK's SHA-256 matches a known-malicious sample.",
                    whyItMatters = "A hash match means this exact file has already been " +
                        "identified as malicious elsewhere — this is a strong signal.",
                    fixAction = uninstall,
                    sourceApp = appName,
                )
            }

            for ((label, combo) in ThreatIntel.riskyPermissionCombos) {
                if (dangerous.toSet().containsAll(combo)) {
                    findings += Finding(
                        severity = RiskLevel.HIGH,
                        category = FindingCategory.PRIVACY,
                        title = "\"$appName\" has a risky permission combination",
                        description = "$label — requests all of: " +
                            combo.joinToString { it.substringAfterLast('.') } + ".",
                        whyItMatters = "This combination is a common pattern in banking trojans " +
                            "and SMS-fraud apps: individually the permissions look ordinary, but " +
                            "together they let an app act without your knowledge.",
                        fixAction = uninstall,
                        sourceApp = appName,
                    )
                }
            }

            if (!isSystemApp && installer == null && dangerous.isNotEmpty()) {
                findings += Finding(
                    severity = RiskLevel.MEDIUM,
                    category = FindingCategory.PRIVACY,
                    title = "\"$appName\" was sideloaded and requests sensitive permissions",
                    description = "Installed outside of a known app store and requests " +
                        "${dangerous.size} sensitive permission(s).",
                    whyItMatters = "Apps installed outside an app store skip the review process " +
                        "that usually catches obviously malicious behavior.",
                    fixAction = uninstall,
                    sourceApp = appName,
                )
            }

            if (!isSystemApp && dangerous.size >= 6) {
                findings += Finding(
                    severity = RiskLevel.MEDIUM,
                    category = FindingCategory.PRIVACY,
                    title = "\"$appName\" has a broad permission footprint",
                    description = "Requests ${dangerous.size} sensitive permissions, well above " +
                        "typical apps of its kind.",
                    whyItMatters = "The more sensitive permissions an app holds, the more it can " +
                        "do if it's ever compromised or turns out to be untrustworthy.",
                    fixAction = uninstall,
                    sourceApp = appName,
                )
            }

            // Findings the user has already told the app aren't threats don't
            // come back — that's the adaptive-learning effect in practice.
            val activeFindings = findings.filterNot { feedbackStore.isDismissed(it.signature) }
            val score = computeAppRiskScore(isSystemApp, dangerous.size, activeFindings)
            val level = riskLevelForScore(score)

            ScannedApp(
                packageName = packageName,
                appName = appName,
                versionName = pkgInfo.versionName,
                isSystemApp = isSystemApp,
                installerPackageName = installer,
                requestedPermissions = requestedPermissions,
                dangerousPermissions = dangerous,
                sha256 = sha256,
                findings = activeFindings,
                riskScore = score,
                riskLevel = level,
            )
        }.sortedByDescending { it.riskScore }
    }

    private fun getInstallerPackageName(pm: PackageManager, packageName: String): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun sha256OfFile(path: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Top-level (not private to [AppScanner]) so [com.intelram.shield.scan.ScanViewModel]
 * can re-run the same scoring after a user dismisses a finding, without a full rescan.
 */
internal fun computeAppRiskScore(
    isSystemApp: Boolean,
    dangerousPermissionCount: Int,
    findings: List<Finding>,
): Int {
    var score = 0

    for (finding in findings) {
        score += when (finding.severity) {
            RiskLevel.CRITICAL -> 45
            RiskLevel.HIGH -> 20
            RiskLevel.MEDIUM -> 12
            RiskLevel.LOW -> 5
            RiskLevel.CLEAN -> 0
        }
    }

    if (!isSystemApp) {
        score += (dangerousPermissionCount * 4).coerceAtMost(24)
    }

    return score.coerceIn(0, 100)
}

internal fun riskLevelForScore(score: Int): RiskLevel = when {
    score >= 80 -> RiskLevel.CRITICAL
    score >= 55 -> RiskLevel.HIGH
    score >= 30 -> RiskLevel.MEDIUM
    score >= 10 -> RiskLevel.LOW
    else -> RiskLevel.CLEAN
}
