package com.omniclaw.app.core.theme

import androidx.compose.ui.graphics.Color

// Pure black & white minimalistic palette.
// The theme schemes in Theme.kt reference Black, White, Muted*, Divider*.
// Additional opacity tokens below give the UI more depth while staying mono.
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)

// Muted text — for subtitles, captions, telemetry
val MutedOnLight = Color(0x99000000)
val MutedOnDark = Color(0x99FFFFFF)

// Dividers — hairline 0.5dp borders
val DividerOnLight = Color(0x1F000000)
val DividerOnDark = Color(0x1FFFFFFF)

// Surface hierarchy — for layered cards (still B&W, just opacity)
val SurfaceRaisedLight = Color(0x0A000000)   // ~4% black overlay on white
val SurfaceRaisedDark = Color(0x0AFFFFFF)    // ~4% white overlay on black
val SurfaceOverlayLight = Color(0x14000000)  // ~8% black
val SurfaceOverlayDark = Color(0x14FFFFFF)   // ~8% white

// Pressed-state overlay — for button press feedback
val PressedLight = Color(0x1F000000)
val PressedDark = Color(0x1FFFFFFF)

// Focus ring — for text fields
val FocusLight = Color(0xFF000000)
val FocusDark = Color(0xFFFFFFFF)

// Status colors — all derived from B&W (no accent colors)
val StatusLivePulse = Color(0xFF000000)  // pulses for LIVE badge
