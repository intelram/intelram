package com.intelram.shield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.intelram.shield.auth.AuthViewModel
import com.intelram.shield.bluetooth.BluetoothChatViewModel
import com.intelram.shield.scan.ScanUiState
import com.intelram.shield.scan.ScanViewModel
import com.intelram.shield.ui.components.BottomNavBar
import com.intelram.shield.ui.components.NavTab
import com.intelram.shield.ui.screens.ChatConversationScreen
import com.intelram.shield.ui.screens.EmailLinkCheckScreen
import com.intelram.shield.ui.screens.HomeScreen
import com.intelram.shield.ui.screens.NearbyChatScreen
import com.intelram.shield.ui.screens.OnboardingScreen
import com.intelram.shield.ui.screens.QrScanScreen
import com.intelram.shield.ui.screens.ResultsScreen
import com.intelram.shield.ui.screens.ScanningScreen
import com.intelram.shield.ui.screens.SettingsScreen
import com.intelram.shield.ui.screens.SignInScreen
import com.intelram.shield.ui.screens.ThreatDetailScreen
import com.intelram.shield.ui.theme.ThreatProtectionTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private object Routes {
    const val ONBOARDING = "onboarding"
    const val SIGNIN = "signin"
    const val HOME = "home"
    const val SCANNING = "scanning"
    const val RESULTS = "results"
    const val THREAT_DETAIL = "threat/{findingId}"
    const val QR_SCAN = "qrscan"
    const val EMAIL_CHECK = "emailcheck"
    const val NEARBY_CHAT = "nearbychat"
    const val CHAT_CONVERSATION = "chatconversation"
    const val SETTINGS = "settings"

    fun threatDetail(id: String) = "threat/$id"
}

private val TAB_ROUTES = setOf(Routes.HOME, Routes.NEARBY_CHAT, Routes.RESULTS, Routes.SETTINGS)
private val CHAT_FEATURE_ROUTES = setOf(Routes.NEARBY_CHAT, Routes.CHAT_CONVERSATION)

class MainActivity : ComponentActivity() {

    private val scanViewModel: ScanViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()
    private val bluetoothViewModel: BluetoothChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keeps the launch splash screen visible briefly on every cold start
        // (not just first run) instead of an instant, easy-to-miss flash.
        var appReady = false
        splashScreen.setKeepOnScreenCondition { !appReady }
        lifecycleScope.launch {
            delay(500)
            appReady = true
        }

        setContent {
            ThreatProtectionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route

                    // Nearby Chat's Bluetooth resources (server socket, discovery
                    // receiver) are only held while either chat screen is on
                    // screen — released the moment the user leaves both, so
                    // discovery never keeps running in the background.
                    LaunchedEffect(currentRoute) {
                        if (currentRoute in CHAT_FEATURE_ROUTES) {
                            bluetoothViewModel.onScreenEntered()
                        } else {
                            bluetoothViewModel.onScreenLeft()
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f)) {
                            NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
                                composable(Routes.ONBOARDING) {
                                    OnboardingScreen(
                                        onGetStarted = { navController.navigate(Routes.SIGNIN) },
                                        onSignIn = { navController.navigate(Routes.SIGNIN) },
                                    )
                                }
                                composable(Routes.SIGNIN) {
                                    SignInScreen(
                                        authViewModel = authViewModel,
                                        onContinue = {
                                            navController.navigate(Routes.HOME) {
                                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                                            }
                                        },
                                    )
                                }
                                composable(Routes.HOME) {
                                    val authState by authViewModel.state.collectAsStateWithLifecycle()
                                    val name = (authState as? com.intelram.shield.auth.AuthUiState.SignedIn)
                                        ?.displayName?.substringBefore(" ") ?: "there"
                                    HomeScreen(
                                        scanViewModel = scanViewModel,
                                        greetingName = name,
                                        onScanNow = {
                                            scanViewModel.startScan()
                                            navController.navigate(Routes.SCANNING)
                                        },
                                        onOpenQrScanner = { navController.navigate(Routes.QR_SCAN) },
                                        onOpenEmailCheck = { navController.navigate(Routes.EMAIL_CHECK) },
                                    )
                                }
                                composable(Routes.SCANNING) {
                                    val uiState by scanViewModel.uiState.collectAsStateWithLifecycle()

                                    LaunchedEffect(Unit) {
                                        if (uiState == ScanUiState.Idle) scanViewModel.startScan()
                                    }
                                    LaunchedEffect(uiState) {
                                        if (uiState is ScanUiState.Done) {
                                            navController.navigate(Routes.RESULTS) {
                                                popUpTo(Routes.SCANNING) { inclusive = true }
                                            }
                                        }
                                    }

                                    val state = uiState
                                    ScanningScreen(
                                        progress = (state as? ScanUiState.Scanning)?.progress ?: 0,
                                        stepLabel = (state as? ScanUiState.Scanning)?.stepLabel ?: "Starting scan…",
                                        onCancel = { navController.popBackStack() },
                                    )
                                }
                                composable(Routes.RESULTS) {
                                    ResultsScreen(
                                        scanViewModel = scanViewModel,
                                        onBack = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                                        onRescan = {
                                            scanViewModel.startScan()
                                            navController.navigate(Routes.SCANNING)
                                        },
                                        onOpenFinding = { id -> navController.navigate(Routes.threatDetail(id)) },
                                    )
                                }
                                composable(
                                    route = Routes.THREAT_DETAIL,
                                    arguments = listOf(navArgument("findingId") { type = NavType.StringType }),
                                ) { backEntry ->
                                    val id = backEntry.arguments?.getString("findingId").orEmpty()
                                    ThreatDetailScreen(
                                        finding = scanViewModel.findFinding(id),
                                        onBack = { navController.popBackStack() },
                                        onDismissFinding = scanViewModel::dismissFinding,
                                    )
                                }
                                composable(Routes.QR_SCAN) {
                                    QrScanScreen()
                                }
                                composable(Routes.EMAIL_CHECK) {
                                    EmailLinkCheckScreen()
                                }
                                composable(Routes.NEARBY_CHAT) {
                                    NearbyChatScreen(
                                        viewModel = bluetoothViewModel,
                                        onOpenConversation = { navController.navigate(Routes.CHAT_CONVERSATION) },
                                    )
                                }
                                composable(Routes.CHAT_CONVERSATION) {
                                    ChatConversationScreen(
                                        viewModel = bluetoothViewModel,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable(Routes.SETTINGS) {
                                    SettingsScreen(
                                        scanViewModel = scanViewModel,
                                        authViewModel = authViewModel,
                                        onSignOut = {
                                            authViewModel.signOut()
                                            navController.navigate(Routes.ONBOARDING) {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        },
                                        onOpenEmailCheck = { navController.navigate(Routes.EMAIL_CHECK) },
                                    )
                                }
                            }
                        }

                        if (currentRoute in TAB_ROUTES) {
                            val activeTab = when (currentRoute) {
                                Routes.HOME -> NavTab.HOME
                                Routes.NEARBY_CHAT -> NavTab.CHAT
                                Routes.RESULTS -> NavTab.ALERTS
                                else -> NavTab.SETTINGS
                            }
                            BottomNavBar(current = activeTab) { tab ->
                                when (tab) {
                                    NavTab.HOME -> navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                    NavTab.SCAN -> {
                                        scanViewModel.startScan()
                                        navController.navigate(Routes.SCANNING)
                                    }
                                    NavTab.CHAT -> navController.navigate(Routes.NEARBY_CHAT) {
                                        popUpTo(Routes.HOME) { inclusive = false }
                                    }
                                    NavTab.ALERTS -> {
                                        val done = scanViewModel.uiState.value is ScanUiState.Done
                                        navController.navigate(if (done) Routes.RESULTS else Routes.HOME) {
                                            popUpTo(Routes.HOME) { inclusive = false }
                                        }
                                    }
                                    NavTab.SETTINGS -> navController.navigate(Routes.SETTINGS)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
