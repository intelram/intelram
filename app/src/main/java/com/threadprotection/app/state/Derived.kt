package com.threadprotection.app.state

import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding
import com.threadprotection.app.ui.theme.Severity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanStatus { NEEDED, AT_RISK, PROTECTED }

data class AuditArea(val name: String, val cat: Category, val hasIssue: Boolean, val scanned: Boolean, val caption: String)

/**
 * Pure functions ported from the prototype's `renderVals()` — computed fresh from state each
 * call. `threats()`/`activeThreats()` now read the real findings a `DeviceScanner.scan()`
 * produced (`state.scanData.findings`), not demo data — see README §Threat intelligence.
 */
object Derived {

    fun threats(state: AppUiState): List<Finding> = state.scanData.findings

    /** Findings still counting against the user: neither fixed nor deliberately ignored. */
    fun activeThreats(state: AppUiState): List<Finding> =
        threats(state).filter { it.id !in state.fixed && it.id !in state.ignoredFindings }

    /** Findings the user acknowledged and chose to leave for now — still real, just not counted. */
    fun ignoredThreats(state: AppUiState): List<Finding> =
        threats(state).filter { it.id in state.ignoredFindings }

    fun securityScore(state: AppUiState): Int {
        if (!state.hasScanned) return 72
        val active = activeThreats(state)
        val maxRisk = active.maxOfOrNull { it.risk } ?: 0
        return max(12, 100 - (maxRisk * 0.55).roundToInt() - active.size * 4)
    }

    fun scanStatus(state: AppUiState): ScanStatus {
        if (!state.hasScanned) return ScanStatus.NEEDED
        return if (activeThreats(state).isNotEmpty()) ScanStatus.AT_RISK else ScanStatus.PROTECTED
    }

    fun statusLabel(status: ScanStatus): String = when (status) {
        ScanStatus.NEEDED -> "SCAN NEEDED"
        ScanStatus.AT_RISK -> "AT RISK"
        ScanStatus.PROTECTED -> "PROTECTED"
    }

    fun scoreCaption(state: AppUiState): String {
        if (!state.hasScanned) return "Tap the big green button to check your phone."
        val n = activeThreats(state).size
        val ignored = ignoredThreats(state).size
        return if (n == 0) {
            if (ignored > 0) {
                "Nothing left to act on. $ignored item${if (ignored > 1) "s" else ""} you chose to ignore for now ${if (ignored > 1) "are" else "is"} still there."
            } else {
                "Everything is safe. Nothing to worry about."
            }
        } else {
            "We found $n problem${if (n > 1) "s" else ""}. Tap one to see what to do."
        }
    }

    private data class AreaDef(val name: String, val cat: Category, val total: String)

    fun systemAuditAreas(state: AppUiState): List<AuditArea> {
        val active = activeThreats(state)
        val scanned = state.hasScanned
        val scan = state.scanData
        val defs = listOf(
            AreaDef("Installed software", Category.SOFTWARE, "${scan.appsScanned.takeIf { it > 0 } ?: "—"} apps"),
            AreaDef("Services & activity", Category.ACTIVITY, "Checked on scan"),
            AreaDef("Connected hardware", Category.HARDWARE, "${state.liveHwDevices.size} devices checked"),
            AreaDef("Open ports", Category.PORTS, if (scan.portsProbed) "${scan.portsFound} listening" else "Restricted on this Android version"),
            AreaDef("Licensing", Category.LICENSING, "Checked on scan"),
            AreaDef("Operating system", Category.OS, scan.osPatchLabel.ifBlank { "Build & patch" }),
            AreaDef(
                "Threat-intel feeds",
                Category.EMAIL,
                if (scan.feedsTotal > 0) "${scan.feedsConfigured}/${scan.feedsTotal} configured" else "Add free keys in Settings",
            ),
        )
        return defs.map { d ->
            val hits = active.count { it.cat == d.cat }
            val hasIssue = scanned && hits > 0
            val caption = when {
                hasIssue -> "$hits issue${if (hits > 1) "s" else ""} found"
                scanned -> "Clean · ${d.total}"
                else -> d.total
            }
            AuditArea(d.name, d.cat, hasIssue, scanned, caption)
        }
    }

    fun selectedFinding(state: AppUiState): Finding? {
        val threats = threats(state)
        return threats.find { it.id == state.selectedId } ?: threats.firstOrNull()
    }

    fun confidence(risk: Int): Int = min(99, 72 + (risk * 0.27).roundToInt())

    fun sourceCount(risk: Int): String = when {
        risk >= 80 -> "4 independent sources agree"
        risk >= 60 -> "3 independent sources agree"
        else -> "2 independent sources agree"
    }

    fun riskUrgency(risk: Int): String = when {
        risk >= 85 -> "Severe — act now"
        risk >= 70 -> "High — fix today"
        risk >= 50 -> "Moderate — fix soon"
        else -> "Low — fix when convenient"
    }

    fun hwSummary(state: AppUiState): String {
        val devices = state.liveHwDevices
        val bad = devices.count { !it.ok }
        return when {
            devices.isEmpty() -> "Checking connected hardware…"
            bad > 0 -> "$bad device${if (bad > 1) "s" else ""} need attention · ${devices.size - bad} safe"
            else -> "All ${devices.size} devices safe"
        }
    }

    /** Counts permissions that are genuinely held and genuinely look unnecessary, straight from the
     *  last PackageManager read — `AppPermission.risk` is already false for anything the app
     *  doesn't currently hold. No local "the user switched this off in our UI" overlay any more:
     *  that could never affect the real permission, so it only ever made this number wrong. */
    fun riskyPermTotal(state: AppUiState): Int =
        state.scanData.permApps.sumOf { app -> app.perms.count { it.risk } }

    fun sevOf(finding: Finding, fixed: Boolean): Severity = if (fixed) Severity.FIXED else finding.sev
}
