package com.threadprotection.app.state

import com.threadprotection.app.data.Category
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.data.Finding
import com.threadprotection.app.ui.theme.Severity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanStatus { NEEDED, AT_RISK, PROTECTED }

data class AuditArea(val name: String, val hasIssue: Boolean, val scanned: Boolean, val caption: String)

/**
 * Pure functions ported from the prototype's `renderVals()` — computed fresh from state each call,
 * same as the original React `renderVals()` being re-evaluated on every render.
 */
object Derived {

    /** The 9 master findings, with the email finding spliced in at index 2 once signed in. */
    fun threats(state: AppUiState): List<Finding> {
        val list = DemoData.masterThreats.toMutableList()
        val account = state.account
        if (account != null && list.isNotEmpty()) {
            list.add(min(2, list.size), DemoData.emailFinding(account.email))
        }
        return list
    }

    fun activeThreats(state: AppUiState): List<Finding> =
        threats(state).filter { it.id !in state.fixed }

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
        return if (n == 0) {
            "Everything is safe. Nothing to worry about."
        } else {
            "We found $n problem${if (n > 1) "s" else ""}. Tap one to see what to do."
        }
    }

    private data class AreaDef(val name: String, val cat: Category, val total: String)

    fun systemAuditAreas(state: AppUiState): List<AuditArea> {
        val active = activeThreats(state)
        val scanned = state.hasScanned
        val defs = listOf(
            AreaDef("Installed software", Category.SOFTWARE, "214 apps"),
            AreaDef("Services & activity", Category.ACTIVITY, "61 services"),
            AreaDef("Connected hardware", Category.HARDWARE, "6 devices checked"),
            AreaDef("Open ports", Category.PORTS, "65,535 probed"),
            AreaDef("Licensing", Category.LICENSING, "214 checked"),
            AreaDef("Operating system", Category.OS, "Build & patch"),
            AreaDef("Your email address", Category.EMAIL, if (state.account != null) "Signed-in email" else "Sign in to check"),
        )
        return defs.map { d ->
            val hits = active.count { it.cat == d.cat }
            val hasIssue = scanned && hits > 0
            val caption = when {
                hasIssue -> "$hits issue${if (hits > 1) "s" else ""} found"
                scanned -> "Clean · ${d.total}"
                else -> d.total
            }
            AuditArea(d.name, hasIssue, scanned, caption)
        }
    }

    fun selectedFinding(state: AppUiState): Finding {
        val threats = threats(state)
        return threats.find { it.id == state.selectedId }
            ?: threats.firstOrNull()
            ?: DemoData.masterThreats.first()
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

    fun hwSummary(): String {
        val bad = DemoData.hwDevices.count { !it.ok }
        return if (bad > 0) {
            "$bad device${if (bad > 1) "s" else ""} blocked · ${DemoData.hwDevices.size - bad} safe"
        } else {
            "All ${DemoData.hwDevices.size} devices safe"
        }
    }

    fun riskyPermTotal(state: AppUiState): Int =
        DemoData.appPerms.sumOf { app ->
            app.perms.count { it.risk && "${app.app}|${it.id}" !in state.permOff }
        }

    fun sevOf(finding: Finding, fixed: Boolean): Severity = if (fixed) Severity.FIXED else finding.sev
}
