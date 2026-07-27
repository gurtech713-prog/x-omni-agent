package com.omniclaw.app.core.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Pure black & white color schemes — no accent color, no gradients.
private val LightScheme = lightColorScheme(
    primary = Black,
    onPrimary = White,
    primaryContainer = White,
    onPrimaryContainer = Black,
    secondary = Black,
    onSecondary = White,
    secondaryContainer = White,
    onSecondaryContainer = Black,
    tertiary = Black,
    onTertiary = White,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = White,
    onSurfaceVariant = MutedOnLight,
    surfaceTint = Black,
    inverseSurface = Black,
    inverseOnSurface = White,
    outline = Black,
    outlineVariant = DividerOnLight,
    scrim = Black,
    error = Black,
    onError = White,
    errorContainer = White,
    onErrorContainer = Black,
)

private val DarkScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    primaryContainer = Black,
    onPrimaryContainer = White,
    secondary = White,
    onSecondary = Black,
    secondaryContainer = Black,
    onSecondaryContainer = White,
    tertiary = White,
    onTertiary = Black,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = Black,
    onSurfaceVariant = MutedOnDark,
    surfaceTint = White,
    inverseSurface = White,
    inverseOnSurface = Black,
    outline = White,
    outlineVariant = DividerOnDark,
    scrim = White,
    error = White,
    onError = Black,
    errorContainer = Black,
    onErrorContainer = White,
)

@Composable
fun OmniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // U-L6: removed the deprecated `window.statusBarColor` /
            // `window.navigationBarColor` assignments — these no-op on API 35+
            // and emit deprecation warnings. Edge-to-edge is now configured
            // via WindowCompat.setDecorFitsSystemWindows(window, false) below,
            // and the system bar appearance is controlled via the WindowInsets
            // controller (light/dark icons only).
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = OmniTypography,
        content = content,
    )
}
