package com.threadprotection.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.threadprotection.app.R

/** Space Grotesk, bundled from Google Fonts (OFL) — README §Typography. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/** Named text styles matching README §Typography's role table (sizes deliberately large, not scaled down). */
object TpType {
    val scoreNumber = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 62.sp, lineHeight = 62.sp)
    val signinAppName = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp)
    val bigCounter = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp)
    val bigCounterLg = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp)
    val screenTitle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 23.sp)
    val screenTitleLg = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 24.5.sp)
    val dashboardHeader = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 21.sp)
    val primaryButton = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.5.sp)
    val primaryButtonLg = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
    val cardTitle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.5.sp)
    val cardTitleBold = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val body = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
    val caption = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val sectionHeading = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, letterSpacing = 1.45.sp)
    val badge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, letterSpacing = 0.7.sp)
    val badgeSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp)
}

val TpMaterialTypography = Typography(
    bodyLarge = TpType.body,
    bodyMedium = TpType.caption,
    titleLarge = TpType.screenTitle,
    labelLarge = TpType.primaryButton,
)
