package com.threadprotection.app.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threadprotection.app.data.Account
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.network.ThreatIntelRepository
import com.threadprotection.app.scan.DeviceScanner
import com.threadprotection.app.scan.HardwareWatcher
import com.threadprotection.app.scan.PermissionAudit
import com.threadprotection.app.ui.theme.TpThemeMode
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

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val threatIntel = ThreatIntelRepository()
    private val deviceScanner by lazy { appContext?.let { DeviceScanner(it, threatIntel) } }
    private val permissionAudit by lazy { appContext?.let { PermissionAudit(it) } }
    private val hardwareWatcher by lazy { appContext?.let { HardwareWatcher(it) } }

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
        }
        refreshHardwareStatus()
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

    fun goQr() {
        qrJob?.cancel()
        _state.update { it.copy(screen = Screen.QR, qrPhase = QrPhase.IDLE, qrProgress = 0, qrVerdict = null) }
    }

    private fun setScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
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
            s.copy(settings = next)
        }
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
        _state.update {
            it.copy(screen = Screen.SCANNING, progress = 0f, scannedCount = 0, fixed = emptySet(), scanPhase = ScanPhaseState())
        }
        scanJob = viewModelScope.launch {
            val result = scanner.scan(_state.value.apiKeys) { update ->
                _state.update {
                    val pct = ((update.index.toFloat() + 1f) / update.total.toFloat()) * 100f
                    it.copy(
                        progress = pct,
                        scannedCount = (pct / 100f * ESTIMATED_ITEMS).toInt(),
                        scanPhase = ScanPhaseState(update.index, update.total, update.label, update.meta),
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

    fun fixAll() {
        val ids = Derived.threats(_state.value).map { it.id }.toSet()
        _state.update { it.copy(fixed = ids) }
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

    companion object {
        private const val ESTIMATED_ITEMS = 300
    }
}
