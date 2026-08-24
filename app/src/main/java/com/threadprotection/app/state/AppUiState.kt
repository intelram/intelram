package com.threadprotection.app.state

import com.threadprotection.app.data.Account
import com.threadprotection.app.data.HwSim
import com.threadprotection.app.ui.theme.TpThemeMode

enum class Screen { SIGNIN, ONBOARDING, DASHBOARD, SCANNING, RESULTS, DETAIL, QR, BRAIN, PERMS, SETTINGS }

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

/** Mirrors the prototype's `state = {...}` object 1:1 — see README §State. */
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
)
