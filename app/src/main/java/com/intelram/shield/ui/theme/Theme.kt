package com.intelram.shield.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = ShieldGreen,
    onPrimary = ShieldNavy,
    secondary = ShieldGreenDark,
    background = ShieldNavy,
    surface = ShieldSurface,
    onBackground = Color(0xFFEAF2F8),
    onSurface = Color(0xFFEAF2F8),
)

private val LightColors = lightColorScheme(
    primary = ShieldGreenDark,
    onPrimary = Color.White,
    secondary = ShieldGreen,
    background = Color(0xFFF5F8FA),
    surface = Color.White,
)

@Composable
fun IntelRamShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
