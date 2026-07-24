package io.nekohasekai.sfa.tarn.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Everything is monospace — that is the whole identity of the design.
 * [FontFamily.Monospace] resolves to the platform mono face, which carries Cyrillic on
 * every supported API level; bundling JetBrains Mono would only tighten the letterforms.
 */
private val Mono = FontFamily.Monospace

/** Small all-caps labels ("СТАТУС", "СЕРВЕР") lean on wide tracking to read as terminal chrome. */
val TarnLabelStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.6.sp,
)

/** The wordmark and screen titles. */
val TarnBrandStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = 1.2.sp,
)

/** Button captions. */
val TarnActionStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 2.sp,
)

/** Endpoint tags, latency, uptime — anything machine-ish. */
val TarnMetaStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp,
)

val TarnTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Light, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Light, fontSize = 36.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Light, fontSize = 30.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = TarnBrandStyle,
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TarnMetaStyle,
    labelLarge = TarnActionStyle,
    labelMedium = TarnLabelStyle,
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
)
