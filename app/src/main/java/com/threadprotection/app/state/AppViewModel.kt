package com.threadprotection.app.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Base64
import com.threadprotection.app.chat.BluetoothChatManager
import com.threadprotection.app.chat.ChatEvent
import com.threadprotection.app.chat.ChatHistoryEntry
import com.threadprotection.app.chat.ChatMode
import com.threadprotection.app.chat.ChatUiMessage
import com.threadprotection.app.chat.MeshRelayManager
import com.threadprotection.app.data.Account
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.data.StoredChatHistoryEntry
import com.threadprotection.app.service.NotificationHelper
import com.threadprotection.app.network.ThreatIntelRepository
import com.threadprotection.app.scan.DeviceScanner
import com.threadprotection.app.scan.HardwareWatcher
import com.threadprotection.app.scan.PermissionAudit
import com.threadprotection.app.ui.theme.TpThemeMode
import com.threadprotection.app.ui.theme.systemThemeMode
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val threatIntel = ThreatIntelRepository()
    private val deviceScanner by lazy { appContext?.let { DeviceScanner(it, threatIntel) } }
    private val permissionAudit by lazy { appContext?.let { PermissionAudit(it) } }
    private val hardwareWatcher by lazy { appContext?.let { HardwareWatcher(it) } }
    private val bluetoothChatManager by lazy {
        val ctx = appContext; val repo = settingsRepository
        if (ctx != null && repo != null) BluetoothChatManager(ctx, repo) else null
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
    private var qrJob: Job? = null
    private var permJob: Job? = null

    init {
        startAmbientTimers()
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.themeFlow.collect { mode -> _state.update { it.copy(theme = mode) } }
            }
            viewModelScope.launch {
                repo.accountFlow.collect { account ->
                    if (account != null) {
                        _state.update { it.copy(account = account, screen = Screen.DASHBOARD) }
                    }
                }
            }
            viewModelScope.launch {
                repo.apiKeysFlow.collect { keys -> _state.update { it.copy(apiKeys = keys) } }
            }
            viewModelScope.launch {
                repo.realtimeFlow.collect { enabled ->
                    _state.update { it.copy(realtime = enabled) }
                    syncProtectionService(enabled)
                }
            }
            viewModelScope.launch {
                repo.protectionSettingsFlow.collect { stored ->
                    val settings = stored.toState()
                    _state.update { it.copy(settings = settings) }
                    syncScheduledScan(settings)
                }
            }
            viewModelScope.launch {
                repo.chatHistoryFlow.collect { stored ->
                    val history = stored
                        .map { ChatHistoryEntry(it.address, it.name, it.lastChattedAtMs, it.nodeId, it.publicKeyB64) }
                        .sortedByDescending { it.lastChattedAtMs }
                    _state.update { it.copy(chatHistory = history) }
                }
            }
        }
        bluetoothChatManager?.let { chat ->
            viewModelScope.launch {
                chat.connState.collect { st -> _state.update { it.copy(btConnState = st) } }
            }
            viewModelScope.launch {
                chat.discoveredDevices.collect { list -> _state.update { it.copy(btDiscoveredDevices = list) } }
            }
            viewModelScope.launch {
                chat.connectedDeviceName.collect { name ->
                    _state.update { it.copy(chatPeerName = name) }
                    if (name != null) {
                        setScreen(Screen.CHAT_CONVERSATION)
                        val address = chat.connectedDeviceAddress.value
                        val nodeId = chat.connectedPeerNodeId.value.orEmpty()
                        val publicKeyB64 = chat.connectedPeerPublicKeyB64.value.orEmpty()
                        if (address != null) {
                            // Also remember this contact as mesh-reachable (if the identity
                            // exchange succeeded) so the conversation can keep going via relay if
                            // the live connection later drops.
                            _state.update { it.copy(chatMeshPeer = ChatHistoryEntry(address, name, System.currentTimeMillis(), nodeId, publicKeyB64)) }
                            settingsRepository?.let { repo ->
                                viewModelScope.launch { repo.recordChatHistory(address, name, nodeId, publicKeyB64) }
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                chat.events.collect { event ->
                    when (event) {
                        is ChatEvent.MessageReceived -> _state.update {
                            it.copy(
                                chatMessages = it.chatMessages + ChatUiMessage(event.id, event.text, fromMe = false, timestampMs = event.atMs, delivered = true),
                                chatPeerTyping = false,
                            )
                        }
                        is ChatEvent.MessageDelivered -> _state.update {
                            it.copy(chatMessages = it.chatMessages.map { m -> if (m.id == event.id) m.copy(delivered = true) else m })
                        }
                        ChatEvent.PeerTyping -> {
                            _state.update { it.copy(chatPeerTyping = true) }
                            viewModelScope.launch {
                                delay(3000)
                                _state.update { s -> if (s.chatPeerTyping) s.copy(chatPeerTyping = false) else s }
                            }
                        }
                        ChatEvent.PeerDisconnected -> _state.update { it.copy(chatPeerName = null, chatPeerTyping = false) }
                    }
                }
            }
        }
        meshRelayManager?.let { mesh ->
            viewModelScope.launch {
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
                            viewModelScope.launch { repo.recordChatHistory(contact.address, contact.name, contact.nodeId, contact.publicKeyB64) }
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

    private fun persistProtectionSettings(next: ProtectionSettings) {
        syncScheduledScan(next)
        settingsRepository?.let { repo -> viewModelScope.launch { repo.setProtectionSettings(next.toStored()) } }
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
        viewModelScope.launch {
            while (true) {
                delay(1400)
                _state.update { it.copy(blocked = it.blocked + 1 + Random.nextInt(3)) }
            }
        }
        // "LEARNING RIGHT NOW" counter — +1..4 every 1.9s (README §AI brain).
        viewModelScope.launch {
            while (true) {
                delay(1900)
                _state.update { it.copy(learned = it.learned + 1 + Random.nextInt(4)) }
            }
        }
        // Rotating activity ticker on the sign-in screen — every 2.8s.
        viewModelScope.launch {
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

    fun goQr() {
        qrJob?.cancel()
        _state.update { it.copy(screen = Screen.QR, qrPhase = QrPhase.IDLE, qrProgress = 0, qrVerdict = null) }
    }

    private fun setScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    // ───────────────────────── splash ─────────────────────────

    /** Called once the branded splash animation has played out. Leaves DASHBOARD alone if an
     *  account was already restored from disk while the splash was showing. */
    fun finishSplash() {
        _state.update { if (it.screen == Screen.SPLASH) it.copy(screen = Screen.SIGNIN) else it }
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
                settingsRepository?.let { repo -> viewModelScope.launch { repo.setLocalCredential(trimmedEmail, password) } }
            }
        }
    }

    private fun setCreateAccountError(message: String) {
        _state.update { it.copy(createAccountError = message) }
    }

    fun signOut() {
        _state.update {
            it.copy(screen = Screen.SIGNIN, account = null, hasScanned = false, fixed = emptySet(), scanData = ScanData())
        }
        persistAccount(null)
    }

    fun setGsiError(message: String?) {
        _state.update { it.copy(gsiError = message) }
    }

    private fun persistAccount(account: Account?) {
        settingsRepository ?: return
        viewModelScope.launch { settingsRepository.setAccount(account) }
    }

    // ───────────────────────── onboarding ─────────────────────────

    fun completeOnboarding() = setScreen(Screen.DASHBOARD)

    // ───────────────────────── theme ─────────────────────────

    fun toggleTheme() {
        val next = if (_state.value.theme == TpThemeMode.NIGHT) TpThemeMode.DAY else TpThemeMode.NIGHT
        applyTheme(next)
    }

    fun applyTheme(mode: TpThemeMode) {
        _state.update { it.copy(theme = mode) }
        settingsRepository?.let { repo -> viewModelScope.launch { repo.setTheme(mode) } }
    }

    // ───────────────────────── realtime + settings toggles ─────────────────────────

    fun toggleRealtime() {
        val next = !_state.value.realtime
        _state.update { it.copy(realtime = next) }
        syncProtectionService(next)
        settingsRepository?.let { repo -> viewModelScope.launch { repo.setRealtime(next) } }
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
        settingsRepository?.let { repo -> viewModelScope.launch { repo.setApiKey(id, value) } }
    }

    // ───────────────────────── scanning (real device scan) ─────────────────────────

    fun startScan() {
        val scanner = deviceScanner ?: return
        scanJob?.cancel()
        var feedSeq = 0L
        _state.update {
            it.copy(screen = Screen.SCANNING, progress = 0f, scannedCount = 0, fixed = emptySet(), scanPhase = ScanPhaseState(), scanFeed = emptyList())
        }
        scanJob = viewModelScope.launch {
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
                )
            }
            delay(400)
            _state.update { it.copy(screen = Screen.RESULTS, hasScanned = true) }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        setScreen(Screen.DASHBOARD)
    }

    fun fixSelected() {
        val sel = Derived.selectedFinding(_state.value) ?: return
        _state.update { it.copy(fixed = it.fixed + sel.id) }
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
        val audit = permissionAudit ?: return
        if (_state.value.scanData.permApps.isNotEmpty() || permJob?.isActive == true) return
        permJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { audit.audit() }
            _state.update {
                it.copy(scanData = it.scanData.copy(permApps = result.apps, appsScanned = result.totalInstalledCount))
            }
        }
    }

    fun togglePermission(app: String, permId: String) {
        val key = "$app|$permId"
        _state.update { s ->
            val off = s.permOff.toMutableSet()
            if (!off.add(key)) off.remove(key)
            s.copy(permOff = off)
        }
    }

    fun turnOffAllRiskyPermissions() {
        _state.update { s ->
            val off = s.permOff.toMutableSet()
            s.scanData.permApps.forEach { app ->
                app.perms.forEach { p -> if (p.risk) off.add("${app.app}|${p.id}") }
            }
            s.copy(permOff = off)
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

    private fun runQrCheck(payload: String, index: Int) {
        qrJob?.cancel()
        _state.update { it.copy(qrPhase = QrPhase.SCANNING, qrIndex = index, qrProgress = 0, qrVerdict = null) }
        qrJob = viewModelScope.launch {
            val progressJob = launch {
                while (_state.value.qrProgress < 92) {
                    delay(60)
                    _state.update { it.copy(qrProgress = (it.qrProgress + 8).coerceAtMost(92)) }
                }
            }
            val verdict = threatIntel.checkUrl(payload, _state.value.apiKeys)
            progressJob.cancel()
            _state.update { it.copy(qrProgress = 100, qrPhase = QrPhase.RESULT, qrVerdict = verdict) }
        }
    }

    fun rescanQr() {
        qrJob?.cancel()
        _state.update { it.copy(qrPhase = QrPhase.IDLE, qrProgress = 0, qrVerdict = null) }
    }

    // ───────────────────────── data breach security (free, keyless, live) ─────────────────────────

    fun checkMyBreaches() {
        val email = _state.value.account?.email ?: return
        if (_state.value.breachChecking) return
        _state.update { it.copy(breachChecking = true) }
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    fun hwBlock() = _state.update { it.copy(hwHandled = HwHandled.BLOCK) }
    fun hwAllow() = _state.update { it.copy(hwHandled = HwHandled.ALLOW) }
    fun hwDismiss() = _state.update { it.copy(hwAlert = null, hwHandled = null) }

    // ───────────────────────── chat (Bluetooth, post-quantum encrypted — README §Chat) ─────────────────────────

    fun goChat() {
        setScreen(Screen.CHAT)
        bluetoothChatManager?.startListening()
    }

    fun goChatHistory() = setScreen(Screen.CHAT_HISTORY)

    fun leaveChatHistory() = setScreen(Screen.CHAT)

    /** Leaves the Chat feature entirely — stops the listening server socket and discovery, clears the session. */
    fun leaveChat() {
        bluetoothChatManager?.shutdown()
        _state.update {
            it.copy(
                chatMessages = emptyList(),
                chatPeerName = null,
                chatMeshPeer = null,
                btDiscoveredDevices = emptyList(),
                btConnState = com.threadprotection.app.chat.BtChatConnState.IDLE,
            )
        }
        setScreen(Screen.DASHBOARD)
    }

    fun setChatMode(mode: ChatMode) {
        _state.update { it.copy(chatMode = mode) }
        if (mode == ChatMode.INTERNET) {
            appContext?.let { ctx ->
                NotificationHelper.postComingSoon(
                    ctx,
                    "Internet chat — coming soon",
                    "Chatting over the internet isn't available yet. Bluetooth chat with nearby devices works right now.",
                    com.threadprotection.app.MainActivity.TARGET_CHAT,
                )
            }
        }
    }

    fun startBtDiscovery() {
        bluetoothChatManager?.startDiscovery()
    }

    fun connectToBtDevice(address: String) {
        _state.update { it.copy(chatMessages = emptyList()) }
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
            val id = bluetoothChatManager?.sendText(text) ?: return
            _state.update {
                it.copy(
                    chatMessages = it.chatMessages + ChatUiMessage(id, text, fromMe = true, timestampMs = System.currentTimeMillis(), delivered = false),
                    chatDraft = "",
                )
            }
            return
        }

        // No live connection — fall back to the store-and-forward mesh relay if this contact's
        // long-term key is on file (only true once you've connected to them directly at least
        // once; see BluetoothChatManager's identity exchange).
        val peer = _state.value.chatMeshPeer
        val mesh = meshRelayManager
        if (peer == null || mesh == null || !peer.meshReachable) return
        val publicKeyBytes = runCatching { Base64.decode(peer.publicKeyB64, Base64.NO_WRAP) }.getOrNull() ?: return
        val senderName = _state.value.account?.name?.takeIf { it.isNotBlank() } ?: "Thread Protection user"
        viewModelScope.launch { mesh.queueOutbound(peer.nodeId, publicKeyBytes, senderName, text) }
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
    }

    fun disconnectChatPeer() {
        bluetoothChatManager?.disconnect()
        _state.update { it.copy(chatMessages = emptyList(), chatPeerName = null, chatMeshPeer = null, screen = Screen.CHAT) }
    }

    companion object {
        private const val ESTIMATED_ITEMS = 300
        private const val SCAN_FEED_LIMIT = 8
    }
}
