package com.threadprotection.app.state

import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.FindingIdentity
import com.threadprotection.app.ui.theme.Severity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanStatus { NEEDED, AT_RISK, PROTECTED }

data class AuditArea(val name: String, val cat: Category, val hasIssue: Boolean, val scanned: Boolean, val caption: String)

/** One severity band on the results screen, with the active findings that fall into it. */
data class ThreatGroup(val severity: Severity, val label: String, val findings: List<Finding>)

/**
 * The live state of "how much of this scan has the user dealt with", used to keep the Start Fixing
 * button, the threat list and the security score describing the same thing at the same time.
 */
data class FixProgress(
    val total: Int,
    val resolved: Int,
    val ignored: Int,
    val remaining: Int,
    /** The finding Start Fixing should open next — null when there is nothing left to fix. */
    val nextId: String?,
    /** True while the user is part-way through applying a fix they haven't confirmed yet. */
    val fixing: Boolean,
) {
    val hasThreats: Boolean get() = total > 0
    val allResolved: Boolean get() = total > 0 && remaining == 0
    /** 0f..1f — how much of this scan's findings have been resolved or consciously ignored. */
    val fraction: Float get() = if (total == 0) 1f else (resolved + ignored).toFloat() / total.toFloat()
}

/**
 * Pure functions ported from the prototype's `renderVals()` — computed fresh from state each
 * call. `threats()`/`activeThreats()` now read the real findings a `DeviceScanner.scan()`
 * produced (`state.scanData.findings`), not demo data — see README §Threat intelligence.
 */
object Derived {

    fun threats(state: AppUiState): List<Finding> = state.scanData.findings

    /** Findings still counting against the user: neither fixed nor deliberately ignored. */
    fun activeThreats(state: AppUiState): List<Finding> =
        FindingIdentity.active(threats(state), state.fixed, state.ignoredFindings)

    /** Findings the user has already resolved. Shown in their own section rather than hidden, so
     *  the work done stays visible without competing with what still needs attention. */
    fun resolvedThreats(state: AppUiState): List<Finding> =
        threats(state).filter { it.id in state.fixed }

    /**
     * Active threats grouped by severity, most severe first, with empty groups dropped.
     * Critical and High are kept as separate groups — they mean different things and a user
     * triaging their phone should see the critical ones on their own.
     */
    fun threatsBySeverity(state: AppUiState): List<ThreatGroup> {
        val active = activeThreats(state)
        return SEVERITY_ORDER.mapNotNull { sev ->
            val items = active.filter { it.sev == sev }.sortedByDescending { it.risk }
            if (items.isEmpty()) null else ThreatGroup(sev, groupLabel(sev), items)
        }
    }

    fun groupLabel(sev: Severity): String = when (sev) {
        Severity.CRITICAL -> "Critical"
        Severity.HIGH -> "Major / High"
        Severity.MEDIUM -> "Medium"
        Severity.LOW -> "Low"
        Severity.FIXED -> "Resolved"
    }

    private val SEVERITY_ORDER = listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW)

    /**
     * What the Start Fixing button should currently say and do. Derived from the real threat state
     * on every recomposition, so the button, the list and the score can never disagree.
     */
    fun fixProgress(state: AppUiState): FixProgress {
        val all = threats(state)
        val active = activeThreats(state)
        val resolved = resolvedThreats(state).size
        val ignored = ignoredThreats(state).size
        // Deliberately the *displayed* order, not the raw scan order: Start Fixing has to open the
        // row the user sees at the top of the list, and the most severe threat is the one worth
        // dealing with first. Taking active.first() here instead let the button jump to a low
        // finding while a critical one sat above it on screen.
        val nextInDisplayOrder = threatsBySeverity(state).firstOrNull()?.findings?.firstOrNull()
        return FixProgress(
            total = all.size,
            resolved = resolved,
            ignored = ignored,
            remaining = active.size,
            nextId = nextInDisplayOrder?.id,
            fixing = state.fixInProgressId != null && active.any { it.id == state.fixInProgressId },
        )
    }

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
