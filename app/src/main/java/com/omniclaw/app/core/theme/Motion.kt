package com.omniclaw.app.core.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion design tokens — keep animations subtle and minimal to match the
 * pure B&W aesthetic. No bouncy springs; just tasteful easing.
 */
object Motion {
    /** Standard duration for state transitions (fade, color change). */
    const val DurationFast = 150
    const val DurationMedium = 300
    const val DurationSlow = 500

    /** Material standard easings. */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val StandardEasing = FastOutSlowInEasing
    val DecelerateEasing = LinearOutSlowInEasing

    /** Live-pulse cycle — for the LIVE badge and recording indicator. */
    const val PulseDurationMs = 1200
}

/**
 * Pulsing alpha modifier — fades between [minAlpha] and 1.0 indefinitely.
 * Used for LIVE badges, REC indicators, and "agent thinking" dots.
 */
@Composable
fun Modifier.pulseAlpha(
    minAlpha: Float = 0.3f,
    durationMs: Int = Motion.PulseDurationMs,
): Modifier {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = minAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = Motion.StandardEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    return this.alpha(alpha)
}

/**
 * Animated alpha that smoothly transitions when [visible] toggles.
 */
@Composable
fun Modifier.fadeVisibility(visible: Boolean, durationMs: Int = Motion.DurationMedium): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMs, easing = Motion.StandardEasing),
        label = "fadeVisibility",
    )
    return this.alpha(alpha)
}

/**
 * Bottom-border underline that animates its width — for text field focus.
 */
@Composable
fun Modifier.animatedUnderline(
    focused: Boolean,
    color: Color,
    thickness: Dp = 1.dp,
    durationMs: Int = Motion.DurationFast,
): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMs, easing = Motion.StandardEasing),
        label = "underline",
    )
    return this.drawWithContent {
        drawContent()
        if (progress > 0f) {
            val width = size.width * progress
            val startX = (size.width - width) / 2f
            drawLine(
                color = color,
                start = Offset(startX, size.height),
                end = Offset(startX + width, size.height),
                strokeWidth = thickness.toPx(),
            )
        }
    }
}
