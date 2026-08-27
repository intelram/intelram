package com.threadprotection.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.state.AppViewModel
import com.threadprotection.app.state.Screen
import com.threadprotection.app.ui.screens.AiBrainScreen
import com.threadprotection.app.ui.screens.AppPermissionDetailScreen
import com.threadprotection.app.ui.screens.AppPermissionsScreen
import com.threadprotection.app.ui.screens.ChatConversationScreen
import com.threadprotection.app.ui.screens.ChatHistoryScreen
import com.threadprotection.app.ui.screens.ChatScreen
import com.threadprotection.app.ui.screens.CreateAccountScreen
import com.threadprotection.app.ui.screens.DashboardScreen
import com.threadprotection.app.ui.screens.DataBreachScreen
import com.threadprotection.app.ui.screens.HardwareAlertOverlay
import com.threadprotection.app.ui.screens.HardwareDetailScreen
import com.threadprotection.app.ui.screens.OnboardingScreen
import com.threadprotection.app.ui.screens.OpenPortsScreen
import com.threadprotection.app.ui.screens.OperatingSystemScreen
import com.threadprotection.app.ui.screens.OtpSecurityScreen
import com.threadprotection.app.ui.screens.QrScannerScreen
import com.threadprotection.app.ui.screens.ResultsScreen
import com.threadprotection.app.ui.screens.ScanWebsiteScreen
import com.threadprotection.app.ui.screens.ScanningScreen
import com.threadprotection.app.ui.screens.SettingsScreen
import com.threadprotection.app.ui.screens.SignInScreen
import com.threadprotection.app.ui.screens.SplashScreen
import com.threadprotection.app.ui.screens.ThreatDetailScreen
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.ThreadProtectionTheme
import com.threadprotection.app.ui.theme.TpThemeMode

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
        handleTargetScreenIntent(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            ThreadProtectionTheme(mode = state.theme) {
                val palette = LocalTpPalette.current
                val context = LocalContext.current

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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.bg)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    when (state.screen) {
                        Screen.SPLASH -> {
                            BackHandler(enabled = true) { /* no-op: can't back out of the splash */ }
                            SplashScreen(onFinished = viewModel::finishSplash)
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
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            ResultsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onOpen = viewModel::openFinding,
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
                                onOpenRemedy = openRemedy,
                            )
                        }

                        Screen.QR -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            QrScannerScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onPick = viewModel::startQr,
                                onDecoded = viewModel::analyzeScannedPayload,
                                onRescan = viewModel::rescanQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                            )
                        }

                        Screen.BRAIN -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            AiBrainScreen(
                                learned = state.learned.toLong(),
                                onGoHome = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoSettings = viewModel::goSettings,
                            )
                        }

                        Screen.PERMS -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            AppPermissionsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoChat = viewModel::goChat,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onTurnOffAllRisky = viewModel::turnOffAllRiskyPermissions,
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
                                onTogglePermission = viewModel::togglePermission,
                            )
                        }

                        Screen.OTP_SECURITY -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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

                        Screen.HARDWARE_DETAIL -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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
                            )
                        }

                        Screen.CHAT_HISTORY -> {
                            BackHandler(enabled = true) { viewModel.leaveChatHistory() }
                            ChatHistoryScreen(
                                state = state,
                                onBack = viewModel::leaveChatHistory,
                                onSelect = viewModel::messageFromHistory,
                            )
                        }

                        Screen.CHAT_CONVERSATION -> {
                            BackHandler(enabled = true) { viewModel.disconnectChatPeer() }
                            ChatConversationScreen(
                                state = state,
                                onBack = viewModel::disconnectChatPeer,
                                onDraftChange = viewModel::setChatDraft,
                                onSend = viewModel::sendChatMessage,
                            )
                        }

                        Screen.SETTINGS -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
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
                            )
                        }
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

    private fun handleTargetScreenIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_SCREEN) return
        when (intent.getStringExtra(EXTRA_TARGET_SCREEN)) {
            TARGET_PERMS -> viewModel.goPerms()
            TARGET_DASHBOARD -> viewModel.goDashboard()
            TARGET_SETTINGS -> viewModel.goSettings()
            TARGET_CHAT -> viewModel.goChat()
        }
    }

    companion object {
        const val ACTION_OPEN_SCREEN = "com.threadprotection.app.action.OPEN_SCREEN"
        const val EXTRA_TARGET_SCREEN = "target_screen"
        const val TARGET_PERMS = "perms"
        const val TARGET_DASHBOARD = "dashboard"
        const val TARGET_SETTINGS = "settings"
        const val TARGET_CHAT = "chat"
    }
}
