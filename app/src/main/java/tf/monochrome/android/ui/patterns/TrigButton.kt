// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One step button.
 *
 * The look is a physical trig key rather than a checkbox: a recessed well with
 * a raised cap, dark and nearly flat when off, lit from inside when on. That
 * is the whole reason this is a `Canvas` and not a `Surface` — the illusion
 * depends on the inner shadow and the rim highlight moving together as the
 * state changes, and on the lit state bleeding a little glow past its own
 * edge, none of which a stack of composable backgrounds does convincingly.
 *
 * Every visual state is a continuous value, not a branch:
 *
 * | state          | what it drives                                  |
 * |----------------|-------------------------------------------------|
 * | on / off       | fill brightness and the rim's opacity           |
 * | playhead       | a hard white rim, brightest thing on the grid   |
 * | selected       | an outer ring, kept outside the cap             |
 * | muted          | desaturates the whole button toward the surface |
 * | locked         | a dot in the corner                             |
 * | accent (beat)  | a lighter well, so the 4/4 grid reads at a glance |
 *
 * Animating them means a step that turns on under the playhead does not fight
 * itself, and a step being painted by a drag settles rather than snapping.
 */
@Composable
fun TrigButton(
    stepNumber: Int,
    enabled: Boolean,
    isPlayhead: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    muted: Boolean = false,
    locked: Boolean = false,
    beatAccent: Boolean = false,
    velocity: Int = 100,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val pressed by interactionSource.collectIsPressedAsState()

    // Fast on the way up, slower on the way down: a trig should feel like it
    // lights the instant it is hit and decays afterwards, which is also how
    // the playhead reads as movement rather than as a jumping highlight.
    val lit by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(durationMillis = if (enabled) 60 else 160),
        label = "trigLit",
    )
    val playhead by animateFloatAsState(
        targetValue = if (isPlayhead) 1f else 0f,
        animationSpec = tween(durationMillis = if (isPlayhead) 30 else 110),
        label = "trigPlayhead",
    )
    val depress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 1400f),
        label = "trigPress",
    )

    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    // Velocity scales brightness, so a soft hit reads as a dimmer key. Floored
    // well above zero — a quiet step must still be obviously *on*.
    val velocityScale = 0.55f + 0.45f * (velocity.coerceIn(1, 127) / 127f)
    val effectiveAccent = if (muted) lerp(accent, surface, 0.72f) else accent

    val description = buildString {
        append("Step $stepNumber")
        if (locked) append(", parameter locked")
    }
    val state = when {
        muted -> "muted"
        enabled -> "on"
        else -> "off"
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MIN_TOUCH_DP.dp, minHeight = MIN_TOUCH_DP.dp)
            .aspectRatio(1f)
            .padding(2.dp)
            .semantics {
                role = Role.Switch
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = CornerRadius(size.minDimension * 0.26f)
            val inset = size.minDimension * (0.06f + 0.03f * depress)
            val capSize = Size(size.width - inset * 2, size.height - inset * 2)
            val capOffset = Offset(inset, inset)

            // ── the well ────────────────────────────────────────────────
            // Slightly lighter on beats so the 4/4 grid is legible without a
            // label on every fourth key.
            drawRoundRect(
                color = lerp(surface, Color.Black, if (beatAccent) 0.18f else 0.34f),
                cornerRadius = radius,
            )

            // ── the cap ─────────────────────────────────────────────────
            val capBase = lerp(
                lerp(surface, Color.White, 0.05f),
                effectiveAccent,
                lit * velocityScale,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    // Top-lit: brighter at the top edge, falling away below.
                    // Reversing this is the single change that makes the key
                    // read as a hole instead of a button.
                    colors = listOf(
                        lerp(capBase, Color.White, 0.10f + 0.22f * lit),
                        lerp(capBase, Color.Black, 0.16f + 0.10f * depress),
                    ),
                    startY = capOffset.y,
                    endY = capOffset.y + capSize.height,
                ),
                topLeft = capOffset,
                size = capSize,
                cornerRadius = radius,
            )

            // ── inner glow ──────────────────────────────────────────────
            if (lit > 0.01f) {
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            effectiveAccent.copy(alpha = 0.55f * lit * velocityScale),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = size.minDimension * 0.62f,
                    ),
                    topLeft = capOffset,
                    size = capSize,
                    cornerRadius = radius,
                )
            }

            // ── specular rim ────────────────────────────────────────────
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f + 0.16f * lit),
                topLeft = capOffset,
                size = capSize,
                cornerRadius = radius,
                style = Stroke(width = size.minDimension * 0.035f),
            )

            // ── playhead ────────────────────────────────────────────────
            // Drawn last and in plain white: it has to win against a lit key
            // in any accent colour, and anything tinted loses that fight on
            // at least one theme.
            if (playhead > 0.01f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.92f * playhead),
                    topLeft = capOffset,
                    size = capSize,
                    cornerRadius = radius,
                    style = Stroke(width = size.minDimension * 0.085f),
                )
            }

            // ── selection ring ──────────────────────────────────────────
            // Outside the cap, in the well, so it never reads as a lit step.
            if (selected) {
                drawRoundRect(
                    color = onSurface.copy(alpha = 0.55f),
                    cornerRadius = radius,
                    style = Stroke(width = size.minDimension * 0.05f),
                )
            }

            // ── parameter lock marker ───────────────────────────────────
            if (locked) {
                drawCircle(
                    color = outline.copy(alpha = 0.95f),
                    radius = size.minDimension * 0.065f,
                    center = Offset(size.width * 0.80f, size.height * 0.20f),
                )
            }
        }

        // The number is a quiet aid, not a label: it fades out as the key
        // lights so the illumination is what the eye tracks.
        Text(
            text = "%02d".format(stepNumber),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = onSurface.copy(alpha = 0.34f - 0.22f * lit),
        )
    }
}

/** Below this the key stops being reliably hittable with a thumb. */
const val MIN_TOUCH_DP = 44
