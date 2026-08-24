package com.threadprotection.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun ThreadProtectionTheme(
    mode: TpThemeMode = if (isSystemInDarkTheme()) TpThemeMode.NIGHT else TpThemeMode.DAY,
    content: @Composable () -> Unit,
) {
    val palette = if (mode == TpThemeMode.NIGHT) TpPalette.Night else TpPalette.Day

    val materialScheme = if (mode == TpThemeMode.NIGHT) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bg,
            onBackground = palette.fg,
            surface = palette.card,
            onSurface = palette.fg,
            surfaceVariant = palette.card2,
            onSurfaceVariant = palette.fg2,
            outline = palette.line,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bg,
            onBackground = palette.fg,
            surface = palette.card,
            onSurface = palette.fg,
            surfaceVariant = palette.card2,
            onSurfaceVariant = palette.fg2,
            outline = palette.line,
            error = palette.danger,
        )
    }

    CompositionLocalProvider(LocalTpPalette provides palette) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = TpMaterialTypography,
            content = content,
        )
    }
}
