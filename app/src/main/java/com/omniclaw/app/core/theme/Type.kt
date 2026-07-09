package com.omniclaw.app.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System sans / mono families — keeps the APK small and matches the
// "true black & white minimalistic" aesthetic without bundling custom TTFs.
val OmniSans = FontFamily.SansSerif
val OmniMono = FontFamily.Monospace

/**
 * Enhanced typography — tighter display sizes, wider letter-spacing on
 * captions, and mono labels for telemetry/code-like data.
 */
val OmniTypography = Typography(
    // Display — for hero numbers, splash title
    displayLarge = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Black, fontSize = 36.sp,
        lineHeight = 40.sp, letterSpacing = (-1.0).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Bold, fontSize = 28.sp,
        lineHeight = 32.sp, letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Bold, fontSize = 22.sp,
        lineHeight = 26.sp, letterSpacing = (-0.25).sp,
    ),
    // Headlines — for screen titles
    headlineLarge = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    // Titles — for card headers, section headers
    titleLarge = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // Body — for main content
    bodyLarge = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = OmniSans, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    // Labels — for buttons, badges, chips (mono for tech feel)
    labelLarge = TextStyle(
        fontFamily = OmniMono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        lineHeight = 16.sp, letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = OmniMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        lineHeight = 14.sp, letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = OmniMono, fontWeight = FontWeight.Normal, fontSize = 10.sp,
        lineHeight = 13.sp, letterSpacing = 0.5.sp,
    ),
)
