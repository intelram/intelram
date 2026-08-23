package com.intelram.shield.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Scanning : ScanUiState
    data class Done(val report: ScanReport) : ScanUiState
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun startScan() {
        if (_uiState.value == ScanUiState.Scanning) return
        _uiState.value = ScanUiState.Scanning
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) {
                val context = getApplication<Application>()
                val apps = AppScanner(context).scanInstalledApps()
                val deviceChecks = DeviceScanner(context).runChecks()
                ScanReport(
                    scannedAt = System.currentTimeMillis(),
                    apps = apps,
                    deviceChecks = deviceChecks,
                )
            }
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
}
