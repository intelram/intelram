package com.threadprotection.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threadprotection.app.data.DemoData
import com.threadprotection.app.state.AppViewModel
import com.threadprotection.app.state.Screen
import com.threadprotection.app.ui.screens.AiBrainScreen
import com.threadprotection.app.ui.screens.AppPermissionsScreen
import com.threadprotection.app.ui.screens.CreateAccountScreen
import com.threadprotection.app.ui.screens.DashboardScreen
import com.threadprotection.app.ui.screens.HardwareAlertOverlay
import com.threadprotection.app.ui.screens.OnboardingScreen
import com.threadprotection.app.ui.screens.QrScannerScreen
import com.threadprotection.app.ui.screens.ResultsScreen
import com.threadprotection.app.ui.screens.ScanningScreen
import com.threadprotection.app.ui.screens.SettingsScreen
import com.threadprotection.app.ui.screens.SignInScreen
import com.threadprotection.app.ui.screens.ThreatDetailScreen
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.ThreadProtectionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as ThreadProtectionApp
            val viewModel: AppViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AppViewModel(app.applicationContext, app.settingsRepository) as T
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            ThreadProtectionTheme(mode = state.theme) {
                val palette = LocalTpPalette.current
                Box(modifier = Modifier.fillMaxSize().background(palette.bg)) {
                    when (state.screen) {
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
                            onGoBrain = viewModel::goBrain,
                            onGoSettings = viewModel::goSettings,
                            onToggleHwOpen = viewModel::toggleHwOpen,
                            onSimulateHw = viewModel::simulateHw,
                        )

                        Screen.SCANNING -> {
                            BackHandler(enabled = true) { viewModel.cancelScan() }
                            ScanningScreen(
                                progress = state.progress,
                                scannedCount = state.scannedCount,
                                phase = state.scanPhase,
                                onCancel = viewModel::cancelScan,
                            )
                        }

                        Screen.RESULTS -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            ResultsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onOpen = viewModel::openFinding,
                                onFixAll = viewModel::fixAll,
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
                                onGoSettings = viewModel::goSettings,
                            )
                        }

                        Screen.PERMS -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            AppPermissionsScreen(
                                state = state,
                                onBack = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoBrain = viewModel::goBrain,
                                onGoSettings = viewModel::goSettings,
                                onTogglePermission = viewModel::togglePermission,
                                onTurnOffAllRisky = viewModel::turnOffAllRiskyPermissions,
                            )
                        }

                        Screen.SETTINGS -> {
                            BackHandler(enabled = true) { viewModel.goDashboard() }
                            SettingsScreen(
                                state = state,
                                onGoHome = viewModel::goDashboard,
                                onGoQr = viewModel::goQr,
                                onGoBrain = viewModel::goBrain,
                                onSignOut = viewModel::signOut,
                                onSignInGoogle = { viewModel.signInWithGoogle() },
                                onPickTheme = viewModel::applyTheme,
                                onToggleSetting = viewModel::toggleProtectionSetting,
                                onSetApiKey = viewModel::setApiKey,
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
}
