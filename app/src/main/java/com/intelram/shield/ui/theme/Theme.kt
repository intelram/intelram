package com.intelram.shield.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColors = lightColorScheme(
    primary = Green,
    onPrimary = Surface,
    primaryContainer = GreenSoft,
    onPrimaryContainer = GreenDark,
    secondary = GreenDark,
    background = Bg,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = InkSoft,
    outline = Border,
    error = Red,
    errorContainer = RedSoft,
)

@Composable
fun ThreatProtectionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content,
    )
}
