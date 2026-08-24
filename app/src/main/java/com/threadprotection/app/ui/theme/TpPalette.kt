package com.threadprotection.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class TpThemeMode { NIGHT, DAY }

/**
 * Full design-token palette from the Thread Protection prototype (README §Design Tokens).
 * Kept as one flat token set (rather than folded into Material3's smaller ColorScheme)
 * so every surface/border/text tier the design calls for stays literally addressable.
 */
@Immutable
data class TpPalette(
    val page: Color,
    val bg: Color,
    val bg2: Color,
    val bg0: Color,
    val card: Color,
    val card2: Color,
    val line: Color,
    val line2: Color,
    val line3: Color,
    val line4: Color,
    val lineHover: Color,
    val fg: Color,
    val fg2: Color,
    val muted: Color,
    val muted2: Color,
    val muted3: Color,
    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
    val danger2: Color,
    val warn: Color,
    val warn2: Color,
    val sheet: Color,
    val sheetFg: Color,
    val mode: TpThemeMode,
) {
    // Frequently used translucent tints (README §Design Tokens, "Accent tints used as fills")
    val accentTint06 get() = accent.copy(alpha = .06f)
    val accentTint07 get() = accent.copy(alpha = .07f)
    val accentTint08 get() = accent.copy(alpha = .08f)
    val accentTint12 get() = accent.copy(alpha = .12f)
    val accentTint14 get() = accent.copy(alpha = .14f)
    val accentTint15 get() = accent.copy(alpha = .15f)
    val accentTint16 get() = accent.copy(alpha = .16f)
    val accentBorder22 get() = accent.copy(alpha = .22f)
    val accentBorder25 get() = accent.copy(alpha = .25f)
    val accentBorder30 get() = accent.copy(alpha = .30f)
    val accentBorder35 get() = accent.copy(alpha = .35f)
    val accentBorder40 get() = accent.copy(alpha = .40f)
    val accentBorder45 get() = accent.copy(alpha = .45f)
    val accentBorder50 get() = accent.copy(alpha = .50f)

    val warnTint06 get() = warn.copy(alpha = .06f)
    val warnTint12 get() = warn.copy(alpha = .12f)
    val warnTint14 get() = warn.copy(alpha = .14f)
    val warnBorder20 get() = warn.copy(alpha = .20f)
    val warnBorder35 get() = warn.copy(alpha = .35f)

    val warn2Tint14 get() = warn2.copy(alpha = .14f)

    val dangerTint08 get() = danger.copy(alpha = .08f)
    val dangerTint12 get() = danger.copy(alpha = .12f)
    val dangerTint14 get() = danger.copy(alpha = .14f)
    val dangerBorder30 get() = danger.copy(alpha = .30f)
    val dangerBorder35 get() = danger.copy(alpha = .35f)

    val mutedTint14 get() = muted.copy(alpha = .14f)

    val alertScrim get() = Color(0xFF040806).copy(alpha = .72f)

    companion object {
        val Night = TpPalette(
            page = Color(0xFF070A09), bg = Color(0xFF0C1210), bg2 = Color(0xFF0F1613), bg0 = Color(0xFF0A0F0D),
            card = Color(0xFF141C18), card2 = Color(0xFF111815),
            line = Color(0xFF1E2A24), line2 = Color(0xFF1A241F), line3 = Color(0xFF2A3A32),
            line4 = Color(0xFF3A4E44), lineHover = Color(0xFF2E4238),
            fg = Color(0xFFE9F2EC), fg2 = Color(0xFFC7D6CC), muted = Color(0xFF8FA79A),
            muted2 = Color(0xFF6E837A), muted3 = Color(0xFF4A5A52),
            accent = Color(0xFF3DDC84), accentHover = Color(0xFF5BE99B), accentSoft = Color(0xFF7CE8AE),
            onAccent = Color(0xFF07130C),
            danger = Color(0xFFFF5C4D), danger2 = Color(0xFFFF8A80),
            warn = Color(0xFFFFB454), warn2 = Color(0xFFFF8A5C),
            sheet = Color(0xFFFFFFFF), sheetFg = Color(0xFF1A1D1B),
            mode = TpThemeMode.NIGHT,
        )
        val Day = TpPalette(
            page = Color(0xFFE4EBE7), bg = Color(0xFFF6FAF7), bg2 = Color(0xFFFFFFFF), bg0 = Color(0xFFE9EFEB),
            card = Color(0xFFFFFFFF), card2 = Color(0xFFF3F8F4),
            line = Color(0xFFDCE5DF), line2 = Color(0xFFE8EEEA), line3 = Color(0xFFC6D4CC),
            line4 = Color(0xFFB4C7BC), lineHover = Color(0xFF8FB8A2),
            fg = Color(0xFF0E1C16), fg2 = Color(0xFF2E4038), muted = Color(0xFF5C7167),
            muted2 = Color(0xFF7A8D84), muted3 = Color(0xFFA6B5AC),
            accent = Color(0xFF0E8F4A), accentHover = Color(0xFF0B7A3F), accentSoft = Color(0xFF2FB56B),
            onAccent = Color(0xFFFFFFFF),
            danger = Color(0xFFC62828), danger2 = Color(0xFFB3261E),
            warn = Color(0xFFA96200), warn2 = Color(0xFFC4551C),
            sheet = Color(0xFFFFFFFF), sheetFg = Color(0xFF1A1D1B),
            mode = TpThemeMode.DAY,
        )
    }
}

/** Severity → (color, tint) per README §Design Tokens "Severity palette". */
enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, FIXED }

fun TpPalette.severityColor(sev: Severity): Color = when (sev) {
    Severity.CRITICAL -> danger
    Severity.HIGH -> warn2
    Severity.MEDIUM -> warn
    Severity.LOW -> muted
    Severity.FIXED -> accent
}

fun TpPalette.severityTint(sev: Severity): Color = when (sev) {
    Severity.CRITICAL -> dangerTint14
    Severity.HIGH -> warn2Tint14
    Severity.MEDIUM -> warnTint14
    Severity.LOW -> mutedTint14
    Severity.FIXED -> accentTint14
}

val LocalTpPalette = staticCompositionLocalOf { TpPalette.Night }
