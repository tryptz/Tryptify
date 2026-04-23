package tf.monochrome.android.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Applies a liquid glass (glassmorphism) effect to a composable.
 *
 * Rendering tiers (following Android Liquid Glass best practices):
 * - API 31+ with [hazeState]: Real backdrop blur via Haze + translucent tint + specular rim.
 * - API 31+ without [hazeState]: Translucent tint fill + specular rim (no blur overhead).
 * - API < 31: Gradient fallback + specular rim.
 */
fun Modifier.liquidGlass(
    hazeState: HazeState? = null,
    shape: Shape = MonoDimens.shapeMd,
    tintAlpha: Float = MonoDimens.glassAlpha,
    borderAlpha: Float = MonoDimens.glassBorderAlpha,
    borderWidth: Dp = MonoDimens.glassBorderWidth,
    blurRadius: Dp = MonoDimens.glassBlurRadius,
    showRefraction: Boolean = true
) = composed {
    val profile = LocalPerformanceProfile.current
    val isDark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val tintColor = if (isDark) Color.Black else Color.White
    val adaptedTintAlpha = if (isDark) (tintAlpha * 1.4f).coerceAtMost(0.50f) else tintAlpha
    val borderColor = MaterialTheme.colorScheme.outline

    // LOW-tier devices skip the full glass chrome — no backdrop blur, no specular
    // rim, no refraction overlay. Those three layers together are the dominant
    // per-row GPU cost on lazy lists, and blur in particular is expensive on
    // budget SoCs. Return the incoming modifier unchanged so the caller's layout
    // still respects shape via their own background/clip if any.
    if (!profile.allowHazeBlur) return@composed this

    // Specular rim — thin gradient border simulating reflected light on glass edges.
    // Cache per-(theme, alpha) so we don't allocate a fresh Brush per recomposition,
    // which is the dominant cost when this modifier is applied to LazyColumn rows.
    val luminousBorderBrush = remember(borderColor, borderAlpha) {
        Brush.linearGradient(
            colors = listOf(
                borderColor.copy(alpha = borderAlpha * 2f),
                borderColor.copy(alpha = borderAlpha * 0.5f),
                borderColor.copy(alpha = borderAlpha * 1.5f)
            ),
            start = Offset.Zero,
            end = Offset.Infinite
        )
    }

    // Refraction overlay — subtle gradient to enhance glass depth
    val refractionBrush = if (showRefraction) {
        remember(isDark) {
            val refractionColor = if (isDark) Color.White else Color.Black
            Brush.linearGradient(
                colors = listOf(
                    refractionColor.copy(alpha = 0.04f),
                    Color.Transparent,
                    refractionColor.copy(alpha = 0.02f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        }
    } else null

    var modifier = this

    // ── Shape clip (FIRST — ensures all subsequent layers respect the shape) ──
    modifier = modifier.clip(shape)

    // ── Backdrop blur layer ────────────────────────────────────────────
    // When hazeState is provided, apply real backdrop blur via Haze.
    // Must be AFTER clip so the blur is clipped to the rounded shape.
    if (hazeState != null && Build.VERSION.SDK_INT >= 31) {
        modifier = modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = tintColor.copy(alpha = adaptedTintAlpha),
                blurRadius = blurRadius,
                tints = listOf(
                    HazeTint(color = tintColor.copy(alpha = adaptedTintAlpha))
                )
            )
        )
    }

    // ── Tint fill (only when NOT using haze blur) ──────────────────────
    if (hazeState == null || Build.VERSION.SDK_INT < 31) {
        modifier = modifier.background(
            color = tintColor.copy(alpha = adaptedTintAlpha),
            shape = shape
        )
    }

    // ── Specular rim border ────────────────────────────────────────────
    modifier = modifier.border(
        width = borderWidth,
        brush = luminousBorderBrush,
        shape = shape
    )

    // ── Refraction gradient overlay ────────────────────────────────────
    if (refractionBrush != null) {
        modifier = modifier.background(
            brush = refractionBrush,
            shape = shape
        )
    }

    modifier
}
