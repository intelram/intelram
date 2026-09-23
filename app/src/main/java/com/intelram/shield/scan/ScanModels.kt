package com.intelram.shield.scan

import java.util.UUID

enum class RiskLevel(val label: String) {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    CLEAN("Clean"),
}

private fun RiskLevel.weight(): Int = when (this) {
    RiskLevel.CRITICAL -> 4
    RiskLevel.HIGH -> 3
    RiskLevel.MEDIUM -> 2
    RiskLevel.LOW -> 1
    RiskLevel.CLEAN -> 0
}

enum class FindingCategory(val label: String) {
    APP("App"),
    PRIVACY("Privacy"),
    NETWORK("Network"),
}

/** What the "Fix Now" action on a finding's detail screen should actually do. */
sealed class FixAction {
    data object None : FixAction()
    data class UninstallApp(val packageName: String) : FixAction()
    data class OpenSystemSettings(val settingsAction: String) : FixAction()
}

data class Finding(
    val id: String = UUID.randomUUID().toString(),
    val severity: RiskLevel,
    val category: FindingCategory,
    val title: String,
    val description: String,
    val whyItMatters: String,
    val fixAction: FixAction = FixAction.None,
    val sourceApp: String? = null,
)

/**
 * A stable identity for a finding that survives across scans, unlike [Finding.id]
 * (a fresh random UUID every scan). Used to remember which findings a user has
 * told the app aren't threats, so they stay dismissed on the next scan.
 */
val Finding.signature: String
    get() = "${category.name}|${sourceApp.orEmpty()}|$title"

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

data class ScanReport(
    val scannedAt: Long,
    val apps: List<ScannedApp>,
    val deviceFindings: List<Finding>,
) {
    val allFindings: List<Finding>
        get() = (apps.flatMap { it.findings } + deviceFindings)
            .sortedByDescending { it.severity.weight() }

    fun findingById(id: String): Finding? = allFindings.firstOrNull { it.id == id }

    val overallScore: Int
        get() {
            val appPenalty: Int = apps.map { app ->
                app.riskScore.coerceAtMost(100) / apps.size.coerceAtLeast(1)
            }.sum()
            val devicePenalty: Int = deviceFindings.map { finding ->
                when (finding.severity) {
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

    val totalFlaggedCount: Int
        get() = allFindings.size
}
