package com.intelram.shield.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val progress: Int, val stepLabel: String) : ScanUiState
    data class Done(val report: ScanReport) : ScanUiState
}

private const val PREFS_NAME = "threat_protection_prefs"
private const val KEY_REALTIME = "realtime_protection"

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)
    private val feedbackStore = FindingFeedbackStore(application)

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _realTimeProtection = MutableStateFlow(prefs.getBoolean(KEY_REALTIME, true))
    val realTimeProtection: StateFlow<Boolean> = _realTimeProtection.asStateFlow()

    private val _dismissedFindingCount = MutableStateFlow(feedbackStore.dismissedCount())
    val dismissedFindingCount: StateFlow<Int> = _dismissedFindingCount.asStateFlow()

    fun setRealTimeProtection(enabled: Boolean) {
        _realTimeProtection.value = enabled
        prefs.edit().putBoolean(KEY_REALTIME, enabled).apply()
    }

    private val scanSteps = listOf(
        22 to "Checking installed apps for malware…",
        40 to "Auditing app privacy permissions…",
        58 to "Checking your network connection…",
        88 to "Checking device and system settings…",
        100 to "Finishing up…",
    )

    /**
     * Runs the real scan on a background dispatcher while driving a smoothed
     * progress animation, so the UI feels consistent regardless of how fast
     * the underlying package enumeration actually completes.
     */
    fun startScan() {
        if (_uiState.value is ScanUiState.Scanning) return
        _uiState.value = ScanUiState.Scanning(0, scanSteps.first().second)

        viewModelScope.launch {
            val reportDeferred = viewModelScope.async(Dispatchers.Default) {
                val context = getApplication<Application>()
                val apps = AppScanner(context, feedbackStore).scanInstalledApps()
                val deviceFindings = DeviceScanner(context, feedbackStore).runChecks()
                ScanReport(
                    scannedAt = System.currentTimeMillis(),
                    apps = apps,
                    deviceFindings = deviceFindings,
                )
            }

            var progress = 0
            while (progress < 99) {
                progress = (progress + 2).coerceAtMost(99)
                val label = scanSteps.first { progress <= it.first }.second
                _uiState.value = ScanUiState.Scanning(progress, label)
                delay(35)
            }

            val report = reportDeferred.await()
            _uiState.value = ScanUiState.Scanning(100, "Finishing up…")
            delay(200)
            _uiState.value = ScanUiState.Done(report)
        }
    }

    fun findApp(packageName: String): ScannedApp? {
        val state = _uiState.value
        return if (state is ScanUiState.Done) {
            state.report.apps.firstOrNull { it.packageName == packageName }
        } else {
            null
        }
    }

    fun findFinding(id: String): Finding? {
        val state = _uiState.value
        return if (state is ScanUiState.Done) state.report.findingById(id) else null
    }

    /**
     * Marks a finding as "not a threat": persists it by [signature] so it's
     * excluded from future scans, and immediately re-scores the current
     * report so the UI reflects it without requiring a rescan.
     */
    fun dismissFinding(id: String) {
        val state = _uiState.value
        if (state !is ScanUiState.Done) return
        val finding = state.report.findingById(id) ?: return

        feedbackStore.dismiss(finding.signature)
        _dismissedFindingCount.value = feedbackStore.dismissedCount()

        val updatedApps = state.report.apps.map { app ->
            val remaining = app.findings.filterNot { it.signature == finding.signature }
            if (remaining.size == app.findings.size) {
                app
            } else {
                val score = computeAppRiskScore(app.isSystemApp, app.dangerousPermissions.size, remaining)
                app.copy(findings = remaining, riskScore = score, riskLevel = riskLevelForScore(score))
            }
        }
        val updatedDeviceFindings = state.report.deviceFindings.filterNot { it.signature == finding.signature }

        _uiState.value = ScanUiState.Done(
            state.report.copy(apps = updatedApps, deviceFindings = updatedDeviceFindings),
        )
    }

    fun resetLearnedExceptions() {
        feedbackStore.clearAll()
        _dismissedFindingCount.value = 0
    }
}
