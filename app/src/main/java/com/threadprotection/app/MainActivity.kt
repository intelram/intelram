package com.threadprotection.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.state.AppViewModel
import com.threadprotection.app.state.Screen
import com.threadprotection.app.state.SplashPhase
import kotlinx.coroutines.flow.first
import com.threadprotection.app.ui.screens.AiBrainScreen
import com.threadprotection.app.ui.screens.AppPermissionDetailScreen
import com.threadprotection.app.ui.screens.AppPermissionsScreen
import com.threadprotection.app.ui.screens.ChatConversationScreen
import com.threadprotection.app.ui.screens.ChatHistoryScreen
import com.threadprotection.app.ui.screens.ChatSessionScreen
import com.threadprotection.app.ui.screens.ChatScreen
import com.threadprotection.app.ui.screens.CreateAccountScreen
import com.threadprotection.app.ui.screens.DashboardScreen
import com.threadprotection.app.ui.screens.DataBreachScreen
import com.threadprotection.app.hardware.DeviceTransport
import com.threadprotection.app.hardware.DeviceTrust
import com.threadprotection.app.ui.screens.ExternalDeviceAlertOverlay
import com.threadprotection.app.ui.screens.HardwareAlertOverlay
import com.threadprotection.app.ui.screens.HardwareDetailScreen
import com.threadprotection.app.ui.screens.IncomingCallOverlay
import com.threadprotection.app.ui.screens.IncomingChatRequestOverlay
import com.threadprotection.app.ui.screens.OnboardingScreen
import com.threadprotection.app.ui.screens.OpenPortsScreen
import com.threadprotection.app.ui.screens.OperatingSystemScreen
import com.threadprotection.app.ui.screens.OtpSecurityScreen
import com.threadprotection.app.ui.screens.QrScannerScreen
import com.threadprotection.app.ui.screens.ResultsScreen
import com.threadprotection.app.ui.screens.ScanEmailScreen
import com.threadprotection.app.ui.screens.ScanWebsiteScreen
import com.threadprotection.app.ui.screens.ScanningScreen
import com.threadprotection.app.ui.screens.SettingsScreen
import com.threadprotection.app.ui.screens.SignInScreen
import com.threadprotection.app.ui.screens.RealScanSplashScreen
import com.threadprotection.app.ui.screens.SplashScreen
import com.threadprotection.app.ui.screens.ThreatDetailScreen
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.ThreadProtectionTheme
import com.threadprotection.app.ui.theme.TpThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel((application as ThreadProtectionApp).applicationContext, (application as ThreadProtectionApp).settingsRepository) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            ThreadProtectionTheme(mode = state.theme) {
                val palette = LocalTpPalette.current
                val context = LocalContext.current

                // A cold start must always play the splash animation, even when launched via a
                // deep link (the App Permissions Quick Settings Tile, a notification tap). Root
                // cause this fixes: handleTargetScreenIntent() used to run directly in onCreate,
                // before setContent — which jumped straight to the target screen before Compose
                // ever composed a single frame, so that launch path never showed the splash at
                // all. Waiting for the very first move off Screen.SPLASH (whether to Sign-in or
                // straight to Dashboard for a returning account) means the animation always plays,
                // then the deep link is honored exactly as before. LaunchedEffect(Unit) keeps this
                // a one-shot: it must not re-fire and yank the user back to the target screen on
                // every later, unrelated state change.
                LaunchedEffect(Unit) {
                    snapshotFlow { state.screen }.first { it != Screen.SPLASH }
                    handleTargetScreenIntent(intent)
                }

                // User-requested, repeatedly and explicitly: the branded splash (and the real scan
                // behind it) should appear "whenever somebody opened the application," not only on
                // a true cold process start. ProcessLifecycleOwner tracks the whole app's foreground
                // state, not this one Activity's — unlike an Activity-level onResume, it does NOT
                // re-fire for incidental in-app blips (a permission dialog, a picked file, an
                // orientation change), only for a genuine "the user left every screen of this app
                // and came back" transition. See AppViewModel.replayLaunchExperience()'s doc for why
                // its own guard makes the very first (cold-start) ON_START a safe no-op here.
                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) viewModel.replayLaunchExperience()
                    }
                    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                    onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
                }

                // The app's own day/night toggle is independent of the system theme, so status/nav
                // bar icon contrast has to follow it explicitly — otherwise light-on-light or
                // dark-on-dark icons can go unreadable depending on what the system happens to be set to.
                SideEffect {
                    val style = if (state.theme == TpThemeMode.NIGHT) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }

                // One batched request, not several single-permission launchers fired back to back.
                // Android only shows one permission dialog at a time, so launching four separate
                // RequestPermission contracts in the same frame meant every launch after the first
                // was silently dropped — which is exactly why BLUETOOTH_ADVERTISE was never
                // actually granted and Chat's BLE presence advertising never started, leaving the
                // other phone undiscoverable. RequestMultiplePermissions queues them properly.
                //
                // ACCESS_FINE_LOCATION is a real use, not a blanket ask: the "Connected hardware"/
                // "Operating system" audit reads the current Wi-Fi network name
                // (WifiInfo.currentSsid), which Android ties to location permission on every
                // version. Requesting it here (not buried in a sub-screen) is what surfaces the
                // OS's own three-way "While using the app / Only this time / Don't allow" choice on
                // Android 11+ right away. It also covers BLE scanning below API 31.
                val startupPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_ADVERTISE)
                        }
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    startupPermissions.launch(wanted.toTypedArray())
                }

                val openAppSettings: (String) -> Unit = { packageName ->
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }

                // Android's own Permission manager (Settings → Privacy → Permission manager), the
                // only real place a user can review permissions across every app at once. There is
                // no API for an app to revoke another app's permissions in bulk, or at all.
                val openPermissionManager: () -> Unit = {
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        // Not every OEM build ships a privacy screen; the all-apps list always exists.
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }

                // Real uninstall request: Android reserves the actual removal (and its confirmation
                // dialog) for the system itself — ACTION_DELETE is the standard way a third-party
                // app asks for that, same as openAppSettings above asks for the permission screen.
                val uninstallApp: (String) -> Unit = { packageName ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }

                // "Fix" on a real scan finding can't change anything itself — Android gives 3rd-party
                // apps no API to revoke another app's permission, close a listening port or patch the
                // OS — so it opens the exact system screen where the user can do it themselves.
                val openRemedy: (com.threadprotection.app.data.Remedy) -> Unit = { remedy ->
                    when (remedy) {
                        is com.threadprotection.app.data.Remedy.AppSettings -> openAppSettings(remedy.packageName)
                        com.threadprotection.app.data.Remedy.DeveloperOptions -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        com.threadprotection.app.data.Remedy.SystemUpdate -> runCatching {
                            context.startActivity(Intent("android.settings.SYSTEM_UPDATE_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        is com.threadprotection.app.data.Remedy.PlayStore -> runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${remedy.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.recoverCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${remedy.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        com.threadprotection.app.data.Remedy.None -> Unit
                    }
                }

                val openBluetoothSettings: () -> Unit = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }

                // QR "Open link": this app has already done its on-device + reputation analysis of
                // the URL — opening it is the phone's own default browser's job, not this app's, so
                // it hands off via ACTION_VIEW exactly like the PlayStore remedy above rather than
                // rendering any web content itself.
                val openUrlInBrowser: (String) -> Unit = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.bg)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    // Shared across the two Analyst Mode screens so opening a CVE's detail from the
                    // search list and navigating there are backed by the same CveViewModel
                    // instance — the search results stay intact underneath, and the detail
                    // screen sees the id that was just tapped. See CveViewModel's split
                    // search/detail state for why one shared instance needs two state slices.
                    val cveDetailViewModel: com.threadprotection.app.analyst.presentation.cve.CveViewModel = androidx.hilt.navigation.compose.hiltViewModel()

                    when (state.screen) {
                        Screen.SPLASH -> {
                            BackHandler(enabled = true) { /* no-op: can't back out of the splash */ }
                            // Two phases of one continuous screen, not a splash followed by a
                            // second scanning screen — see AppViewModel.runAutoScanOnSplash()'s doc.
                            when (state.splashPhase) {
                                SplashPhase.BRANDING -> SplashScreen(onFinished = viewModel::finishSplash)
                                SplashPhase.REAL_SCAN -> RealScanSplashScreen(
                                    progressPct = state.progress,
                                    scannedCount = state.scannedCount,
                                    phaseLabel = state.scanPhase.label,
                                )
                            }
                        }

                        Screen.SIGNIN -> SignInScreen(
                            blockedCount = state.blocked,
                            tickerText = DemoData.ticker[state.tickIdx],
                            gsiError = state.gsiError,
                            onSignedIn = viewModel::signInWithGoogle,
                            onCreateAccount = viewModel::goCreateAccount,
                            onGsiError = viewModel::setGsiError,
                        )

                        Screen.CREATE_ACCOUNT -> {
                            BackHandler(enabled = true) { viewModel.backToSignIn() }
                            CreateAccountScreen(
                                error = state.createAccountError,
                                onBack = viewModel::backToSignIn,
                                onCreate = viewModel::createAccount,
                            )
                        }

                        Screen.ONBOARDING -> {
                            BackHandler(enabled = true) { /* no-op: must complete onboarding */ }
                            OnboardingScreen(onGetStarted = viewModel::completeOnboarding)
                        }

                        Screen.DASHBOARD -> DashboardScreen(
                            state = state,
                            onToggleTheme = viewModel::toggleTheme,
                            onStartScan = viewModel::startScan,
                            onToggleRealtime = viewModel::toggleRealtime,
                            onGoQr = viewModel::goQr,
                            onGoPerms = viewModel::goPerms,
                            onGoChat = viewModel::goChat,
                            onGoBrain = viewModel::goBrain,
                            onGoSettings = viewModel::goSettings,
                            onGoOtpSecurity = viewModel::goOtpSecurity,
                            onGoDataBreach = viewModel::goDataBreach,
                            onGoScanWebsite = viewModel::goScanWebsite,
                            onGoScanEmail = viewModel::goScanEmail,
                            onGoHardwareDetail = viewModel::goHardwareDetail,
                            onGoPortsDetail = viewModel::goPortsDetail,
                            onGoOsDetail = viewModel::goOsDetail,
                            onToggleHwOpen = viewModel::toggleHwOpen,
                            onSimulateHw = viewModel::simulateHw,
                        )

                        Screen.SCANNING -> {
                            BackHandler(enabled = true) { viewModel.cancelScan() }
                            ScanningScreen(
                                progress = state.progress,
                                scannedCount = state.scannedCount,
                                phase = state.scanPhase,
                                feed = state.scanFeed,
                                onCancel = viewModel::cancelScan,
                            )
                        }

                        Screen.RESULTS -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            ResultsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onOpen = viewModel::openFinding,
                                onStartFixing = viewModel::startFixing,
                            )
                        }

                        Screen.DETAIL -> {
                            BackHandler(enabled = true) { viewModel.backToResults() }
                            ThreatDetailScreen(
                                state = state,
                                onBack = viewModel::backToResults,
                                onVoteUp = viewModel::voteUp,
                                onVoteDown = viewModel::voteDown,
                                onFix = viewModel::fixSelected,
                                onBeginFix = viewModel::beginFix,
                                onUnresolve = viewModel::unresolveFinding,
                                onIgnore = viewModel::ignoreSelectedFinding,
                                onUnignore = viewModel::unignoreFinding,
                                resolved = com.threadprotection.app.state.Derived.selectedFinding(state)?.id
                                    ?.let { it in state.fixed } == true,
                                ignored = com.threadprotection.app.state.Derived.selectedFinding(state)?.id
                                    ?.let { it in state.ignoredFindings } == true,
                                onOpenRemedy = openRemedy,
                            )
                        }

                        Screen.QR -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            QrScannerScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onPick = viewModel::startQr,
                                onDecoded = viewModel::analyzeScannedPayload,
                                onRescan = viewModel::rescanQr,
                                onToggleTorch = viewModel::toggleQrTorch,
                                onOpenLink = openUrlInBrowser,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                            )
                        }

                        Screen.BRAIN -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            AiBrainScreen(
                                learned = state.learned.toLong(),
                                onGoHome = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoSettings = viewModel::goSettings,
                            )
                        }

                        Screen.PERMS -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            AppPermissionsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onOpenPermissionManager = openPermissionManager,
                                onOpenDetail = viewModel::openAppPermissionDetail,
                            )
                        }

                        Screen.APP_PERMISSION_DETAIL -> {
                            BackHandler(enabled = true) { viewModel.closeAppPermissionDetail() }
                            AppPermissionDetailScreen(
                                state = state,
                                onBack = viewModel::closeAppPermissionDetail,
                                onOpenAppSettings = openAppSettings,
                                onUninstall = uninstallApp,
                                onRefresh = viewModel::refreshPermissions,
                            )
                        }

                        Screen.OTP_SECURITY -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            OtpSecurityScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onOpenAppSettings = openAppSettings,
                            )
                        }

                        Screen.DATA_BREACH -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            DataBreachScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onCheck = viewModel::checkMyBreaches,
                            )
                        }

                        Screen.SCAN_WEBSITE -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            ScanWebsiteScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onUrlChange = viewModel::setWebsiteUrl,
                                onCheck = viewModel::checkWebsite,
                            )
                        }

                        Screen.SCAN_EMAIL -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            ScanEmailScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onSenderChange = viewModel::setEmailSender,
                                onBodyChange = viewModel::setEmailBody,
                                onCheck = viewModel::checkEmail,
                            )
                        }

                        Screen.HARDWARE_DETAIL -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            HardwareDetailScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onOpenBluetoothSettings = openBluetoothSettings,
                            )
                        }

                        Screen.PORTS_DETAIL -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            OpenPortsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onOpenDeveloperOptions = { openRemedy(com.threadprotection.app.data.Remedy.DeveloperOptions) },
                            )
                        }

                        Screen.OS_DETAIL -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            OperatingSystemScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onCheckForUpdates = { openRemedy(com.threadprotection.app.data.Remedy.SystemUpdate) },
                            )
                        }

                        Screen.CHAT -> {
                            BackHandler(enabled = true) { viewModel.leaveChat() }
                            ChatScreen(
                                state = state,
                                onBack = viewModel::leaveChat,
                                onGoQr = viewModel::goQr,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onSetChatMode = viewModel::setChatMode,
                                onStartDiscovery = viewModel::startBtDiscovery,
                                onConnect = viewModel::connectToBtDevice,
                                onGoHistory = viewModel::goChatHistory,
                                onAcceptRequest = viewModel::acceptIncomingChatRequest,
                                onDenyRequest = viewModel::denyIncomingChatRequest,
                                onDismissError = viewModel::dismissChatError,
                                onRetryConnect = viewModel::retryBtConnect,
                                onResumeConversation = viewModel::resumeChatConversation,
                            )
                        }

                        Screen.CHAT_HISTORY -> {
                            BackHandler(enabled = true) { viewModel.leaveChatHistory() }
                            ChatHistoryScreen(
                                state = state,
                                onBack = viewModel::leaveChatHistory,
                                onSelect = viewModel::messageFromHistory,
                                onOpenSession = viewModel::openStoredSession,
                                onClearSessions = viewModel::clearStoredSessions,
                            )
                        }

                        Screen.CHAT_SESSION -> {
                            BackHandler(enabled = true) { viewModel.closeStoredSession() }
                            ChatSessionScreen(
                                state = state,
                                onBack = viewModel::closeStoredSession,
                                onDelete = viewModel::deleteStoredSession,
                            )
                        }

                        Screen.CHAT_CONVERSATION -> {
                            BackHandler(enabled = true) { viewModel.leaveChatConversation() }
                            ChatConversationScreen(
                                state = state,
                                onBack = viewModel::leaveChatConversation,
                                onDraftChange = viewModel::setChatDraft,
                                onSend = viewModel::sendChatMessage,
                                onExitChat = viewModel::exitChat,
                                onStartCall = viewModel::startCall,
                                onEndCall = viewModel::endCall,
                                onToggleMute = viewModel::toggleCallMute,
                            )
                        }

                        Screen.SETTINGS -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goDashboard() }
                            SettingsScreen(
                                state = state,
                                onGoHome = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onSignOut = viewModel::signOut,
                                onSignInGoogle = { viewModel.signInWithGoogle() },
                                onPickTheme = viewModel::applyTheme,
                                onToggleSetting = viewModel::toggleProtectionSetting,
                                onSetApiKey = viewModel::setApiKey,
                                onAddQuickSettingsTile = { requestAddQuickSettingsTile(context) },
                                onSetScheduledScanTime = viewModel::setScheduledScanTime,
                                onSetScheduledScanFrequency = viewModel::setScheduledScanFrequency,
                                onGoAnalystTools = viewModel::goAnalystCveSearch,
                            )
                        }

                        // Analyst Mode (CVE/EPSS/KEV). Screens are backed by their own Hilt
                        // CveViewModel, not this Activity's AppViewModel — see
                        // analyst/presentation/cve/CveViewModel.kt. Only screen navigation
                        // (the shared Screen enum / back-stack) runs through AppViewModel, same
                        // as every other screen.
                        Screen.ANALYST_CVE_SEARCH -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goSettings() }
                            com.threadprotection.app.analyst.presentation.cve.ui.CveSearchScreen(
                                onBack = { if (!viewModel.navigateBack()) viewModel.goSettings() },
                                onOpenDetail = { cveId ->
                                    cveDetailViewModel.openDetail(cveId)
                                    viewModel.goAnalystCveDetail()
                                },
                                viewModel = cveDetailViewModel,
                            )
                        }

                        Screen.ANALYST_CVE_DETAIL -> {
                            BackHandler(enabled = true) { if (!viewModel.navigateBack()) viewModel.goAnalystCveSearch() }
                            com.threadprotection.app.analyst.presentation.cve.ui.CveDetailScreen(
                                onBack = { if (!viewModel.navigateBack()) viewModel.goAnalystCveSearch() },
                                viewModel = cveDetailViewModel,
                            )
                        }
                    }

                    // Sits outside the screen `when`, so an incoming chat request interrupts
                    // whatever the user is looking at — Dashboard, Settings, anywhere. Previously
                    // the Accept/Deny card lived only inside the Chat screen, so a request that
                    // arrived while the user was elsewhere was simply never shown.
                    state.incomingChatRequest?.let { request ->
                        IncomingChatRequestOverlay(
                            displayName = request.displayName,
                            onAccept = viewModel::acceptIncomingChatRequest,
                            onDeny = viewModel::denyIncomingChatRequest,
                        )
                    }

                    // Same reasoning as the chat-request overlay above: a call can arrive while the
                    // user is anywhere in the app, not just inside ChatConversationScreen, so it has
                    // to be rendered at this outer level too rather than only inside that screen.
                    if (state.callState == com.threadprotection.app.chat.BtCallState.RINGING) {
                        IncomingCallOverlay(
                            displayName = state.chatPeerName ?: "Unknown device",
                            onAccept = viewModel::acceptCall,
                            onDecline = viewModel::declineCall,
                        )
                    }

                    // Real device connections outrank the demo hardware alert, so this comes first.
                    state.deviceAlert?.let { device ->
                        ExternalDeviceAlertOverlay(
                            device = device,
                            decision = state.deviceTrust[device.id] ?: DeviceTrust.UNKNOWN,
                            onBlock = { viewModel.blockExternalDevice(device.id) },
                            onAllowOnce = { viewModel.allowExternalDeviceOnce(device.id) },
                            onDone = viewModel::dismissDeviceAlert,
                            onOpenSettings = { transport ->
                                // Only Bluetooth has a settings screen worth opening — a USB device
                                // is unplugged by hand, and pretending otherwise would be a dead end.
                                if (transport == DeviceTransport.BLUETOOTH) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.recoverCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                            },
                        )
                    }

                    state.hwAlert?.let { alert ->
                        HardwareAlertOverlay(
                            alert = alert,
                            handled = state.hwHandled,
                            onBlock = viewModel::hwBlock,
                            onAllow = viewModel::hwAllow,
                            onDone = viewModel::hwDismiss,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTargetScreenIntent(intent)
    }

    private fun requestAddQuickSettingsTile(context: android.content.Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
        runCatching {
            statusBarManager.requestAddTileService(
                android.content.ComponentName(context, com.threadprotection.app.tile.AppPermissionsTileService::class.java),
                "App Permissions",
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_tile_permissions),
                java.util.concurrent.Executor { runnable -> runnable.run() },
                java.util.function.Consumer<Int> { }, // user accepted/denied — nothing to react to here
            )
        }
    }

    /**
     * Handles two different intents that can bring the user here from outside the app: this app's
     * own deep-link scheme ([ACTION_OPEN_SCREEN], from the Quick Settings tile / notifications) and
     * Android's standard Share sheet ([Intent.ACTION_SEND]) — "Share" on an email from Gmail or any
     * mail app lands its subject/body straight on the email-check screen, already running. This is
     * the entire "integration" with email apps this app has, and deliberately so — see
     * EmailInspector's doc for why it never talks to Gmail's API directly.
     */
    private fun handleTargetScreenIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            viewModel.receiveSharedEmailText(intent.getStringExtra(Intent.EXTRA_SUBJECT), text)
            return
        }
        if (intent?.action != ACTION_OPEN_SCREEN) return
        when (intent.getStringExtra(EXTRA_TARGET_SCREEN)) {
            TARGET_PERMS -> viewModel.goPerms()
            TARGET_DASHBOARD -> viewModel.goDashboard()
            TARGET_SETTINGS -> viewModel.goSettings()
            TARGET_CHAT -> viewModel.goChat()
            TARGET_ANALYST_CVE -> viewModel.goAnalystCveSearch()
        }
    }

    companion object {
        const val ACTION_OPEN_SCREEN = "com.threadprotection.app.action.OPEN_SCREEN"
        const val EXTRA_TARGET_SCREEN = "target_screen"
        const val TARGET_PERMS = "perms"
        const val TARGET_DASHBOARD = "dashboard"
        const val TARGET_SETTINGS = "settings"
        const val TARGET_CHAT = "chat"
        const val TARGET_ANALYST_CVE = "analyst_cve"
    }
}
