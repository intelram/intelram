package com.threadprotection.app.state

import com.threadprotection.app.chat.BtChatConnState
import com.threadprotection.app.chat.BtDeviceInfo
import com.threadprotection.app.chat.ChatHistoryEntry
import com.threadprotection.app.chat.ChatMode
import com.threadprotection.app.chat.ChatUiMessage
import com.threadprotection.app.data.Account
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.HwDevice
import com.threadprotection.app.data.HwSim
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.network.BreachCheckResult
import com.threadprotection.app.network.UrlVerdict
import com.threadprotection.app.scan.PortFinding
import com.threadprotection.app.ui.theme.TpThemeMode

enum class Screen {
    SPLASH, SIGNIN, CREATE_ACCOUNT, ONBOARDING, DASHBOARD, SCANNING, RESULTS, DETAIL, QR, BRAIN, PERMS, SETTINGS,
    OTP_SECURITY, DATA_BREACH, SCAN_WEBSITE, HARDWARE_DETAIL, PORTS_DETAIL, OS_DETAIL, CHAT, CHAT_CONVERSATION,
    CHAT_HISTORY, APP_PERMISSION_DETAIL,
}

enum class QrPhase { IDLE, SCANNING, RESULT }

enum class Vote { UP, DOWN }

enum class HwHandled { BLOCK, ALLOW }

enum class ScanFrequency { DAILY, WEEKLY }

data class ProtectionSettings(
    val autoScan: Boolean = true,
    val breach: Boolean = true,
    val downloads: Boolean = true,
    val phishing: Boolean = true,
    val hardware: Boolean = true,
    val scanHour: Int = 3,
    val scanMinute: Int = 0,
    val scanFrequency: ScanFrequency = ScanFrequency.DAILY,
    /** java.util.Calendar.SUNDAY(1)..SATURDAY(7) — only used when scanFrequency == WEEKLY. */
    val scanDayOfWeek: Int = java.util.Calendar.MONDAY,
)

/** Real, on-device results from the last completed `DeviceScanner.scan()` — see README §Threat intelligence. */
data class ScanData(
    val findings: List<Finding> = emptyList(),
    val permApps: List<PermApp> = emptyList(),
    val hwDevices: List<HwDevice> = emptyList(),
    val appsScanned: Int = 0,
    val ports: List<PortFinding> = emptyList(),
    val portsFound: Int = 0,
    val portsProbed: Boolean = false,
    val osPatchLabel: String = "",
    val feedsConfigured: Int = 0,
    val feedsTotal: Int = 0,
)

/** Live phase text shown on the Scanning screen while `DeviceScanner.scan()` runs. */
data class ScanPhaseState(val index: Int = 0, val total: Int = 7, val label: String = "", val meta: String = "")

/** One real item as it's checked during a scan — package label + package name, a port, a hardware entry, etc. */
data class ScanFeedEntry(val id: Long, val text: String)

/** Mirrors the prototype's `state = {...}` object — see README §State — extended with real scan/auth/API-key state. */
data class AppUiState(
    val screen: Screen = Screen.SPLASH,
    val account: Account? = null,
    val progress: Float = 0f,
    val scannedCount: Int = 0,
    val fixed: Set<String> = emptySet(),
    val realtime: Boolean = true,
    val settings: ProtectionSettings = ProtectionSettings(),
    val selectedId: String? = null,
    /** Package name of the app currently shown on AppPermissionDetailScreen. */
    val selectedPermApp: String? = null,
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
    /** Rolling window of the most recent real items the live scan has actually checked, newest first. */
    val scanFeed: List<ScanFeedEntry> = emptyList(),
    val liveHwDevices: List<HwDevice> = emptyList(),
    val createAccountError: String? = null,
    val breachResult: BreachCheckResult? = null,
    val breachChecking: Boolean = false,
    val websiteUrl: String = "",
    val websiteVerdict: UrlVerdict? = null,
    val websiteChecking: Boolean = false,
    val chatMode: ChatMode = ChatMode.BLUETOOTH,
    val btConnState: BtChatConnState = BtChatConnState.IDLE,
    val btDiscoveredDevices: List<BtDeviceInfo> = emptyList(),
    val chatHistory: List<ChatHistoryEntry> = emptyList(),
    /** Set when a conversation was opened from History for messaging (not necessarily a live
     *  connection) — this is who sendChatMessage() addresses a mesh-relayed message to when
     *  there's no live socket. Cleared on disconnect/leave. */
    val chatMeshPeer: ChatHistoryEntry? = null,
    val chatPeerName: String? = null,
    val chatMessages: List<ChatUiMessage> = emptyList(),
    val chatPeerTyping: Boolean = false,
    val chatDraft: String = "",
)
