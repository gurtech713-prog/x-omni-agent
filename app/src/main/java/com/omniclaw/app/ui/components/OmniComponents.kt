package com.omniclaw.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.core.theme.pulseAlpha

/**
 * Pure black & white minimalistic shared components.
 * Enhanced with subtle press feedback, fade-in animations, and better typography.
 */

@Composable
fun OmniSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        fontFamily = OmniMono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 18.dp),
    )
}

@Composable
fun OmniDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun OmniTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            actions()
        }
    }
}


@Composable
fun OmniButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressModifier = if (enabled) Modifier.alpha(if (pressed) 0.65f else 1.0f) else Modifier.alpha(0.4f)
    // Enforce the 48dp Material minimum touch target so buttons are tappable
    // and meet accessibility guidelines. Previously the content padding
    // (18dp/13dp) + 11sp text produced a ~40dp button — below the minimum.
    val minHeightModifier = Modifier.heightIn(min = 48.dp)

    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = modifier.then(pressModifier).then(minHeightModifier),
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.background,
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontFamily = OmniMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = modifier.then(pressModifier).then(minHeightModifier),
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            border = BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontFamily = OmniMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}

/**
 * Enhanced badge — when [filled] is true and [pulsing] is true, the badge
 * pulses (for LIVE / REC indicators).
 */
@Composable
fun OmniBadge(text: String, modifier: Modifier = Modifier, filled: Boolean = true, pulsing: Boolean = false) {
    val baseModifier = if (pulsing) modifier.pulseAlpha(minAlpha = 0.35f) else modifier
    Surface(
        modifier = baseModifier,
        shape = RoundedCornerShape(0.dp),
        color = if (filled) MaterialTheme.colorScheme.onBackground else Color.Transparent,
        contentColor = if (filled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onBackground),
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = OmniMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun OmniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            label.uppercase(),
            fontFamily = OmniMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.0.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            value,
            fontFamily = OmniMono,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun OmniRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(14.dp))
                trailing()
            }
        }
    }
    if (onClick == null) {
        Column(modifier = modifier) { row() }
    } else {
        Surface(onClick = onClick, modifier = modifier, color = MaterialTheme.colorScheme.background) { row() }
    }
}


/**
 * Empty-state placeholder — large monospace label + optional subtitle.
 * Consistent across all screens.
 */
@Composable
fun OmniEmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title.uppercase(),
            fontFamily = OmniMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Spacer(Modifier.size(12.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
