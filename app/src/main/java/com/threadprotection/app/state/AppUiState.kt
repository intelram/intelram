package com.threadprotection.app.state

import com.threadprotection.app.data.Account
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.HwDevice
import com.threadprotection.app.data.HwSim
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.network.UrlVerdict
import com.threadprotection.app.ui.theme.TpThemeMode

enum class Screen { SIGNIN, CREATE_ACCOUNT, ONBOARDING, DASHBOARD, SCANNING, RESULTS, DETAIL, QR, BRAIN, PERMS, SETTINGS }

enum class QrPhase { IDLE, SCANNING, RESULT }

enum class Vote { UP, DOWN }

enum class HwHandled { BLOCK, ALLOW }

data class ProtectionSettings(
    val autoScan: Boolean = true,
    val breach: Boolean = true,
    val downloads: Boolean = true,
    val phishing: Boolean = true,
    val hardware: Boolean = true,
)

/** Real, on-device results from the last completed `DeviceScanner.scan()` — see README §Threat intelligence. */
data class ScanData(
    val findings: List<Finding> = emptyList(),
    val permApps: List<PermApp> = emptyList(),
    val hwDevices: List<HwDevice> = emptyList(),
    val appsScanned: Int = 0,
    val portsFound: Int = 0,
    val portsProbed: Boolean = false,
    val osPatchLabel: String = "",
    val feedsConfigured: Int = 0,
    val feedsTotal: Int = 0,
)

/** Live phase text shown on the Scanning screen while `DeviceScanner.scan()` runs. */
data class ScanPhaseState(val index: Int = 0, val total: Int = 7, val label: String = "", val meta: String = "")

/** Mirrors the prototype's `state = {...}` object — see README §State — extended with real scan/auth/API-key state. */
data class AppUiState(
    val screen: Screen = Screen.SIGNIN,
    val account: Account? = null,
    val progress: Float = 0f,
    val scannedCount: Int = 0,
    val fixed: Set<String> = emptySet(),
    val realtime: Boolean = true,
    val settings: ProtectionSettings = ProtectionSettings(),
    val selectedId: String? = null,
    val hasScanned: Boolean = false,
    val qrPhase: QrPhase = QrPhase.IDLE,
    val qrIndex: Int = 0,
    val qrProgress: Int = 0,
    val qrVerdict: UrlVerdict? = null,
    val gsiError: String? = null,
    val theme: TpThemeMode = TpThemeMode.NIGHT,
    val learned: Int = 148_392,
    val votes: Map<String, Vote> = emptyMap(),
    val hwAlert: HwSim? = null,
    val hwIdx: Int = 0,
    val hwHandled: HwHandled? = null,
    val permOff: Set<String> = emptySet(),
    val hwOpen: Boolean = false,
    val blocked: Long = 41_827_384,
    val tickIdx: Int = 0,
    val apiKeys: ApiKeys = ApiKeys(),
    val scanData: ScanData = ScanData(),
    val scanPhase: ScanPhaseState = ScanPhaseState(),
    val liveHwDevices: List<HwDevice> = emptyList(),
    val createAccountError: String? = null,
)
