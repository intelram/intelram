package com.threadprotection.app.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Base64
import android.util.Log
import com.threadprotection.app.chat.BluetoothChatManager
import com.threadprotection.app.chat.ChatEvent
import com.threadprotection.app.chat.ChatHistoryEntry
import com.threadprotection.app.chat.ChatMode
import com.threadprotection.app.chat.ChatSession
import com.threadprotection.app.chat.ChatSessionStatus
import com.threadprotection.app.chat.ChatUiMessage
import com.threadprotection.app.chat.IncomingChatRequest
import com.threadprotection.app.chat.MeshRelayManager
import com.threadprotection.app.data.Account
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.Breach
import com.threadprotection.app.data.Category
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.HwDevice
import com.threadprotection.app.data.Remedy
import com.threadprotection.app.hardware.ExternalDeviceMonitor
import com.threadprotection.app.qr.QrContentClassifier
import com.threadprotection.app.data.FindingIdentity
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.data.StoredBreach
import com.threadprotection.app.data.StoredChatHistoryEntry
import com.threadprotection.app.data.StoredChatMessage
import com.threadprotection.app.data.StoredChatSession
import com.threadprotection.app.data.StoredFinding
import com.threadprotection.app.data.StoredHwDevice
import com.threadprotection.app.data.StoredPortFinding
import com.threadprotection.app.data.StoredRemedy
import com.threadprotection.app.data.StoredScanData
import com.threadprotection.app.data.StoredSessionStatus
import com.threadprotection.app.service.NotificationHelper
import com.threadprotection.app.network.ThreatIntelRepository
import com.threadprotection.app.scan.DeviceScanner
import com.threadprotection.app.scan.HardwareWatcher
import com.threadprotection.app.scan.PermissionAudit
import com.threadprotection.app.scan.PortFinding
import com.threadprotection.app.ui.theme.Severity
import com.threadprotection.app.ui.theme.TpThemeMode
import com.threadprotection.app.ui.theme.systemThemeMode
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(
    private val appContext: Context? = null,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {

    // Seed with the device's own dark/light setting so the very first frame (splash included)
    // already matches the phone, instead of flashing Night until the settings DataStore flow
    // below resolves and overwrites it.
    private val _state = MutableStateFlow(AppUiState(theme = appContext?.let { systemThemeMode(it) } ?: TpThemeMode.NIGHT))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /**
     * Catches anything that escapes a coroutine started with [safeLaunch].
     *
     * Root cause this exists to fix: every background job in this class used a bare
     * `viewModelScope.launch {}`, which installs no CoroutineExceptionHandler, so a
     * throw inside any of them — a DataStore write failure, a malformed stored key reaching
     * `PqcChatCrypto.encapsulate()`, a serialization error — went to the thread's default uncaught
     * handler and killed the process. That is exactly what made Send and Chat History crash.
     *
     * This is deliberately *not* an empty catch: the failure is logged with its full stack trace
     * and surfaced to the user as a recoverable banner, so the app stays usable and the underlying
     * problem stays visible.
     */
    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        Log.e(TAG, "Background task failed", throwable)
        _state.update { it.copy(chatError = throwable.userMessage()) }
    }

    /** Use instead of `viewModelScope.launch` for anything that can fail — see [crashGuard]. */
    private fun safeLaunch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(crashGuard, block = block)

    fun dismissChatError() = _state.update { it.copy(chatError = null) }

    /** Turns an exception into something a user can act on, without inventing a cause it doesn't
     *  have. The full stack trace always goes to logcat under [TAG] regardless. */
    private fun Throwable.userMessage(): String = when (this) {
        is java.io.IOException -> "Couldn't save to this device's storage. Your messages are still on screen — try again."
        is SecurityException -> "A required permission was refused. Check the app's Bluetooth and nearby-devices permissions."
        is IllegalArgumentException -> "That contact's stored security key is unusable, so the message couldn't be sealed. Reconnect to them directly once to refresh it."
        else -> "Something went wrong (${this::class.java.simpleName}). The app is still usable — the details are in the logs."
    }

    /**
     * Completed the moment each on-disk record set has been read at least once since process
     * start. [startScan] awaits both before letting a scan conclude anything about resolved or
     * ignored findings.
     *
     * Root cause this exists to fix: `resolvedRecords`/`ignoredRecords` start out empty in
     * [AppUiState]'s default constructor, and the real values only arrive once the DataStore flows
     * collected in `init` emit for the first time — a suspending disk read that has not
     * necessarily completed yet. A scan launched immediately after cold start (the exact "turn the
     * phone on and scan" case) could race that first emission and run `withResolvedApplied()`
     * against a still-empty map, showing every previously fixed or ignored threat as active again
     * for that one scan. Awaiting these first closes the window; a DataStore read is milliseconds
     * against a scan that takes seconds, so this never adds a perceptible delay.
     */
    private val resolvedRecordsReady = CompletableDeferred<Unit>()
    private val ignoredRecordsReady = CompletableDeferred<Unit>()

    private val threatIntel = ThreatIntelRepository()
    private val deviceScanner by lazy { appContext?.let { DeviceScanner(it, threatIntel) } }
    private val permissionAudit by lazy { appContext?.let { PermissionAudit(it) } }
    private val hardwareWatcher by lazy { appContext?.let { HardwareWatcher(it) } }
    private val externalDeviceMonitor by lazy { appContext?.let { ExternalDeviceMonitor.getInstance(it) } }
    private val bluetoothChatManager by lazy {
        val ctx = appContext; val repo = settingsRepository
        if (ctx != null && repo != null) BluetoothChatManager.getInstance(ctx, repo) else null
    }

    /** Shared with ProtectionForegroundService (same process-wide instance via getInstance) —
     *  the service actually drives listening/gossiping in the background; this ViewModel just
     *  sends outbound messages and observes deliveries. See MeshRelayManager's own doc comment. */
    private val meshRelayManager by lazy {
        val ctx = appContext; val repo = settingsRepository
        if (ctx != null && repo != null) MeshRelayManager.getInstance(ctx, repo) else null
    }
    private var lastTypingSentAt = 0L

    private var scanJob: Job? = null
    /** Guards [triggerAutoScanOnce] so the automatic on-launch scan fires exactly once per app
     *  process, not on every later screen change that happens to touch Dashboard. */
    private var autoScanTriggeredThisLaunch = false
    private var qrJob: Job? = null
    private var permJob: Job? = null

    init {
        startAmbientTimers()
        settingsRepository?.let { repo ->
            safeLaunch {
                repo.themeFlow.distinctUntilChanged().collect { mode -> _state.update { it.copy(theme = mode) } }
            }
            safeLaunch {
                // distinctUntilChanged is essential, not cosmetic: DataStore re-emits the whole
                // Preferences object on EVERY write to ANY key, so without it this collector fired
                // again each time anything was saved.
                //
                // Root cause of "accepting a chat throws you back to Home": accepting writes chat
                // history and the session transcript, DataStore re-emitted, this collector ran with
                // an unchanged account and unconditionally forced screen = DASHBOARD — ripping the
                // user out of the conversation they had just joined. Any save did it: sending a
                // message, ignoring a finding, anything.
                repo.accountFlow.distinctUntilChanged().collect { account ->
                    if (account == null) return@collect
                    var enteredRealScanFromSplash = false
                    var landedOnDashboardFromSignIn = false
                    _state.update { s ->
                        // Only the sign-in transition navigates. Once the user is in the app, an
                        // account refresh must never move them off the screen they are on.
                        when (s.screen) {
                            // The common cold-start case: a returning user's persisted account
                            // loads (almost always) before the splash's own ~1.85s timer finishes.
                            // Stays on Screen.SPLASH and moves into its real-scan phase rather than
                            // jumping to Dashboard immediately — see runAutoScanOnSplash()'s doc for
                            // why the app-open experience must stay one continuous screen.
                            Screen.SPLASH -> {
                                enteredRealScanFromSplash = true
                                s.copy(account = account, splashPhase = SplashPhase.REAL_SCAN)
                            }
                            // A brand-new Google sign-in (bypassing onboarding) — a first-time,
                            // one-shot "just signed up" moment, not a reopen of the app, so this
                            // deliberately keeps the older Screen.SCANNING → Screen.RESULTS flow,
                            // exactly like completeOnboarding()'s demo-account equivalent below.
                            Screen.SIGNIN, Screen.CREATE_ACCOUNT -> {
                                landedOnDashboardFromSignIn = true
                                s.copy(account = account, screen = Screen.DASHBOARD)
                            }
                            else -> s.copy(account = account)
                        }
                    }
                    if (enteredRealScanFromSplash) triggerAutoScanOnce(AutoScanFlow.SPLASH_TO_DASHBOARD)
                    if (landedOnDashboardFromSignIn) triggerAutoScanOnce(AutoScanFlow.SCANNING_TO_RESULTS)
                }
            }
            safeLaunch {
                repo.apiKeysFlow.distinctUntilChanged().collect { keys -> _state.update { it.copy(apiKeys = keys) } }
            }
            safeLaunch {
                repo.realtimeFlow.distinctUntilChanged().collect { enabled ->
                    _state.update { it.copy(realtime = enabled) }
                    syncProtectionService(enabled)
                }
            }
            safeLaunch {
                repo.protectionSettingsFlow.distinctUntilChanged().collect { stored ->
                    val settings = stored.toState()
                    _state.update { it.copy(settings = settings) }
                    syncScheduledScan(settings)
                }
            }
            safeLaunch {
                repo.chatHistoryFlow.distinctUntilChanged().collect { stored ->
                    val history = stored
                        .map { ChatHistoryEntry(it.address, it.name, it.lastChattedAtMs, it.nodeId, it.publicKeyB64) }
                        .sortedByDescending { it.lastChattedAtMs }
                    _state.update { it.copy(chatHistory = history) }
                }
            }
            safeLaunch {
                repo.chatSessionsFlow.distinctUntilChanged().collect { stored ->
                    val sessions = stored.map { s ->
                        ChatSession(
                            sessionId = s.sessionId,
                            address = s.address,
                            name = s.name,
                            startedAtMs = s.startedAtMs,
                            endedAtMs = s.endedAtMs,
                            status = when (s.status) {
                                StoredSessionStatus.ACTIVE -> ChatSessionStatus.ACTIVE
                                StoredSessionStatus.COMPLETED -> ChatSessionStatus.COMPLETED
                                StoredSessionStatus.INTERRUPTED -> ChatSessionStatus.INTERRUPTED
                            },
                            messages = s.messages.map {
                                ChatUiMessage(it.id, it.text, it.fromMe, it.timestampMs, it.delivered, it.relayed)
                            },
                        )
                    }.sortedByDescending { it.startedAtMs }
                    _state.update { st ->
                        // Keep an open transcript in sync if the user is reading it.
                        val viewing = st.viewingSession?.let { v -> sessions.firstOrNull { it.sessionId == v.sessionId } }
                        st.copy(chatSessions = sessions, viewingSession = viewing ?: st.viewingSession)
                    }
                }
            }
        }
        externalDeviceMonitor?.let { monitor ->
            // Watching starts as soon as the app has a context: a malicious cable plugged in while
            // the user is on the dashboard is exactly the case that matters, so this isn't gated
            // behind opening some particular screen.
            monitor.start()
            safeLaunch {
                monitor.devices.collect { list -> _state.update { it.copy(externalDevices = list) } }
            }
            safeLaunch {
                monitor.pendingAlert.collect { device -> _state.update { it.copy(deviceAlert = device) } }
            }
            safeLaunch {
                monitor.trust.collect { map -> _state.update { it.copy(deviceTrust = map) } }
            }
            safeLaunch {
                monitor.bluetoothBlind.collect { blind -> _state.update { it.copy(bluetoothWatchBlind = blind) } }
            }
        }
        settingsRepository?.let { repo ->
            safeLaunch {
                // Resolved threats live on disk, so they survive a restart. Re-applying them
                // against the *current* findings on every emission is what makes "don't show it
                // again unless it comes back" true rather than just "hide it forever".
                repo.resolvedFindingsFlow.distinctUntilChanged().collect { records ->
                    // A retired ("cleared") record stops suppressing its finding but stays in
                    // everResolvedIds, so a return of the same problem is reported as a re-emergence
                    // rather than a first-time discovery.
                    val live = records.filterNot { it.cleared }
                    _state.update { st ->
                        st.copy(
                            resolvedRecords = live.associate { it.id to it.fingerprint },
                            resolvedCategories = records.mapNotNull { rec ->
                                runCatching { Category.valueOf(rec.category) }.getOrNull()?.let { rec.id to it }
                            }.toMap(),
                            everResolvedIds = records.map { it.id }.toSet(),
                        ).withResolvedApplied()
                    }
                    resolvedRecordsReady.complete(Unit)
                }
            }
            safeLaunch {
                // "Ignore for now" mirrors resolved threats exactly — persisted on disk so it
                // survives a rescan and an app restart, and re-applied against current findings on
                // every emission so a genuinely changed/worse ignored issue reactivates.
                repo.ignoredFindingsFlow.distinctUntilChanged().collect { records ->
                    val live = records.filterNot { it.cleared }
                    _state.update { st ->
                        st.copy(
                            ignoredRecords = live.associate { it.id to it.fingerprint },
                            ignoredCategories = records.mapNotNull { rec ->
                                runCatching { Category.valueOf(rec.category) }.getOrNull()?.let { rec.id to it }
                            }.toMap(),
                            everIgnoredIds = records.map { it.id }.toSet(),
                        ).withResolvedApplied()
                    }
                    ignoredRecordsReady.complete(Unit)
                }
            }
            safeLaunch {
                // Seeds the Dashboard with the last real scan's actual result so it shows that
                // score/status immediately on launch instead of "scan needed" until a brand new
                // scan finishes. Guarded by `!hasScanned` so this can only ever seed a cold-started
                // session — once a real scan runs (or this same seed already applied once), later
                // emissions of this flow (including the one caused by this session's own write-back
                // after its next scan) are left alone rather than replacing live, fresher state.
                repo.lastScanFlow.distinctUntilChanged().collect { stored ->
                    stored ?: return@collect
                    _state.update {
                        if (it.hasScanned) return@update it
                        val restored = stored.toDomain()
                        it.copy(
                            scanData = restored,
                            hasScanned = true,
                            scannedCount = restored.appsScanned,
                        ).withResolvedApplied()
                    }
                }
            }
        }
        bluetoothChatManager?.let { chat ->
            safeLaunch {
                chat.connState.collect { st -> _state.update { it.copy(btConnState = st) } }
            }
            safeLaunch {
                chat.discoveredDevices.collect { list -> _state.update { it.copy(btDiscoveredDevices = list) } }
            }
            safeLaunch {
                // Scanning is now published separately from the connection state, so the radar can
                // show "scanning" without a scan event ever being able to overwrite — or be
                // mistaken for — a real connection status. See BluetoothChatManager.isScanning.
                chat.isScanning.collect { on -> _state.update { it.copy(btScanning = on) } }
            }
            safeLaunch {
                chat.lastFailureReason.collect { why -> _state.update { it.copy(btFailureReason = why) } }
            }
            safeLaunch {
                chat.canAdvertise.collect { v -> _state.update { it.copy(btCanAdvertise = v) } }
            }
            safeLaunch {
                chat.advertisePermissionMissing.collect { v -> _state.update { it.copy(btAdvertisePermissionMissing = v) } }
            }
            safeLaunch {
                chat.sessionSafetyCode.collect { code -> _state.update { it.copy(chatSafetyCode = code) } }
            }
            safeLaunch {
                chat.callState.collect { st -> _state.update { it.copy(callState = st) } }
            }
            safeLaunch {
                chat.callMuted.collect { muted -> _state.update { it.copy(callMuted = muted) } }
            }
            safeLaunch {
                chat.connectedDeviceName.collect { name ->
                    _state.update { it.copy(chatPeerName = name) }
                    if (name != null) {
                        setScreen(Screen.CHAT_CONVERSATION)
                        val address = chat.connectedDeviceAddress.value
                        val nodeId = chat.connectedPeerNodeId.value.orEmpty()
                        val publicKeyB64 = chat.connectedPeerPublicKeyB64.value.orEmpty()
                        if (address != null) {
                            // A real connection just completed its handshake — this is the only
                            // place a session is opened, so "Connected" can never be shown for a
                            // socket that didn't get all the way through.
                            openChatSession(address, name)
                            // Also remember this contact as mesh-reachable (if the identity
                            // exchange succeeded) so the conversation can keep going via relay if
                            // the live connection later drops.
                            _state.update { it.copy(chatMeshPeer = ChatHistoryEntry(address, name, System.currentTimeMillis(), nodeId, publicKeyB64)) }
                            settingsRepository?.let { repo ->
                                safeLaunch { repo.recordChatHistory(address, name, nodeId, publicKeyB64) }
                            }
                        }
                    }
                }
            }
            safeLaunch {
                chat.events.collect { event ->
                    when (event) {
                        is ChatEvent.MessageReceived -> {
                            _state.update {
                                it.copy(
                                    chatMessages = it.chatMessages + ChatUiMessage(event.id, event.text, fromMe = false, timestampMs = event.atMs, delivered = true),
                                    chatPeerTyping = false,
                                )
                            }
                            // Save straight away: if the peer disappears a second from now, this
                            // message is already on disk rather than lost with the live session.
                            persistActiveSession(ChatSessionStatus.ACTIVE)
                        }
                        // `delivered` flips only here, driven by the peer's real ACK over the
                        // encrypted channel — never optimistically on send.
                        is ChatEvent.MessageDelivered -> {
                            _state.update {
                                it.copy(chatMessages = it.chatMessages.map { m -> if (m.id == event.id) m.copy(delivered = true) else m })
                            }
                            persistActiveSession(ChatSessionStatus.ACTIVE)
                        }
                        ChatEvent.PeerTyping -> {
                            _state.update { it.copy(chatPeerTyping = true) }
                            safeLaunch {
                                delay(3000)
                                _state.update { s -> if (s.chatPeerTyping) s.copy(chatPeerTyping = false) else s }
                            }
                        }
                        ChatEvent.PeerDisconnected -> {
                            // Unexpected drop: close the session as INTERRUPTED, keeping every
                            // message already exchanged. The user stays on the conversation so they
                            // can read the transcript; the banner shows the disconnected state.
                            closeChatSession(ChatSessionStatus.INTERRUPTED)
                            appContext?.let { NotificationHelper.cancelChatRequest(it) }
                            _state.update {
                                it.copy(chatPeerName = null, chatPeerTyping = false, incomingChatRequest = null)
                            }
                        }
                        is ChatEvent.ChatRequested -> {
                            _state.update {
                                it.copy(
                                    incomingChatRequest = IncomingChatRequest(
                                        requestId = event.requestId,
                                        displayName = event.displayName,
                                        receivedAtMs = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            // The in-app card only exists while Chat is open. Post a real
                            // heads-up notification with Accept/Deny buttons so the request
                            // reaches the user wherever they are — including with the app
                            // backgrounded, which is when it was being missed entirely.
                            appContext?.let { ctx ->
                                NotificationHelper.postChatRequest(ctx, event.displayName, event.requestId)
                            }
                        }
                        // Accepted on either side is the only route into an open conversation; the
                        // peer name flow (collected above) is what actually navigates there.
                        ChatEvent.ChatAccepted -> {
                            appContext?.let { NotificationHelper.cancelChatRequest(it) }
                            _state.update { it.copy(incomingChatRequest = null) }
                        }
                        ChatEvent.ChatDenied -> {
                            appContext?.let { NotificationHelper.cancelChatRequest(it) }
                            _state.update {
                            it.copy(
                                incomingChatRequest = null,
                                chatMeshPeer = null,
                                chatPeerName = null,
                                btConnectingAddress = null,
                                screen = Screen.CHAT,
                            )
                            }
                        }
                        is ChatEvent.CallRequested -> {
                            // The in-app ringing UI reacts to callState directly (RINGING); this
                            // event exists only to clear any stale "call ended" banner so a brand
                            // new ring doesn't sit under yesterday's reason text.
                            _state.update { it.copy(callEndedReason = null) }
                        }
                        is ChatEvent.CallEnded -> {
                            _state.update { it.copy(callEndedReason = event.reason) }
                            if (event.reason != null) {
                                safeLaunch {
                                    delay(4000)
                                    _state.update { if (it.callEndedReason == event.reason) it.copy(callEndedReason = null) else it }
                                }
                            }
                        }
                    }
                }
            }
        }
        meshRelayManager?.let { mesh ->
            safeLaunch {
                mesh.delivered.collect { msg ->
                    val contact = _state.value.chatHistory.firstOrNull { it.nodeId == msg.senderNodeId }
                    val displayName = contact?.name?.takeIf { it.isNotBlank() } ?: msg.senderName
                    val openForThisPeer = _state.value.screen == Screen.CHAT_CONVERSATION &&
                        _state.value.chatMeshPeer?.nodeId == msg.senderNodeId
                    if (openForThisPeer) {
                        _state.update {
                            it.copy(
                                chatMessages = it.chatMessages + ChatUiMessage(
                                    id = java.util.UUID.randomUUID().toString(),
                                    text = msg.body,
                                    fromMe = false,
                                    timestampMs = msg.sentAtMs,
                                    delivered = true,
                                    relayed = true,
                                ),
                            )
                        }
                        persistActiveSession(ChatSessionStatus.ACTIVE)
                    } else {
                        appContext?.let { ctx ->
                            NotificationHelper.postAlert(
                                ctx,
                                "New message from $displayName",
                                msg.body.take(140),
                                com.threadprotection.app.MainActivity.TARGET_CHAT,
                            )
                        }
                    }
                    // Bump this contact to the top of History and refresh their identity in case
                    // it's the first time we've heard from them via relay rather than directly.
                    if (contact != null) {
                        settingsRepository?.let { repo ->
                            safeLaunch { repo.recordChatHistory(contact.address, contact.name, contact.nodeId, contact.publicKeyB64) }
                        }
                    }
                }
            }
        }
        refreshHardwareStatus()
    }

    /** Starts/stops/reschedules the background scheduled-scan job to match the user's Settings choice. */
    private fun syncScheduledScan(settings: ProtectionSettings) {
        val ctx = appContext ?: return
        if (settings.autoScan) {
            com.threadprotection.app.service.ScheduledScanWorker.scheduleNext(
                ctx, settings.scanHour, settings.scanMinute, settings.scanFrequency.name, settings.scanDayOfWeek,
            )
        } else {
            com.threadprotection.app.service.ScheduledScanWorker.cancel(ctx)
        }
    }

    private fun com.threadprotection.app.data.StoredProtectionSettings.toState() = ProtectionSettings(
        autoScan = autoScan,
        breach = breach,
        downloads = downloads,
        phishing = phishing,
        hardware = hardware,
        scanHour = scanHour,
        scanMinute = scanMinute,
        scanFrequency = if (scanFrequency == "WEEKLY") ScanFrequency.WEEKLY else ScanFrequency.DAILY,
        scanDayOfWeek = scanDayOfWeek,
    )

    private fun ProtectionSettings.toStored() = com.threadprotection.app.data.StoredProtectionSettings(
        autoScan = autoScan,
        breach = breach,
        downloads = downloads,
        phishing = phishing,
        hardware = hardware,
        scanHour = scanHour,
        scanMinute = scanMinute,
        scanFrequency = scanFrequency.name,
        scanDayOfWeek = scanDayOfWeek,
    )

    // ───────────────────────── last-scan snapshot mapping ─────────────────────────
    // See StoredScanData's doc for what's deliberately excluded (permApps, liveHwDevices) and why.

    private fun Remedy.toStored(): StoredRemedy = when (this) {
        is Remedy.AppSettings -> StoredRemedy.AppSettings(packageName)
        Remedy.DeveloperOptions -> StoredRemedy.DeveloperOptions
        Remedy.SystemUpdate -> StoredRemedy.SystemUpdate
        is Remedy.PlayStore -> StoredRemedy.PlayStore(packageName)
        Remedy.None -> StoredRemedy.None
    }

    private fun StoredRemedy.toDomain(): Remedy = when (this) {
        is StoredRemedy.AppSettings -> Remedy.AppSettings(packageName)
        StoredRemedy.DeveloperOptions -> Remedy.DeveloperOptions
        StoredRemedy.SystemUpdate -> Remedy.SystemUpdate
        is StoredRemedy.PlayStore -> Remedy.PlayStore(packageName)
        StoredRemedy.None -> Remedy.None
    }

    private fun Finding.toStored() = StoredFinding(
        id = id,
        name = name,
        type = type,
        cat = cat.name,
        sev = sev.name,
        risk = risk,
        desc = desc,
        advice = advice,
        fix = fix,
        pros = pros,
        cons = cons,
        source = source,
        breaches = breaches?.map { StoredBreach(it.site, it.date, it.data) },
        remedy = remedy.toStored(),
    )

    /** Findings whose category/severity no longer resolves (a value from a future app version this
     *  one doesn't know) are dropped rather than crashing or guessing — the next real scan replaces
     *  this snapshot anyway. */
    private fun StoredFinding.toDomain(): Finding? {
        val category = runCatching { Category.valueOf(cat) }.getOrNull() ?: return null
        val severity = runCatching { Severity.valueOf(sev) }.getOrNull() ?: return null
        return Finding(
            id = id,
            name = name,
            type = type,
            cat = category,
            sev = severity,
            risk = risk,
            desc = desc,
            advice = advice,
            fix = fix,
            pros = pros,
            cons = cons,
            source = source,
            breaches = breaches?.map { Breach(it.site, it.date, it.data) },
            remedy = remedy.toDomain(),
        )
    }

    private fun ScanData.toStored() = StoredScanData(
        findings = findings.map { it.toStored() },
        hwDevices = hwDevices.map { StoredHwDevice(it.name, it.detail, it.ok) },
        appsScanned = appsScanned,
        ports = ports.map { StoredPortFinding(it.port, it.ownerLabel) },
        portsProbed = portsProbed,
        osPatchLabel = osPatchLabel,
        feedsConfigured = feedsConfigured,
        feedsTotal = feedsTotal,
    )

    private fun StoredScanData.toDomain(): ScanData {
        val restoredPorts = ports.map { PortFinding(it.port, it.ownerLabel) }
        return ScanData(
            findings = findings.mapNotNull { it.toDomain() },
            permApps = emptyList(),
            hwDevices = hwDevices.map { HwDevice(it.name, it.detail, it.ok) },
            appsScanned = appsScanned,
            ports = restoredPorts,
            portsFound = restoredPorts.size,
            portsProbed = portsProbed,
            osPatchLabel = osPatchLabel,
            feedsConfigured = feedsConfigured,
            feedsTotal = feedsTotal,
        )
    }

    private fun persistProtectionSettings(next: ProtectionSettings) {
        syncScheduledScan(next)
        settingsRepository?.let { repo -> safeLaunch { repo.setProtectionSettings(next.toStored()) } }
    }

    /** Starts/stops the background hardware-watch service (README: "work in the background… even if closed"). */
    private fun syncProtectionService(enabled: Boolean) {
        val ctx = appContext ?: return
        if (enabled) {
            com.threadprotection.app.service.ProtectionForegroundService.start(ctx)
            com.threadprotection.app.service.TwoFactorReminderWorker.schedule(ctx)
        } else {
            com.threadprotection.app.service.ProtectionForegroundService.stop(ctx)
            com.threadprotection.app.service.TwoFactorReminderWorker.cancel(ctx)
        }
    }

    private inline fun MutableStateFlow<AppUiState>.update(block: (AppUiState) -> AppUiState) {
        value = block(value)
    }

    private fun startAmbientTimers() {
        // "PROTECTING RIGHT NOW" counter — +1..3 every 1.4s (README §Sign in).
        safeLaunch {
            while (true) {
                delay(1400)
                _state.update { it.copy(blocked = it.blocked + 1 + Random.nextInt(3)) }
            }
        }
        // "LEARNING RIGHT NOW" counter — +1..4 every 1.9s (README §AI brain).
        safeLaunch {
            while (true) {
                delay(1900)
                _state.update { it.copy(learned = it.learned + 1 + Random.nextInt(4)) }
            }
        }
        // Rotating activity ticker on the sign-in screen — every 2.8s.
        safeLaunch {
            while (true) {
                delay(2800)
                _state.update { it.copy(tickIdx = (it.tickIdx + 1) % DemoData.ticker.size) }
            }
        }
    }

    // ───────────────────────── navigation ─────────────────────────

    fun goDashboard() = setScreen(Screen.DASHBOARD)
    fun goSettings() = setScreen(Screen.SETTINGS)
    fun goBrain() = setScreen(Screen.BRAIN)
    fun backToResults() = setScreen(Screen.RESULTS)

    fun goPerms() {
        setScreen(Screen.PERMS)
        ensurePermissionsLoaded()
    }

    fun goOtpSecurity() {
        setScreen(Screen.OTP_SECURITY)
        ensurePermissionsLoaded()
    }

    fun goDataBreach() {
        setScreen(Screen.DATA_BREACH)
        val email = _state.value.account?.email
        if (email != null && _state.value.breachResult?.email != email) checkMyBreaches()
    }

    fun goScanWebsite() = setScreen(Screen.SCAN_WEBSITE)

    fun goHardwareDetail() = setScreen(Screen.HARDWARE_DETAIL)

    fun goPortsDetail() = setScreen(Screen.PORTS_DETAIL)

    fun goOsDetail() = setScreen(Screen.OS_DETAIL)

    // Analyst Mode (CVE/EPSS/KEV) — the screens themselves run on their own Hilt CveViewModel
    // (analyst/presentation/cve/CveViewModel.kt); these two just move the shared Screen enum
    // the same way every other nav function here does, so back-stack handling comes for free.
    fun goAnalystCveSearch() = setScreen(Screen.ANALYST_CVE_SEARCH)
    fun goAnalystCveDetail() = setScreen(Screen.ANALYST_CVE_DETAIL)

    fun goQr() {
        qrJob?.cancel()
        releaseChatRadioIfLeaving(Screen.QR)
        _state.update { it.copy(screen = Screen.QR, qrPhase = QrPhase.IDLE, qrProgress = 0, qrVerdict = null) }
    }

    /**
     * Screens the back stack deliberately never returns to: transient or one-shot destinations
     * where going "back" into them would be wrong (re-entering a finished scan, or landing back on
     * sign-in after signing in).
     */
    private fun setScreen(screen: Screen) {
        releaseChatRadioIfLeaving(screen)
        _state.update { s ->
            if (s.screen == screen) return@update s
            // The stack maths lives in BackStackRules so it can be unit tested — see
            // ChatFlowTest for the navigation cases.
            s.copy(screen = screen, backStack = BackStackRules.push(s.backStack, s.screen, screen, MAX_BACK_STACK))
        }
    }

    /**
     * Real back navigation: returns to whatever screen the user actually came from, instead of
     * every screen hard-coding a jump to the Dashboard. Returns false when the stack is empty, so
     * the caller can let the system handle it (exit the app).
     */
    fun navigateBack(): Boolean {
        val previous = BackStackRules.peek(_state.value.backStack) ?: return false
        releaseChatRadioIfLeaving(previous)
        _state.update { s ->
            s.copy(screen = previous, backStack = BackStackRules.pop(s.backStack))
        }
        return true
    }

    /** Leaving the Chat feature by any route — bottom nav, back, sign-out — must release the radio.
     *  Otherwise BLE advertising and the RFCOMM accept loop kept running for the rest of the
     *  process's life, draining battery and leaving this phone discoverable long after the user
     *  left Chat. Call this before every transition that can move off a Chat screen. */
    private fun releaseChatRadioIfLeaving(next: Screen) {
        if (!_state.value.screen.isChatFeature || next.isChatFeature) return
        // Stop *scanning* only. The RFCOMM listener and BLE advertising deliberately keep running,
        // owned by ProtectionForegroundService, so this phone stays discoverable and reachable
        // after the user navigates away from Chat — that is the whole point of moving them there.
        // Calling shutdown() here (as this used to) tore them down again the instant the user left
        // the Chat screen, which is precisely why an incoming request had nothing to connect to.
        bluetoothChatManager?.stopDiscovery()
        _state.update { it.copy(btDiscoveredDevices = emptyList()) }
    }

    // ───────────────────────── splash ─────────────────────────

    /**
     * Called once the branded splash animation (`SplashPhase.BRANDING`) has played out.
     *
     * On a true cold start this is almost always a no-op: `accountFlow`'s collector (see `init`)
     * reads a returning user's account from disk and moves `screen` to DASHBOARD well before the
     * splash's own ~1.85s timer finishes, so by the time this fires `it.screen != Screen.SPLASH`
     * already. It only actually does something for a brand-new account (still SPLASH, no account
     * yet → SIGNIN) or for [replayLaunchExperience]'s repeat showing (still SPLASH, account already
     * present from a prior launch → moves to `SplashPhase.REAL_SCAN`, still on `Screen.SPLASH`, and
     * starts the real scan — see [runAutoScanOnSplash]'s doc for why this deliberately never hands
     * off to `Screen.SCANNING`/`Screen.RESULTS` for this path).
     */
    fun finishSplash() {
        var startRealScan = false
        _state.update { s ->
            when {
                s.screen != Screen.SPLASH -> s
                s.account != null -> { startRealScan = true; s.copy(splashPhase = SplashPhase.REAL_SCAN) }
                else -> s.copy(screen = Screen.SIGNIN)
            }
        }
        if (startRealScan) triggerAutoScanOnce(AutoScanFlow.SPLASH_TO_DASHBOARD)
    }

    /**
     * Replays the branded splash — and, once it finishes, a fresh real scan — every time the app is
     * genuinely reopened after being backgrounded, not only on the true cold process start that
     * already gets this for free from [AppUiState]'s initial `screen = Screen.SPLASH`. User-requested,
     * repeatedly and explicitly: this exact screen "whenever somebody opened the application," not
     * only the first time per process.
     *
     * Wired to `ProcessLifecycleOwner`'s `ON_START` in `MainActivity` — unlike an Activity-level
     * `onResume`, that only fires on a genuine whole-app foreground transition, not incidental
     * in-app blips (a permission dialog, a picked file, a orientation change), so this doesn't
     * replay the splash over something as small as returning from a system permission prompt.
     *
     * The guard below also makes the very first `ON_START` (which fires as a "catch up" dispatch
     * the instant the observer is registered, even on a true cold start) a safe no-op: at that
     * point `screen` is still its initial `Screen.SPLASH` value, so there's nothing to replay yet —
     * the real cold-start splash just plays once, normally, through [finishSplash] above.
     */
    fun replayLaunchExperience() {
        if (_state.value.account == null) return
        if (_state.value.screen == Screen.SPLASH) return
        autoScanTriggeredThisLaunch = false
        _state.update { it.copy(screen = Screen.SPLASH, splashPhase = SplashPhase.BRANDING) }
    }

    // ───────────────────────── sign-in ─────────────────────────

    fun signInWithGoogle(account: Account = DemoData.demoAccount) {
        _state.update { it.copy(account = account, screen = Screen.ONBOARDING, gsiError = null) }
        persistAccount(account)
    }

    fun goCreateAccount() {
        _state.update { it.copy(screen = Screen.CREATE_ACCOUNT, createAccountError = null) }
    }

    fun backToSignIn() {
        _state.update { it.copy(screen = Screen.SIGNIN, createAccountError = null) }
    }

    fun createAccount(name: String, email: String, password: String) {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        when {
            trimmedName.isEmpty() -> setCreateAccountError("Enter your name")
            !trimmedEmail.contains("@") || !trimmedEmail.contains(".") -> setCreateAccountError("Enter a valid email address")
            password.length < 8 -> setCreateAccountError("Use a password with at least 8 characters")
            else -> {
                val account = Account(name = trimmedName, email = trimmedEmail, initial = trimmedName.take(1).uppercase())
                _state.update { it.copy(account = account, screen = Screen.ONBOARDING, createAccountError = null) }
                persistAccount(account)
                settingsRepository?.let { repo -> safeLaunch { repo.setLocalCredential(trimmedEmail, password) } }
            }
        }
    }

    private fun setCreateAccountError(message: String) {
        _state.update { it.copy(createAccountError = message) }
    }

    fun signOut() {
        releaseChatRadioIfLeaving(Screen.SIGNIN)
        // "Allowed once" is a statement about this session. Signing out ends it.
        externalDeviceMonitor?.clearSessionTrust()
        _state.update {
            it.copy(
                screen = Screen.SIGNIN,
                account = null,
                hasScanned = false,
                fixed = emptySet(),
                resolvedRecords = emptyMap(),
                ignoredFindings = emptySet(),
                ignoredRecords = emptyMap(),
                fixInProgressId = null,
                deviceTrust = emptyMap(),
                deviceAlert = null,
                scanData = ScanData(),
            )
        }
        persistAccount(null)
    }

    fun setGsiError(message: String?) {
        _state.update { it.copy(gsiError = message) }
    }

    private fun persistAccount(account: Account?) {
        settingsRepository ?: return
        safeLaunch { settingsRepository.setAccount(account) }
    }

    // ───────────────────────── onboarding ─────────────────────────

    fun completeOnboarding() {
        setScreen(Screen.DASHBOARD)
        // A brand-new account reaching Dashboard for the first time is just as much "opened the
        // app" as a returning user's auto sign-in — see triggerAutoScanOnce's doc. Uses the older
        // Screen.SCANNING → Screen.RESULTS flow deliberately: this is a first-time, one-shot
        // "welcome, here's what we found" moment, not a reopen of an already-set-up app.
        triggerAutoScanOnce(AutoScanFlow.SCANNING_TO_RESULTS)
    }

    // ───────────────────────── theme ─────────────────────────

    fun toggleTheme() {
        val next = if (_state.value.theme == TpThemeMode.NIGHT) TpThemeMode.DAY else TpThemeMode.NIGHT
        applyTheme(next)
    }

    fun applyTheme(mode: TpThemeMode) {
        _state.update { it.copy(theme = mode) }
        settingsRepository?.let { repo -> safeLaunch { repo.setTheme(mode) } }
    }

    // ───────────────────────── realtime + settings toggles ─────────────────────────

    fun toggleRealtime() {
        val next = !_state.value.realtime
        _state.update { it.copy(realtime = next) }
        syncProtectionService(next)
        settingsRepository?.let { repo -> safeLaunch { repo.setRealtime(next) } }
    }

    /** Toggles one of the Settings screen's 6 protection rows by key (README §Settings §Protection). */
    fun toggleProtectionSetting(key: String) {
        if (key == "realtime") {
            toggleRealtime()
            return
        }
        var updated: ProtectionSettings? = null
        _state.update { s ->
            val settings = s.settings
            val next = when (key) {
                "autoScan" -> settings.copy(autoScan = !settings.autoScan)
                "breach" -> settings.copy(breach = !settings.breach)
                "phishing" -> settings.copy(phishing = !settings.phishing)
                "downloads" -> settings.copy(downloads = !settings.downloads)
                "hardware" -> settings.copy(hardware = !settings.hardware)
                else -> settings
            }
            updated = next
            s.copy(settings = next)
        }
        updated?.let(::persistProtectionSettings)
    }

    /** Customizes when the scheduled scan runs (README §Settings §Scheduled scan) — a specific time of day, daily or on one weekday. */
    fun setScheduledScanTime(hour: Int, minute: Int) {
        var updated: ProtectionSettings? = null
        _state.update { s ->
            val next = s.settings.copy(scanHour = hour, scanMinute = minute)
            updated = next
            s.copy(settings = next)
        }
        updated?.let(::persistProtectionSettings)
    }

    fun setScheduledScanFrequency(frequency: ScanFrequency, dayOfWeek: Int) {
        var updated: ProtectionSettings? = null
        _state.update { s ->
            val next = s.settings.copy(scanFrequency = frequency, scanDayOfWeek = dayOfWeek)
            updated = next
            s.copy(settings = next)
        }
        updated?.let(::persistProtectionSettings)
    }

    // ───────────────────────── threat-intel API keys ─────────────────────────

    fun setApiKey(id: ApiKeyId, value: String) {
        _state.update { it.copy(apiKeys = ApiKeys(it.apiKeys.values + (id to value))) }
        settingsRepository?.let { repo -> safeLaunch { repo.setApiKey(id, value) } }
    }

    // ───────────────────────── scanning (real device scan) ─────────────────────────

    /** Which on-open scan experience [triggerAutoScanOnce] runs — see its doc. */
    private enum class AutoScanFlow { SPLASH_TO_DASHBOARD, SCANNING_TO_RESULTS }

    /**
     * Runs one real scan automatically the moment the user reaches Dashboard fresh off the splash —
     * whether that's auto sign-in (a persisted account), finishing onboarding for the first time, a
     * brand-new Google sign-in, or [replayLaunchExperience] replaying the splash for a later reopen
     * of the app. User-requested: every time the app is opened, not only the first time, it should
     * scan the real environment and show a real result, rather than requiring a manual "Scan Now"
     * tap first.
     *
     * [flow] picks which of two visual experiences that scan runs behind:
     * - [AutoScanFlow.SPLASH_TO_DASHBOARD] ([runAutoScanOnSplash]) — a *reopen* of an already set-up
     *   app: stays on the one splash-styled screen throughout, then lands straight on Dashboard.
     *   User-requested, explicitly and repeatedly, after the two-screens version below read as a
     *   redundant "second scanning" step: "once it's scanned, then directly land on the homepage
     *   without doing second scanning."
     * - [AutoScanFlow.SCANNING_TO_RESULTS] ([startScan]) — a first-time, one-shot "here's what we
     *   found" moment (finishing onboarding, or a brand-new sign-in): the older
     *   `Screen.SCANNING` → `Screen.RESULTS` flow, deliberately left as it was — a first look at
     *   one's own results in detail is a reasonable thing to show only the first time, not a
     *   "reopen" this feature request was about.
     *
     * [autoScanTriggeredThisLaunch] makes each landing a true one-shot regardless of which flow:
     * without it, this would also fire on an unrelated later account-flow re-emission (any DataStore
     * write re-emits the whole account, not just an actual sign-in — see the comment on that
     * collector) and re-launch a full scan out of nowhere in the middle of a session.
     * [replayLaunchExperience] deliberately resets this guard back to `false` before replaying the
     * splash, so the *next* landing gets its own fresh one-shot scan too.
     */
    private fun triggerAutoScanOnce(flow: AutoScanFlow) {
        if (autoScanTriggeredThisLaunch) return
        autoScanTriggeredThisLaunch = true
        when (flow) {
            AutoScanFlow.SPLASH_TO_DASHBOARD -> runAutoScanOnSplash()
            AutoScanFlow.SCANNING_TO_RESULTS -> startScan()
        }
    }

    fun startScan() {
        val scanner = deviceScanner ?: return
        scanJob?.cancel()
        // Both resolutions and "Ignore for now" deliberately survive a rescan — they are
        // re-validated inside runRealScan against whatever this scan actually finds, so a genuinely
        // changed or worsened item reactivates on its own rather than the rescan blindly wiping
        // either list.
        _state.update {
            it.copy(
                screen = Screen.SCANNING,
                progress = 0f,
                scannedCount = 0,
                fixInProgressId = null,
                scanPhase = ScanPhaseState(),
                scanFeed = emptyList(),
            )
        }
        scanJob = safeLaunch { runRealScan(scanner, landingScreen = Screen.RESULTS) }
    }

    /**
     * The "opened the app" scan: identical real scan to [startScan] above, but stays on
     * `Screen.SPLASH` (in its `SplashPhase.REAL_SCAN` phase — see `RealScanSplashScreen`) for the
     * entire duration instead of switching to the separately-styled `Screen.SCANNING`, and lands
     * directly on `Screen.DASHBOARD` — never `Screen.RESULTS` — when done. User-requested,
     * explicitly and repeatedly: one continuous scanning screen per app open, then straight to the
     * homepage, not a splash followed by a second "now scanning" screen followed by a results list.
     */
    private fun runAutoScanOnSplash() {
        val scanner = deviceScanner ?: return
        scanJob?.cancel()
        _state.update {
            it.copy(
                progress = 0f,
                scannedCount = 0,
                fixInProgressId = null,
                scanPhase = ScanPhaseState(),
                scanFeed = emptyList(),
            )
        }
        scanJob = safeLaunch { runRealScan(scanner, landingScreen = Screen.DASHBOARD) }
    }

    /**
     * The real scan pipeline shared by [startScan] and [runAutoScanOnSplash] — identical work
     * either way (the same live findings, the same persistence, the same resolved/ignored-finding
     * reconciliation); only [landingScreen] differs, since that's the one thing the two call sites
     * actually disagree about.
     */
    private suspend fun runRealScan(scanner: DeviceScanner, landingScreen: Screen) {
        var feedSeq = 0L
        // See resolvedRecordsReady's doc: closes the cold-start race where a scan launched
        // before the first disk read lands would otherwise treat every previously fixed or
        // ignored threat as active again.
        if (settingsRepository != null) {
            resolvedRecordsReady.await()
            ignoredRecordsReady.await()
        }
        val result = scanner.scan(_state.value.apiKeys) { update ->
            _state.update {
                val pct = ((update.index.toFloat() + 1f) / update.total.toFloat()) * 100f
                val feed = if (update.liveItem != null) {
                    (listOf(ScanFeedEntry(feedSeq++, update.liveItem)) + it.scanFeed).take(SCAN_FEED_LIMIT)
                } else {
                    it.scanFeed
                }
                it.copy(
                    progress = pct,
                    scannedCount = (pct / 100f * ESTIMATED_ITEMS).toInt(),
                    scanPhase = ScanPhaseState(update.index, update.total, update.label, update.meta),
                    scanFeed = feed,
                )
            }
        }
        _state.update {
            it.copy(
                progress = 100f,
                scanData = ScanData(
                    findings = result.findings,
                    permApps = result.permApps,
                    hwDevices = result.hwDevices,
                    appsScanned = result.appsScanned,
                    ports = result.ports,
                    portsFound = result.ports.size,
                    portsProbed = result.portsProbed,
                    osPatchLabel = result.osPatchLabel,
                    feedsConfigured = result.feedsConfigured,
                    feedsTotal = result.feedsTotal,
                ),
                liveHwDevices = result.hwDevices,
                scannedCount = result.appsScanned,
            ).withResolvedApplied()
        }
        // Persisted so the Dashboard's score/status survives an app or phone restart — see
        // StoredScanData's doc for what's deliberately left out (permApps, live hardware).
        settingsRepository?.let { repo -> safeLaunch { repo.saveLastScan(_state.value.scanData.toStored()) } }
        // A record whose fingerprint no longer matches means the problem came back, or got
        // worse. Drop it from disk so it can be resolved again on its current terms rather
        // than silently suppressing a threat the user never actually saw in this shape.
        val snapshot = _state.value
        val superseded = FindingIdentity.supersededRecords(result.findings, snapshot.resolvedRecords)
        if (superseded.isNotEmpty()) {
            Log.i(TAG, "scan: ${superseded.size} resolved finding(s) came back in a different shape — clearing their records")
            settingsRepository?.clearResolvedFindings(superseded)
        }
        // Retire records whose problem this scan could see was gone. This is what lets a later
        // return of the same issue register as a genuine re-emergence instead of being
        // suppressed forever by the user's original resolution.
        val cleared = FindingIdentity.clearedRecords(
            findings = result.findings,
            resolved = snapshot.resolvedRecords,
            resolvedCategories = snapshot.resolvedCategories,
            coveredCategories = result.coveredCategories,
        )
        if (cleared.isNotEmpty()) {
            Log.i(TAG, "scan: ${cleared.size} resolved finding(s) confirmed gone — retiring their records")
            settingsRepository?.markFindingsCleared(cleared)
        }
        // Same reconciliation as above, mirrored for "Ignore for now" so it carries the same
        // durability and the same honesty about a changed or worsened issue reactivating.
        val supersededIgnored = FindingIdentity.supersededRecords(result.findings, snapshot.ignoredRecords)
        if (supersededIgnored.isNotEmpty()) {
            Log.i(TAG, "scan: ${supersededIgnored.size} ignored finding(s) came back in a different shape — clearing their records")
            settingsRepository?.clearIgnoredFindings(supersededIgnored)
        }
        val clearedIgnored = FindingIdentity.clearedRecords(
            findings = result.findings,
            resolved = snapshot.ignoredRecords,
            resolvedCategories = snapshot.ignoredCategories,
            coveredCategories = result.coveredCategories,
        )
        if (clearedIgnored.isNotEmpty()) {
            Log.i(TAG, "scan: ${clearedIgnored.size} ignored finding(s) confirmed gone — retiring their records")
            settingsRepository?.markIgnoredCleared(clearedIgnored)
        }
        val returned = _state.value.reEmergedIds
        if (returned.isNotEmpty()) {
            Log.w(TAG, "scan: ${returned.size} previously resolved threat(s) have re-emerged: $returned")
        }
        delay(400)
        _state.update { it.copy(screen = landingScreen, splashPhase = SplashPhase.BRANDING, hasScanned = true) }
    }

    /**
     * Recomputes [AppUiState.fixed] and [AppUiState.ignoredFindings] from their on-disk records
     * against whatever findings the state currently holds.
     *
     * This is the one place either is reconciled, and it runs both when either record set changes
     * and when the findings change (after a scan). A record only counts while its fingerprint
     * still matches the finding, so a threat that reappeared — or got worse — is active again even
     * though the user resolved or ignored it once. Findings this scan didn't produce at all simply
     * aren't in the set; their records stay on disk in case a later scan can reach that source
     * again. [reEmergedIds] covers both: a threat the user dismissed either way and that has since
     * changed is reported as a genuine return, not silently re-suppressed.
     */
    private fun AppUiState.withResolvedApplied(): AppUiState = copy(
        fixed = FindingIdentity.stillResolved(scanData.findings, resolvedRecords),
        ignoredFindings = FindingIdentity.stillResolved(scanData.findings, ignoredRecords),
        reEmergedIds = FindingIdentity.reEmerged(scanData.findings, everResolvedIds, resolvedRecords) +
            FindingIdentity.reEmerged(scanData.findings, everIgnoredIds, ignoredRecords),
    )

    fun cancelScan() {
        scanJob?.cancel()
        setScreen(Screen.DASHBOARD)
    }

    /**
     * "Ignore for now" — the user has seen this finding and decided to leave it. It stops counting
     * against the security score and disappears from the active list, and — like [fixSelected] —
     * is recorded on disk against the finding's current fingerprint so it stays out of the way
     * across scans and app restarts. It is deliberately not marked [fixed]: the UI still shows it
     * as ignored rather than resolved, but the durability guarantee is identical. It reappears the
     * moment the underlying problem actually changes or worsens — see FindingIdentity.
     */
    fun ignoreSelectedFinding() {
        val sel = Derived.selectedFinding(_state.value) ?: return
        val fingerprint = FindingIdentity.fingerprintOf(sel)
        _state.update {
            it.copy(
                ignoredRecords = it.ignoredRecords + (sel.id to fingerprint),
                everIgnoredIds = it.everIgnoredIds + sel.id,
            ).withResolvedApplied()
        }
        settingsRepository?.let { repo ->
            safeLaunch { repo.markFindingIgnored(sel.id, fingerprint, sel.cat.name) }
        }
    }

    /** Undo an "Ignore for now" — puts the finding back into the active list and the score, and
     *  removes its record from disk, mirroring [unresolveFinding]. */
    fun unignoreFinding(id: String) {
        _state.update { it.copy(ignoredRecords = it.ignoredRecords - id).withResolvedApplied() }
        settingsRepository?.let { repo -> safeLaunch { repo.clearIgnoredFindings(setOf(id)) } }
    }

    /**
     * The user applied the fix for the selected finding. Recorded on disk against the finding's
     * current fingerprint, so it stays resolved across scans and restarts — and comes back if the
     * underlying problem does.
     */
    fun fixSelected() {
        val sel = Derived.selectedFinding(_state.value) ?: return
        val fingerprint = FindingIdentity.fingerprintOf(sel)
        // Applied in memory first so the score, the list and the button all move on this frame —
        // the disk write below is durability, not the source of truth for what's on screen. The
        // record is updated alongside `fixed` so the two can't disagree while the write is in
        // flight, and the flow's later emission is then just a confirmation of the same value.
        _state.update {
            it.copy(
                fixed = it.fixed + sel.id,
                resolvedRecords = it.resolvedRecords + (sel.id to fingerprint),
                fixInProgressId = null,
            )
        }
        settingsRepository?.let { repo ->
            safeLaunch { repo.markFindingResolved(sel.id, fingerprint, sel.cat.name) }
        }
    }

    /**
     * Marks that the user has been handed off to Android Settings to apply a fix but hasn't
     * confirmed it yet — this app cannot change a system setting itself, so "in progress" means
     * exactly that and nothing more. Cleared by [fixSelected] on confirmation, or by [cancelFix]
     * if they come back without doing it.
     */
    fun beginFix(id: String) {
        _state.update { it.copy(fixInProgressId = id) }
    }

    fun cancelFix() {
        _state.update { it.copy(fixInProgressId = null) }
    }

    /** Puts a resolved finding back into the active list and removes its record from disk. */
    fun unresolveFinding(id: String) {
        _state.update { it.copy(fixed = it.fixed - id, resolvedRecords = it.resolvedRecords - id) }
        settingsRepository?.let { repo -> safeLaunch { repo.clearResolvedFindings(setOf(id)) } }
    }

    /** Start Fixing: opens the highest-severity finding that still needs attention. */
    fun startFixing() {
        val next = Derived.fixProgress(_state.value).nextId ?: return
        openFinding(next)
    }

    fun openFinding(id: String) {
        _state.update { it.copy(selectedId = id, screen = Screen.DETAIL) }
    }

    fun voteUp() {
        val sel = Derived.selectedFinding(_state.value) ?: return
        _state.update { it.copy(votes = it.votes + (sel.id to Vote.UP), learned = it.learned + 1) }
    }

    fun voteDown() {
        val sel = Derived.selectedFinding(_state.value) ?: return
        _state.update { it.copy(votes = it.votes + (sel.id to Vote.DOWN), learned = it.learned + 1) }
    }

    // ───────────────────────── app permissions (independent quick audit) ─────────────────────────

    fun ensurePermissionsLoaded() {
        if (_state.value.scanData.permApps.isNotEmpty()) return
        refreshPermissions()
    }

    /**
     * Re-reads every installed app's live permission state from PackageManager. Called on entering
     * the permissions screens and, crucially, every time the app detail screen resumes — which is
     * how a change the user just made in system Settings becomes visible here. There is no cached
     * "we think it's off" state anywhere; the OS is the only source of truth.
     */
    fun refreshPermissions() {
        val audit = permissionAudit ?: return
        if (permJob?.isActive == true) return
        permJob = safeLaunch {
            val result = withContext(Dispatchers.IO) { audit.audit() }
            _state.update {
                it.copy(scanData = it.scanData.copy(permApps = result.apps, appsScanned = result.totalInstalledCount))
            }
        }
    }

    fun openAppPermissionDetail(packageName: String) {
        _state.update { it.copy(selectedPermApp = packageName, screen = Screen.APP_PERMISSION_DETAIL) }
    }

    fun closeAppPermissionDetail() {
        _state.update { it.copy(selectedPermApp = null, screen = Screen.PERMS) }
    }

    // ───────────────────────── QR scanner (real reputation checks) ─────────────────────────

    fun startQr(index: Int) {
        val sample = DemoData.qrSamples.getOrNull(index) ?: return
        runQrCheck(sample.url, index)
    }

    /** Entry point for a real camera-decoded payload — README §QR scanner. */
    fun analyzeScannedPayload(rawPayload: String) {
        runQrCheck(rawPayload, -1)
    }

    /**
     * Analyses one decoded QR payload.
     *
     * Classification happens first and entirely on-device, and it is what decides whether anything
     * is sent to a reputation service at all.
     *
     * Root-cause fix: this used to hand *every* payload straight to `checkUrl()`. A Wi-Fi join
     * string, a contact card, a two-factor secret or a shopping list was normalised into a URL and
     * posted to third-party threat-intel APIs — which is both a wrong answer (a verdict about a
     * "site" that never existed) and a real privacy leak: the user's home Wi-Fi password and 2FA
     * seeds left the device. Only [QrAnalysis.urlToCheck] is ever transmitted, and it is null for
     * every non-web payload.
     */
    private fun runQrCheck(payload: String, index: Int) {
        qrJob?.cancel()
        val analysis = QrContentClassifier.classify(payload)
        _state.update {
            it.copy(
                qrPhase = QrPhase.SCANNING,
                qrIndex = index,
                qrProgress = 0,
                qrVerdict = null,
                qrAnalysis = analysis,
            )
        }
        val url = analysis.urlToCheck
        if (url == null) {
            // Nothing web-facing: the on-device analysis is the complete answer, and no network
            // call is made. Shown immediately rather than running a fake progress bar over a
            // lookup that isn't happening.
            Log.d(TAG, "runQrCheck: ${analysis.type} payload analysed on-device, nothing transmitted")
            _state.update { it.copy(qrProgress = 100, qrPhase = QrPhase.RESULT) }
            return
        }
        qrJob = safeLaunch {
            val progressJob = launch {
                while (_state.value.qrProgress < 92) {
                    delay(60)
                    _state.update { it.copy(qrProgress = (it.qrProgress + 8).coerceAtMost(92)) }
                }
            }
            val verdict = threatIntel.checkUrl(url, _state.value.apiKeys)
            progressJob.cancel()
            _state.update { it.copy(qrProgress = 100, qrPhase = QrPhase.RESULT, qrVerdict = verdict) }
        }
    }

    fun rescanQr() {
        qrJob?.cancel()
        _state.update { it.copy(qrPhase = QrPhase.IDLE, qrProgress = 0, qrVerdict = null, qrAnalysis = null) }
    }

    /** Torch toggle for the scanner — real camera flash, driven by CameraX. */
    fun toggleQrTorch() = _state.update { it.copy(qrTorchOn = !it.qrTorchOn) }

    // ───────────────────────── data breach security (free, keyless, live) ─────────────────────────

    fun checkMyBreaches() {
        val email = _state.value.account?.email ?: return
        if (_state.value.breachChecking) return
        _state.update { it.copy(breachChecking = true) }
        safeLaunch {
            val result = threatIntel.checkEmailBreaches(email)
            _state.update { it.copy(breachResult = result, breachChecking = false) }
        }
    }

    // ───────────────────────── scan website (manual URL check) ─────────────────────────

    fun setWebsiteUrl(url: String) {
        _state.update { it.copy(websiteUrl = url) }
    }

    fun checkWebsite() {
        val url = _state.value.websiteUrl.trim()
        if (url.isEmpty() || _state.value.websiteChecking) return
        _state.update { it.copy(websiteChecking = true, websiteVerdict = null) }
        safeLaunch {
            val verdict = threatIntel.checkUrl(url, _state.value.apiKeys)
            _state.update { it.copy(websiteVerdict = verdict, websiteChecking = false) }
        }
    }

    // ───────────────────────── hardware watch ─────────────────────────

    fun toggleHwOpen() {
        _state.update { it.copy(hwOpen = !it.hwOpen) }
    }

    fun refreshHardwareStatus() {
        val watcher = hardwareWatcher ?: return
        safeLaunch {
            val devices = withContext(Dispatchers.IO) { watcher.scan() }
            _state.update { it.copy(liveHwDevices = devices) }
        }
    }

    fun simulateHw() {
        _state.update {
            val sim = DemoData.hwSim[it.hwIdx % DemoData.hwSim.size]
            it.copy(hwAlert = sim, hwIdx = it.hwIdx + 1, hwHandled = null)
        }
    }

    // ───────────────────────── external devices (real USB / Bluetooth connections) ─────────────────────────

    /**
     * The user chose to block a connected device.
     *
     * Records the decision and keeps the device flagged. It deliberately does *not* claim the
     * connection was severed — no Android app can do that — and the alert says so; see
     * ExternalDeviceMonitor.
     */
    fun blockExternalDevice(id: String) = externalDeviceMonitor?.block(id)

    /** Session-only trust for a device the user recognises. Cleared on sign-out. */
    fun allowExternalDeviceOnce(id: String) = externalDeviceMonitor?.allowOnce(id)

    fun dismissDeviceAlert() = externalDeviceMonitor?.dismissAlert()

    override fun onCleared() {
        super.onCleared()
        externalDeviceMonitor?.stop()
    }

    fun hwBlock() = _state.update { it.copy(hwHandled = HwHandled.BLOCK) }
    fun hwAllow() = _state.update { it.copy(hwHandled = HwHandled.ALLOW) }
    fun hwDismiss() = _state.update { it.copy(hwAlert = null, hwHandled = null) }

    // ───────────────────────── chat (Bluetooth, post-quantum encrypted — README §Chat) ─────────────────────────

    fun goChat() {
        setScreen(Screen.CHAT)
        bluetoothChatManager?.startListening()
        // Start looking straight away and keep the list fresh — the user shouldn't have to tap the
        // radar to find out whether anyone is nearby.
        bluetoothChatManager?.startContinuousDiscovery()
    }

    fun goChatHistory() = setScreen(Screen.CHAT_HISTORY)

    fun leaveChatHistory() = setScreen(Screen.CHAT)

    /**
     * Leaves the Chat feature's nearby-list screen for Dashboard — pure navigation. `setScreen()`
     * stops the BLE *scan* (see `releaseChatRadioIfLeaving`'s doc), but must never clear
     * `chatMessages`/`chatPeerName`/`chatMeshPeer`: since `leaveChatConversation()` lets the user
     * back out of a live conversation without disconnecting, a real connection (and its message
     * history) can legitimately still be alive right here, and `resumeChatConversation()` depends
     * on `chatPeerName` staying accurate. The `connectedDeviceName` collector in `init` already
     * keeps `chatPeerName` truthful as connections actually start and end — nothing here needs to
     * duplicate that. An explicit end of the conversation is `exitChat()`'s job, not this one's.
     */
    fun leaveChat() {
        setScreen(Screen.DASHBOARD)
    }

    fun setChatMode(mode: ChatMode) {
        _state.update { it.copy(chatMode = mode) }
        if (mode == ChatMode.INTERNET) {
            appContext?.let { ctx ->
                NotificationHelper.postComingSoon(
                    ctx,
                    "Internet chat is under maintenance",
                    "This mode isn't ready yet — it needs a relay server that doesn't exist for this app yet. Bluetooth chat with nearby devices works right now.",
                    com.threadprotection.app.MainActivity.TARGET_CHAT,
                )
            }
        }
    }

    fun startBtDiscovery() {
        // Re-run startListening() first: it's idempotent, and it's what re-attempts BLE advertising
        // and the RFCOMM listener. Without this, a permission granted *after* goChat() already ran
        // (or Bluetooth switched on afterwards) would never take effect — this device would scan
        // fine but stay invisible to everyone else until Chat was closed and reopened.
        bluetoothChatManager?.startListening()
        bluetoothChatManager?.startContinuousDiscovery()
    }

    /** Retries the last connection the user asked for. Reports honestly when there's nothing to
     *  retry rather than showing a spinner for a connection that was never attempted. */
    fun retryBtConnect() {
        val manager = bluetoothChatManager
        val retried = manager?.retryLastConnect() ?: false
        if (!retried) {
            _state.update { it.copy(chatError = "There's no earlier connection to retry — pick a device from the list below.") }
            manager?.startContinuousDiscovery()
        }
    }

    fun connectToBtDevice(address: String) {
        // Remember which row the user tapped so that row — and only that row — can show
        // "Connecting…" / "Verifying…" / "Failed", driven by the real BtChatConnState rather than
        // an optimistic label flipped the moment the button was pressed.
        _state.update { it.copy(chatMessages = emptyList(), btConnectingAddress = address) }
        bluetoothChatManager?.connectTo(address)
    }

    /** Opens a conversation with a History contact for messaging — works whether or not they're
     *  currently in range. A real direct connect is also attempted in the background so the
     *  conversation upgrades to live 2-way chat automatically if they happen to be nearby right
     *  now; if not, sendChatMessage() falls back to queuing via the mesh relay (see
     *  MeshRelayManager), which only works if this contact's long-term key was captured during a
     *  past direct handshake (entry.meshReachable). */
    fun messageFromHistory(entry: ChatHistoryEntry) {
        _state.update {
            it.copy(
                chatMeshPeer = entry,
                chatPeerName = entry.name,
                chatMessages = emptyList(),
                // Record this as a session up front, so a relay-only conversation (no live socket,
                // therefore no handshake to trigger openChatSession) is still saved to History.
                // If the background connect below does succeed, openChatSession sees an id already
                // set and leaves this one alone rather than starting a second session for the same
                // conversation.
                activeSessionId = java.util.UUID.randomUUID().toString(),
                activeSessionStartedAtMs = System.currentTimeMillis(),
                screen = Screen.CHAT_CONVERSATION,
            )
        }
        bluetoothChatManager?.connectTo(entry.address)
    }

    fun setChatDraft(text: String) {
        _state.update { it.copy(chatDraft = text) }
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastTypingSentAt > 2000) {
            lastTypingSentAt = now
            bluetoothChatManager?.sendTyping()
        }
    }

    fun sendChatMessage() {
        val text = _state.value.chatDraft.trim()
        if (text.isEmpty()) return

        if (_state.value.btConnState == com.threadprotection.app.chat.BtChatConnState.CONNECTED) {
            // sendText returns null when the socket or session key has gone away between the state
            // check and the write — a real race when the peer walks out of range mid-tap. Surface
            // it as an error instead of adding a message that was never transmitted.
            val id = bluetoothChatManager?.sendText(text)
            if (id == null) {
                _state.update { it.copy(chatError = "That message wasn't sent — the connection dropped. Reconnect and try again.") }
                return
            }
            _state.update {
                it.copy(
                    chatMessages = it.chatMessages + ChatUiMessage(id, text, fromMe = true, timestampMs = System.currentTimeMillis(), delivered = false),
                    chatDraft = "",
                )
            }
            persistActiveSession(ChatSessionStatus.ACTIVE)
            return
        }

        // No live connection — fall back to the store-and-forward mesh relay if this contact's
        // long-term key is on file (only true once you've connected to them directly at least
        // once; see BluetoothChatManager's identity exchange).
        val peer = _state.value.chatMeshPeer
        val mesh = meshRelayManager
        if (peer == null || mesh == null || !peer.meshReachable) {
            _state.update { it.copy(chatError = "Not connected, and this contact can't be reached by relay yet. Connect to them directly once first.") }
            return
        }
        val publicKeyBytes = runCatching { Base64.decode(peer.publicKeyB64, Base64.NO_WRAP) }.getOrNull() ?: return
        val senderName = _state.value.account?.name?.takeIf { it.isNotBlank() } ?: "Thread Protection user"
        safeLaunch { mesh.queueOutbound(peer.nodeId, publicKeyBytes, senderName, text) }
        _state.update {
            it.copy(
                chatMessages = it.chatMessages + ChatUiMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    text = text,
                    fromMe = true,
                    timestampMs = System.currentTimeMillis(),
                    delivered = false,
                    relayed = true,
                ),
                chatDraft = "",
            )
        }
        persistActiveSession(ChatSessionStatus.ACTIVE)
    }

    /**
     * Leaves the conversation *screen* without touching the live connection underneath it.
     *
     * Root cause this fixes: Back used to call the same function as the explicit "Exit Chat"
     * button, so simply navigating away from the conversation tore down the socket — the user
     * couldn't glance at another screen without ending the chat. This is pure navigation: the
     * socket, the encrypted session and [AppUiState.chatPeerName] are all left exactly as they
     * were, so [resumeChatConversation] can return to the same live conversation, and a message
     * arriving while the user is elsewhere still comes through (see the `connectedDeviceName`/
     * message collectors in `init`, which don't depend on which screen is showing).
     */
    fun leaveChatConversation() {
        setScreen(Screen.CHAT)
    }

    /** Returns to a conversation that's still live in the background after [leaveChatConversation] —
     *  a no-op if the connection has since ended, so this can't jump into a dead screen. */
    fun resumeChatConversation() {
        if (_state.value.chatPeerName != null) setScreen(Screen.CHAT_CONVERSATION)
    }

    /** This user tapped Accept on an incoming request — tells the peer and opens the chat. */
    fun acceptIncomingChatRequest() {
        appContext?.let { NotificationHelper.cancelChatRequest(it) }
        _state.update { it.copy(incomingChatRequest = null) }
        // acceptChatRequest() returns false when the request died between arriving and this tap —
        // the peer hung up, or it timed out. Tell the user instead of leaving a dead screen: the
        // manager has already released the radio, so they can go straight back to the device list.
        val accepted = bluetoothChatManager?.acceptChatRequest() ?: false
        if (!accepted) {
            _state.update {
                it.copy(
                    chatError = "That chat request is no longer open — the other phone hung up or it timed out.",
                    screen = if (it.screen == Screen.CHAT_CONVERSATION) Screen.CHAT else it.screen,
                )
            }
            return
        }
        // Accepting is what puts this user *into* the conversation. Do it here rather than waiting
        // on the connectedDeviceName collector alone, so the screen never lags a state behind.
        setScreen(Screen.CHAT_CONVERSATION)
    }

    /** This user tapped Deny — tells the peer and closes the pending connection cleanly. */
    fun denyIncomingChatRequest() {
        appContext?.let { NotificationHelper.cancelChatRequest(it) }
        _state.update { it.copy(incomingChatRequest = null) }
        bluetoothChatManager?.denyChatRequest()
    }

    // ───────────────────────── voice call ─────────────────────────

    /** Rings the current chat partner. A no-op if there's no live, accepted chat to call over —
     *  see BluetoothChatManager.startCall's doc. */
    fun startCall() {
        _state.update { it.copy(callEndedReason = null) }
        bluetoothChatManager?.startCall()
    }

    fun acceptCall() {
        appContext?.let { NotificationHelper.cancelIncomingCall(it) }
        bluetoothChatManager?.acceptCall()
    }

    fun declineCall() {
        appContext?.let { NotificationHelper.cancelIncomingCall(it) }
        bluetoothChatManager?.declineCall()
    }

    fun endCall() {
        bluetoothChatManager?.endCall()
    }

    fun toggleCallMute() {
        bluetoothChatManager?.toggleMute()
    }

    /** Clears the "Call ended"/"They declined the call." banner once the user has seen it. */
    fun dismissCallEndedReason() {
        _state.update { it.copy(callEndedReason = null) }
    }

    // ───────────────────────── chat sessions (real transcripts, persisted) ─────────────────────────

    /** Opens a recording session the moment a connection is genuinely established. */
    private fun openChatSession(address: String, name: String) {
        if (_state.value.activeSessionId != null) return
        _state.update {
            it.copy(
                activeSessionId = java.util.UUID.randomUUID().toString(),
                activeSessionStartedAtMs = System.currentTimeMillis(),
                chatMessages = emptyList(),
            )
        }
    }

    /**
     * Writes the live conversation to disk under its stable session id. Called after every message
     * in either direction, so an interrupted chat (peer walked away, Bluetooth died, process
     * killed) still has everything received up to that point already saved.
     */
    private fun persistActiveSession(status: ChatSessionStatus, endedAtMs: Long? = null) {
        val s = _state.value
        val sessionId = s.activeSessionId ?: return
        val repo = settingsRepository ?: return
        val address = s.chatMeshPeer?.address ?: bluetoothChatManager?.connectedDeviceAddress?.value ?: return
        val name = s.chatPeerName ?: s.chatMeshPeer?.name ?: address
        val messages = s.chatMessages.map {
            StoredChatMessage(it.id, it.text, it.fromMe, it.timestampMs, it.delivered, it.relayed)
        }
        val stored = StoredChatSession(
            sessionId = sessionId,
            address = address,
            name = name,
            startedAtMs = s.activeSessionStartedAtMs,
            endedAtMs = endedAtMs,
            status = when (status) {
                ChatSessionStatus.ACTIVE -> StoredSessionStatus.ACTIVE
                ChatSessionStatus.COMPLETED -> StoredSessionStatus.COMPLETED
                ChatSessionStatus.INTERRUPTED -> StoredSessionStatus.INTERRUPTED
            },
            messages = messages,
        )
        safeLaunch { repo.saveChatSession(stored) }
    }

    /** Finalises the session with how it actually ended, then forgets the live id. */
    private fun closeChatSession(status: ChatSessionStatus) {
        if (_state.value.activeSessionId == null) return
        persistActiveSession(status, endedAtMs = System.currentTimeMillis())
        _state.update { it.copy(activeSessionId = null, activeSessionStartedAtMs = 0L) }
    }

    /**
     * "Exit Chat" — the deliberate end of a conversation. Saves the transcript as COMPLETED, drops
     * the Bluetooth connection, and returns to History so the user immediately sees the session
     * they just finished. Radio teardown itself is handled by setScreen when they leave the Chat
     * feature entirely.
     */
    fun exitChat() {
        closeChatSession(ChatSessionStatus.COMPLETED)
        bluetoothChatManager?.disconnect()
        _state.update { it.copy(chatMessages = emptyList(), chatPeerName = null, chatMeshPeer = null) }
        setScreen(Screen.CHAT_HISTORY)
    }

    fun openStoredSession(sessionId: String) {
        val session = _state.value.chatSessions.firstOrNull { it.sessionId == sessionId } ?: return
        _state.update { it.copy(viewingSession = session, screen = Screen.CHAT_SESSION) }
    }

    fun closeStoredSession() {
        _state.update { it.copy(viewingSession = null) }
        setScreen(Screen.CHAT_HISTORY)
    }

    fun deleteStoredSession(sessionId: String) {
        val repo = settingsRepository ?: return
        safeLaunch { repo.deleteChatSession(sessionId) }
        _state.update { if (it.viewingSession?.sessionId == sessionId) it.copy(viewingSession = null) else it }
    }

    fun clearStoredSessions() {
        val repo = settingsRepository ?: return
        safeLaunch { repo.clearChatSessions() }
        _state.update { it.copy(viewingSession = null) }
    }

    companion object {
        private const val TAG = "TPChat"
        private const val ESTIMATED_ITEMS = 300
        /** The live feed fills the whole card on a modern phone screen, so it holds a real
         *  scrollback rather than 8 lines above a block of empty white. */
        private const val SCAN_FEED_LIMIT = 60
        private const val MAX_BACK_STACK = 24
    }
}
