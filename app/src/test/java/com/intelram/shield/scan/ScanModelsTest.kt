package com.intelram.shield.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testApp(
    riskScore: Int,
    riskLevel: RiskLevel = RiskLevel.CLEAN,
    findings: List<Finding> = emptyList(),
) = ScannedApp(
    packageName = "com.example.test",
    appName = "Test App",
    versionName = "1.0",
    isSystemApp = false,
    installerPackageName = null,
    requestedPermissions = emptyList(),
    dangerousPermissions = emptyList(),
    sha256 = null,
    findings = findings,
    riskScore = riskScore,
    riskLevel = riskLevel,
)

private fun testFinding(severity: RiskLevel, title: String = "Test finding") = Finding(
    severity = severity,
    category = FindingCategory.APP,
    title = title,
    description = "desc",
    whyItMatters = "why",
)

class ScanModelsTest {

    @Test
    fun `an empty report scores a perfect 100 with nothing flagged`() {
        val report = ScanReport(scannedAt = 0L, apps = emptyList(), deviceFindings = emptyList())
        assertEquals(100, report.overallScore)
        assertEquals(0, report.flaggedAppCount)
        assertEquals(0, report.totalFlaggedCount)
        assertTrue(report.allFindings.isEmpty())
    }

    @Test
    fun `a single high-risk app pulls the overall score down`() {
        val report = ScanReport(
            scannedAt = 0L,
            apps = listOf(testApp(riskScore = 80, riskLevel = RiskLevel.HIGH)),
            deviceFindings = emptyList(),
        )
        // worstApp=80, appPenalty=80/1=80, devicePenalty=0
        // combined = 80*0.6 + 80*0.2 = 64 -> score = 100-64 = 36
        assertEquals(36, report.overallScore)
    }

    @Test
    fun `a critical device finding alone has a smaller effect than a risky app`() {
        val report = ScanReport(
            scannedAt = 0L,
            apps = emptyList(),
            deviceFindings = listOf(testFinding(RiskLevel.CRITICAL)),
        )
        // worstApp=0, appPenalty=0, devicePenalty=25 -> combined = 25*0.2 = 5 -> score = 95
        assertEquals(95, report.overallScore)
    }

    @Test
    fun `flaggedAppCount only counts apps that are not clean`() {
        val report = ScanReport(
            scannedAt = 0L,
            apps = listOf(
                testApp(riskScore = 0, riskLevel = RiskLevel.CLEAN),
                testApp(riskScore = 40, riskLevel = RiskLevel.MEDIUM),
                testApp(riskScore = 90, riskLevel = RiskLevel.CRITICAL),
            ),
            deviceFindings = emptyList(),
        )
        assertEquals(2, report.flaggedAppCount)
    }

    @Test
    fun `allFindings are sorted most severe first, across apps and device findings`() {
        val low = testFinding(RiskLevel.LOW, "low")
        val critical = testFinding(RiskLevel.CRITICAL, "critical")
        val medium = testFinding(RiskLevel.MEDIUM, "medium")
        val high = testFinding(RiskLevel.HIGH, "high")

        val report = ScanReport(
            scannedAt = 0L,
            apps = listOf(testApp(riskScore = 50, findings = listOf(low, critical, medium))),
            deviceFindings = listOf(high),
        )

        assertEquals(
            listOf("critical", "high", "medium", "low"),
            report.allFindings.map { it.title },
        )
        assertEquals(4, report.totalFlaggedCount)
    }

    @Test
    fun `findingById looks up a finding across apps and device findings, or returns null`() {
        val target = testFinding(RiskLevel.HIGH, "findable")
        val report = ScanReport(
            scannedAt = 0L,
            apps = listOf(testApp(riskScore = 50, findings = listOf(target))),
            deviceFindings = listOf(testFinding(RiskLevel.LOW, "other")),
        )

        assertEquals(target.id, report.findingById(target.id)?.id)
        assertNull(report.findingById("does-not-exist"))
    }
}
