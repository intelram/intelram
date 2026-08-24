package com.threadprotection.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Radii from README §Shape & spacing. */
object TpShapes {
    val pill = RoundedCornerShape(999.dp)
    val cardLarge = RoundedCornerShape(20.dp)
    val cardLargeMax = RoundedCornerShape(24.dp)
    val cardMedium = RoundedCornerShape(16.dp)
    val cardSmall = RoundedCornerShape(14.dp)
    val iconSquare = RoundedCornerShape(11.dp)
    val iconSquareLg = RoundedCornerShape(12.dp)
}

object TpSpacing {
    val screenPadding = 20.dp
    val cardPaddingSmall = 14.dp
    val cardPaddingLarge = 22.dp
    val gapSmall = 9.dp
    val gapMedium = 12.dp
    val gapLarge = 18.dp
    val minTapTarget = 52.dp
    val primaryButtonHeight = 60.dp
}
