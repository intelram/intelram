package com.intelram.shield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.intelram.shield.scan.ScanViewModel
import com.intelram.shield.ui.screens.AppDetailScreen
import com.intelram.shield.ui.screens.DashboardScreen
import com.intelram.shield.ui.theme.IntelRamShieldTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntelRamShieldTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    NavHost(navController = navController, startDestination = "dashboard") {
                        composable("dashboard") {
                            DashboardScreen(
                                state = uiState,
                                onScan = viewModel::startScan,
                                onAppClick = { app ->
                                    val encoded = URLEncoder.encode(app.packageName, "UTF-8")
                                    navController.navigate("app/$encoded")
                                },
                            )
                        }
                        composable(
                            route = "app/{packageName}",
                            arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encoded = backStackEntry.arguments?.getString("packageName").orEmpty()
                            val packageName = URLDecoder.decode(encoded, "UTF-8")
                            val app = viewModel.findApp(packageName)
                            if (app != null) {
                                AppDetailScreen(app = app, onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }
}
