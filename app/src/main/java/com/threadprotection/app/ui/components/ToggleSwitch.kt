package com.threadprotection.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.threadprotection.app.ui.theme.LocalTpPalette

/** 62×36 track / 30 knob toggle, 26px travel, 0.2s transition — README §Shape & spacing. */
@Composable
fun ToggleSwitch(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    val trackColor by animateColorAsState(if (checked) palette.accent else palette.line3, tween(200), label = "track")
    val knobColor by animateColorAsState(if (checked) palette.onAccent else palette.muted, tween(200), label = "knob")
    val offset by animateDpAsState(if (checked) 26.dp else 0.dp, tween(200), label = "knobOffset")

    Box(
        modifier = modifier
            .size(width = 62.dp, height = 36.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCheckedChange,
            )
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = offset)
                .size(30.dp)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}
