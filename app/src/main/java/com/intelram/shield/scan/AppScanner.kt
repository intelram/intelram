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
class AppScanner(private val context: Context) {

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

            if (packageName in ThreatIntel.knownMalwarePackageNames) {
                findings += Finding(
                    RiskLevel.CRITICAL,
                    "Matches known-malware package name",
                    "Package name matches an entry in the on-device threat-intel list.",
                )
            }

            if (sha256 != null && sha256 in ThreatIntel.knownMalwareSha256) {
                findings += Finding(
                    RiskLevel.CRITICAL,
                    "Matches known-malware APK hash",
                    "SHA-256 of the installed APK matches a known-malicious sample.",
                )
            }

            for ((label, combo) in ThreatIntel.riskyPermissionCombos) {
                if (dangerous.toSet().containsAll(combo)) {
                    findings += Finding(
                        RiskLevel.HIGH,
                        "Risky permission combination: $label",
                        "Requests all of: ${combo.joinToString { it.substringAfterLast('.') }}.",
                    )
                }
            }

            if (!isSystemApp && installer == null && dangerous.isNotEmpty()) {
                findings += Finding(
                    RiskLevel.MEDIUM,
                    "Sideloaded app with sensitive permissions",
                    "Installed outside of a known app store and requests ${dangerous.size} " +
                        "sensitive permission(s).",
                )
            }

            if (!isSystemApp && dangerous.size >= 6) {
                findings += Finding(
                    RiskLevel.MEDIUM,
                    "Broad permission footprint",
                    "Requests ${dangerous.size} sensitive permissions, well above typical apps.",
                )
            }

            val score = computeRiskScore(isSystemApp, dangerous.size, findings)
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
                findings = findings,
                riskScore = score,
                riskLevel = level,
            )
        }.sortedByDescending { it.riskScore }
    }

    private fun computeRiskScore(
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

    private fun riskLevelForScore(score: Int): RiskLevel = when {
        score >= 80 -> RiskLevel.CRITICAL
        score >= 55 -> RiskLevel.HIGH
        score >= 30 -> RiskLevel.MEDIUM
        score >= 10 -> RiskLevel.LOW
        else -> RiskLevel.CLEAN
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
