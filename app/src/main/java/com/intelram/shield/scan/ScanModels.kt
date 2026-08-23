package com.intelram.shield.scan

enum class RiskLevel(val label: String) {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    CLEAN("Clean"),
}

data class Finding(
    val severity: RiskLevel,
    val title: String,
    val description: String,
)

data class ScannedApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val isSystemApp: Boolean,
    val installerPackageName: String?,
    val requestedPermissions: List<String>,
    val dangerousPermissions: List<String>,
    val sha256: String?,
    val findings: List<Finding>,
    val riskScore: Int,
    val riskLevel: RiskLevel,
)

data class DeviceCheck(
    val severity: RiskLevel,
    val title: String,
    val description: String,
)

data class ScanReport(
    val scannedAt: Long,
    val apps: List<ScannedApp>,
    val deviceChecks: List<DeviceCheck>,
) {
    val overallScore: Int
        get() {
            val appPenalty: Int = apps.map { app ->
                app.riskScore.coerceAtMost(100) / apps.size.coerceAtLeast(1)
            }.sum()
            val devicePenalty: Int = deviceChecks.map { check ->
                when (check.severity) {
                    RiskLevel.CRITICAL -> 25
                    RiskLevel.HIGH -> 15
                    RiskLevel.MEDIUM -> 8
                    RiskLevel.LOW -> 3
                    RiskLevel.CLEAN -> 0
                }
            }.sum()
            val worstApp: Int = apps.maxOfOrNull { it.riskScore } ?: 0
            val combined: Double = worstApp * 0.6 + appPenalty * 0.2 + devicePenalty * 0.2
            return (100.0 - combined).coerceIn(0.0, 100.0).toInt()
        }

    val flaggedAppCount: Int
        get() = apps.count { it.riskLevel != RiskLevel.CLEAN }
}
