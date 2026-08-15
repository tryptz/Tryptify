// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The small shared pieces the Patterns screen is built from.
 *
 * They exist as one file because they share a single visual premise: this
 * screen should read as a piece of hardware sitting under Tryptify's glass,
 * not as a form. Concretely that means recessed wells, caps that light rather
 * than fill, and text that is small, wide-tracked and upper-case — the
 * typography of a front panel. Reusing Material's Button and Chip here would
 * be less code and would look like every settings screen in the app, which is
 * exactly what the brief rules out.
 */

/** Panel background for a group of controls. Glass on capable devices. */
@Composable
fun PatternPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(MonoDimens.shapeLg)
            .liquidGlass(shape = MonoDimens.shapeLg)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f))
            .padding(12.dp),
        content = content,
    )
}

/** Front-panel label: small, spaced, quiet. */
@Composable
fun PanelLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A small selectable pill — pattern length, quantize, page, category.
 *
 * Active state is a filled cap rather than an outline: at this size an outline
 * is one pixel of difference and unreadable in a row of eight.
 */
@Composable
fun PatternPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val lit by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(120),
        label = "pillLit",
    )
    val surface = MaterialTheme.colorScheme.surface
    val container = lerp(lerp(surface, Color.Black, 0.22f), accent, lit)
    val content = if (active) {
        if (container.luminance() > 0.5f) Color.Black else Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(34.dp)
            .clip(MonoDimens.shapeSm)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.10f + 0.14f * lit),
                shape = MonoDimens.shapeSm,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (enabled) content else content.copy(alpha = 0.5f),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * A transport key.
 *
 * Deliberately large and deliberately square-ish. These are the three controls
 * someone reaches for without looking while the loop is running, and a 40 dp
 * icon button is not that. [pulsing] drives the record key's breathing glow —
 * the one place on the screen where an animation carries state rather than
 * decorating it.
 */
@Composable
fun TransportKey(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    pulsing: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1500f),
        label = "keyPress",
    )
    val lit by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(140),
        label = "keyLit",
    )
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "recPulse")
        .animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(620),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "recPulseValue",
        )

    val glow = if (pulsing) lit * pulse else lit
    val surface = MaterialTheme.colorScheme.surface
    val base = lerp(lerp(surface, Color.White, 0.06f), accent, glow)
    val content = if (glow > 0.5f) {
        if (base.luminance() > 0.5f) Color.Black else Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .height(56.dp)
            .scale(1f - 0.03f * press)
            .clip(MonoDimens.shapeMd)
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(base, Color.White, 0.10f),
                        lerp(base, Color.Black, 0.20f + 0.10f * press),
                    ),
                ),
            )
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.12f + 0.24f * glow),
                shape = MonoDimens.shapeMd,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) content else content.copy(alpha = 0.4f),
        )
    }
}

/**
 * A labelled horizontal fader.
 *
 * Material's `Slider` would do, and is used elsewhere in the app; here the
 * value has to be readable at a glance while the loop runs, so the label and
 * the reading sit on the same line above a track thin enough not to dominate
 * a stack of six of them.
 */
@Composable
fun PatternFader(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelLabel(label)
            Text(
                text = display,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

/**
 * A momentary pad — tapping it plays the channel and, while recording, writes
 * the hit into the pattern.
 *
 * The flash is driven by the engine's trigger counter rather than by the tap,
 * so a pad also lights when its channel fires from the sequencer. That is what
 * makes the pad row double as a set of activity lamps.
 */
@Composable
fun ChannelPad(
    label: String,
    triggerCount: Int,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    assigned: Boolean = true,
    onTap: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val flash by animateFloatAsState(
        // Keyed on the counter's parity so every increment is a fresh target
        // and the animation actually restarts on consecutive hits.
        targetValue = if (triggerCount % 2 == 0) 0f else 1f,
        animationSpec = tween(90),
        label = "padFlash",
    )
    val surface = MaterialTheme.colorScheme.surface
    val base = lerp(lerp(surface, Color.Black, 0.18f), accent, 0.35f + 0.5f * flash)

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(MonoDimens.shapeSm)
            .background(if (assigned) base else lerp(surface, Color.Black, 0.28f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), MonoDimens.shapeSm)
            .clickable {
                PatternHaptics.padHit(haptics)
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (assigned) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}
