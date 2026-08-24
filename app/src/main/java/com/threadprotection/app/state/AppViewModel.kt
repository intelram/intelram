package com.threadprotection.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threadprotection.app.data.Account
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.ui.theme.TpThemeMode
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TOTAL_SCAN_ITEMS = 2384
private const val SCAN_DURATION_MS = 8_000
private const val SCAN_TICK_MS = 60L
private const val QR_DURATION_MS = 750
private const val QR_TICK_MS = 60L

class AppViewModel(private val settingsRepository: SettingsRepository? = null) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var qrJob: Job? = null

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
    fun goPerms() = setScreen(Screen.PERMS)
    fun goBrain() = setScreen(Screen.BRAIN)
    fun backToResults() = setScreen(Screen.RESULTS)

    fun goQr() {
        qrJob?.cancel()
        _state.update { it.copy(screen = Screen.QR, qrPhase = QrPhase.IDLE, qrProgress = 0) }
    }

    private fun setScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    // ───────────────────────── sign-in ─────────────────────────

    fun signInWithGoogle(account: Account = DemoData.demoAccount) {
        _state.update { it.copy(account = account, screen = Screen.ONBOARDING, gsiError = null) }
        persistAccount(account)
    }

    fun skipSignIn() = setScreen(Screen.ONBOARDING)

    fun signOut() {
        _state.update {
            it.copy(screen = Screen.SIGNIN, account = null, hasScanned = false, fixed = emptySet())
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
        _state.update { it.copy(realtime = !it.realtime) }
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

    // ───────────────────────── scanning ─────────────────────────

    fun startScan() {
        scanJob?.cancel()
        _state.update { it.copy(screen = Screen.SCANNING, progress = 0f, scannedCount = 0, fixed = emptySet()) }
        scanJob = viewModelScope.launch {
            val steps = SCAN_DURATION_MS / SCAN_TICK_MS
            val stepSize = 100f / steps
            while (_state.value.progress < 100f) {
                delay(SCAN_TICK_MS)
                _state.update {
                    val p = (it.progress + stepSize * (0.6f + Random.nextFloat() * 0.8f)).coerceAtMost(100f)
                    it.copy(progress = p, scannedCount = ((p / 100f) * TOTAL_SCAN_ITEMS).toInt())
                }
            }
            delay(500)
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
        val sel = Derived.selectedFinding(_state.value)
        _state.update { it.copy(fixed = it.fixed + sel.id) }
    }

    fun openFinding(id: String) {
        _state.update { it.copy(selectedId = id, screen = Screen.DETAIL) }
    }

    fun voteUp() {
        val sel = Derived.selectedFinding(_state.value)
        _state.update { it.copy(votes = it.votes + (sel.id to Vote.UP), learned = it.learned + 1) }
    }

    fun voteDown() {
        val sel = Derived.selectedFinding(_state.value)
        _state.update { it.copy(votes = it.votes + (sel.id to Vote.DOWN), learned = it.learned + 1) }
    }

    // ───────────────────────── QR scanner ─────────────────────────

    fun startQr(index: Int) {
        qrJob?.cancel()
        _state.update { it.copy(qrPhase = QrPhase.SCANNING, qrIndex = index, qrProgress = 0) }
        qrJob = viewModelScope.launch {
            while (_state.value.qrProgress < 100) {
                delay(QR_TICK_MS)
                _state.update { it.copy(qrProgress = (it.qrProgress + 8).coerceAtMost(100)) }
            }
            _state.update { it.copy(qrPhase = QrPhase.RESULT) }
        }
    }

    fun rescanQr() {
        qrJob?.cancel()
        _state.update { it.copy(qrPhase = QrPhase.IDLE, qrProgress = 0) }
    }

    // ───────────────────────── hardware watch ─────────────────────────

    fun toggleHwOpen() {
        _state.update { it.copy(hwOpen = !it.hwOpen) }
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

    // ───────────────────────── app permissions ─────────────────────────

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
            DemoData.appPerms.forEach { app ->
                app.perms.forEach { p -> if (p.risk) off.add("${app.app}|${p.id}") }
            }
            s.copy(permOff = off)
        }
    }
}
