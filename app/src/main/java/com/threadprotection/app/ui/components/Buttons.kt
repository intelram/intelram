package com.threadprotection.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpShapes
import com.threadprotection.app.ui.theme.TpType

/** Full-width accent pill button — README primary buttons (56–64px tall, 17–19px/700). */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalTpPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "pressScale")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .scale(scale)
            .clip(TpShapes.pill)
            .background(if (pressed) palette.accentHover else palette.accent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = TpType.primaryButtonLg, color = palette.onAccent, textAlign = TextAlign.Center)
    }
}

/** Outlined pill button — secondary actions ("Use without an account", "Cancel scan", etc). */
@Composable
fun OutlinedPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    textColor: Color? = null,
) {
    val palette = LocalTpPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(TpShapes.pill)
            .border(BorderStroke(1.5.dp, borderColor ?: palette.line4), TpShapes.pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TpType.primaryButton,
            color = textColor ?: palette.fg2,
            textAlign = TextAlign.Center,
        )
    }
}

/** Muted text-only pill action ("Ignore for now"). */
@Composable
fun SubtlePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(TpShapes.pill)
            .border(BorderStroke(1.dp, palette.line3), TpShapes.pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = TpType.cardTitle, color = palette.muted, textAlign = TextAlign.Center)
    }
}

/** Solid white "Sign in with Google" pill (README §Sign in / §Settings). */
@Composable
fun WhiteGoogleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(TpShapes.pill)
            .background(Color(0xFFFFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
