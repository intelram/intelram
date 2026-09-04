package com.threadprotection.app.state

import com.threadprotection.app.chat.BtCallState
import com.threadprotection.app.chat.BtChatConnState
import com.threadprotection.app.chat.BtDeviceInfo
import com.threadprotection.app.chat.ChatHistoryEntry
import com.threadprotection.app.chat.ChatMode
import com.threadprotection.app.chat.ChatSession
import com.threadprotection.app.chat.ChatUiMessage
import com.threadprotection.app.chat.IncomingChatRequest
import com.threadprotection.app.data.Account
import com.threadprotection.app.data.Category
import com.threadprotection.app.hardware.DeviceTrust
import com.threadprotection.app.hardware.ExternalDevice
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.HwDevice
import com.threadprotection.app.data.HwSim
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.network.BreachCheckResult
import com.threadprotection.app.network.UrlVerdict
import com.threadprotection.app.qr.QrAnalysis
import com.threadprotection.app.scan.PortFinding
import com.threadprotection.app.ui.theme.TpThemeMode

enum class Screen {
    SPLASH, SIGNIN, CREATE_ACCOUNT, ONBOARDING, DASHBOARD, SCANNING, RESULTS, DETAIL, QR, BRAIN, PERMS, SETTINGS,
    OTP_SECURITY, DATA_BREACH, SCAN_WEBSITE, HARDWARE_DETAIL, PORTS_DETAIL, OS_DETAIL, CHAT, CHAT_CONVERSATION,
    CHAT_HISTORY, CHAT_SESSION, APP_PERMISSION_DETAIL,
    /** Analyst Mode (CVE/EPSS/KEV vulnerability intelligence) — reached from Settings → "Analyst
     *  Tools". Routed the same way as every other screen so the existing back-stack keeps working
     *  for free, but the screens themselves are backed by their own Hilt `CveViewModel`, not this
     *  `AppViewModel` — see analyst/presentation/cve/CveViewModel.kt and
     *  GRAPH_ENGINEERING_MAP.md §10. */
    ANALYST_CVE_SEARCH, ANALYST_CVE_DETAIL;

    /** Screens that make up the Chat feature — while on any of them the BLE advertiser and the
     *  RFCOMM listener should be running; leaving all of them must tear them down. */
    val isChatFeature: Boolean
        get() = this == CHAT || this == CHAT_CONVERSATION || this == CHAT_HISTORY || this == CHAT_SESSION
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
    /** Screens visited before this one, oldest first — drives real Back navigation so leaving a
     *  detail screen returns where the user came from rather than always jumping to the Dashboard. */
    val backStack: List<Screen> = emptyList(),
    val account: Account? = null,
    val progress: Float = 0f,
    val scannedCount: Int = 0,
    /** Findings the user has marked resolved *and* whose stored fingerprint still matches what
     *  this scan found. Seeded from disk at startup and after every scan, so a resolved threat
     *  stays resolved across scans and app restarts — see FindingIdentity. */
    val fixed: Set<String> = emptySet(),
    /** The live (non-retired) id → fingerprint records on disk, kept so a scan can tell "resolved
     *  and unchanged" from "resolved earlier but the problem is back". A record retired by
     *  [everResolvedIds] is absent here, so it no longer suppresses anything. */
    val resolvedRecords: Map<String, String> = emptyMap(),
    /** Category of each stored record, so a scan can tell whether it was in a position to conclude
     *  the problem is gone — see FindingIdentity.clearedRecords. */
    val resolvedCategories: Map<String, Category> = emptyMap(),
    /** Every finding id that has ever been resolved, including retired records. This is the memory
     *  behind "this threat is back": a re-emergence is reported as a new active threat, but the app
     *  can still say the user has dealt with it before. */
    val everResolvedIds: Set<String> = emptySet(),
    /** Findings active right now that the user had resolved previously — flagged in the UI so a
     *  return is visibly distinct from a first-time discovery. */
    val reEmergedIds: Set<String> = emptySet(),
    /** True while a fix is being applied (the user has been sent to the relevant Settings screen
     *  and hasn't come back yet) — drives the "fixing in progress" state of the Start Fixing
     *  button rather than a timer or a guess. */
    val fixInProgressId: String? = null,
    /** Findings the user chose "Ignore for now" *and* whose stored fingerprint still matches what
     *  this scan found. Seeded from disk at startup and after every scan, so an ignored threat
     *  stays out of the active list across scans and app restarts — mirrors [fixed] exactly, see
     *  FindingIdentity. Kept separate from [fixed] because ignoring a finding is not the same user
     *  intent as resolving it, and the UI says so; the durability guarantee is the same for both. */
    val ignoredFindings: Set<String> = emptySet(),
    /** The live (non-retired) id → fingerprint records on disk for ignored findings — mirrors
     *  [resolvedRecords] but for "Ignore for now". */
    val ignoredRecords: Map<String, String> = emptyMap(),
    /** Category of each stored ignored record — mirrors [resolvedCategories]. */
    val ignoredCategories: Map<String, Category> = emptyMap(),
    /** Every finding id that has ever been ignored, including retired records — mirrors [everResolvedIds]. */
    val everIgnoredIds: Set<String> = emptySet(),
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
    /** On-device classification of the decoded payload — what it is, its parsed fields, and
     *  whether anything was sent off the phone. Null until something is scanned. */
    val qrAnalysis: QrAnalysis? = null,
    val qrTorchOn: Boolean = false,
    val gsiError: String? = null,
    val theme: TpThemeMode = TpThemeMode.NIGHT,
    val learned: Int = 148_392,
    val votes: Map<String, Vote> = emptyMap(),
    val hwAlert: HwSim? = null,
    /** Every external device seen connecting this session, newest first — real USB/Bluetooth
     *  events from the OS, not a canned list. See hardware/ExternalDeviceMonitor. */
    val externalDevices: List<ExternalDevice> = emptyList(),
    /** The device currently awaiting the user's Block / Allow-once decision, or null. */
    val deviceAlert: ExternalDevice? = null,
    /** Per-device decisions for this session, keyed by the device's stable id. */
    val deviceTrust: Map<String, DeviceTrust> = emptyMap(),
    /** True when Bluetooth connections can be seen but not identified because BLUETOOTH_CONNECT
     *  isn't granted — surfaced so the UI can say so rather than imply all-clear. */
    val bluetoothWatchBlind: Boolean = false,
    val hwIdx: Int = 0,
    val hwHandled: HwHandled? = null,
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
    /** Whether the BLE radio is scanning right now. Deliberately separate from [btConnState]: a
     *  scan starting, stopping or failing must never be able to overwrite — or be displayed as —
     *  a real connection status. See BluetoothChatManager.isScanning. */
    val btScanning: Boolean = false,
    /** Plain-language reason the last connection attempt failed, shown with a Retry action. */
    val btFailureReason: String? = null,
    /** null = not determined yet; false = this phone isn't broadcasting its presence, so other
     *  devices can't find it (it can still find others) — see BluetoothChatManager. */
    val btCanAdvertise: Boolean? = null,
    /** Address of the device the user last tapped Connect on, so only that row reflects the
     *  connecting/failed state rather than every row changing at once. */
    val btConnectingAddress: String? = null,
    /** Recoverable failure to show the user instead of crashing — set by the ViewModel's coroutine
     *  crash guard. Null when there's nothing wrong. */
    val chatError: String? = null,
    /** An incoming chat request awaiting this user's Accept/Deny, shown in-app (never as a fake
     *  system notification). Null when no request is pending. */
    val incomingChatRequest: IncomingChatRequest? = null,
    /** True when [btCanAdvertise] is false specifically because BLUETOOTH_ADVERTISE was denied —
     *  a user-fixable cause, unlike hardware that simply can't do BLE peripheral mode. */
    val btAdvertisePermissionMissing: Boolean = false,
    val chatHistory: List<ChatHistoryEntry> = emptyList(),
    /** Saved transcripts, newest first — persisted, survives restarting the app. */
    val chatSessions: List<ChatSession> = emptyList(),
    /** The session currently being read from History (null when not viewing one). */
    val viewingSession: ChatSession? = null,
    /** Identity of the live conversation being recorded, so incremental saves update one entry. */
    val activeSessionId: String? = null,
    val activeSessionStartedAtMs: Long = 0L,
    /** Set when a conversation was opened from History for messaging (not necessarily a live
     *  connection) — this is who sendChatMessage() addresses a mesh-relayed message to when
     *  there's no live socket. Cleared on disconnect/leave. */
    val chatMeshPeer: ChatHistoryEntry? = null,
    val chatPeerName: String? = null,
    val chatMessages: List<ChatUiMessage> = emptyList(),
    /** Session verification code shown in the conversation header — see BluetoothChatManager. */
    val chatSafetyCode: String? = null,
    val chatPeerTyping: Boolean = false,
    val chatDraft: String = "",
    /** Voice call layered on top of the live chat connection — see BluetoothChatManager's call
     *  functions and BtCallState's doc. The caller/callee's name is [chatPeerName]; a call cannot
     *  exist without an already-CONNECTED chat, so there's no separate peer-name field for it. */
    val callState: BtCallState = BtCallState.IDLE,
    val callMuted: Boolean = false,
    /** A one-line reason shown briefly after a call ends ("They declined the call.", "Call
     *  ended.") — null once dismissed. Never describes an ordinary local hangup, only something
     *  the user didn't just do themselves. */
    val callEndedReason: String? = null,
)
