package tf.monochrome.android.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.player.playerFrostTint
import tf.monochrome.android.ui.player.rememberLiquidGlassAvailable
import tf.monochrome.android.ui.theme.contrastRatio
import tf.monochrome.android.ui.theme.glassTint

/**
 * What the glass in this subtree lies over, when that is not the page: the
 * genre galaxy provides deep space. Null means the theme's own background,
 * which is what an ordinary screen's panes frost.
 *
 * The frost washes toward it (dark over dark, light over light) and the text on
 * the glass is chosen against it ([GlassInkScope]). The theme alone cannot
 * say: a light theme's map is still space, and its panes are dark glass.
 */
val LocalGlassGround = compositionLocalOf<Color?> { null }

/**
 * The accent the glass was tinted with before a pane moved it for its own
 * text. A pane inside a pane (a chip on a sheet) tints its slab with this, not
 * with the readable accent its parent handed down, so both stay one material.
 */
internal val LocalGlassAccent = compositionLocalOf<Color?> { null }

/** The text on one pane of glass: the words, the quieter words, and the accent. */
internal data class GlassInk(val text: Color, val muted: Color, val accent: Color)

/**
 * A pane of glass as one colour, as the eye averages it: the frost over the
 * [ground], and the slab's [tint] over that at the share of it the shader
 * lets through ([bodyOpacity] times its body mix). With the shader off it is
 * the plain [fallback] surface instead, at the opacity the fallback draws it.
 */
internal fun glassFace(
    ground: Color,
    frost: Color,
    tint: Color,
    bodyOpacity: Float,
    shaded: Boolean,
    fallback: Color,
): Color {
    val frosted = frost.compositeOver(ground)
    return if (shaded) {
        tint.copy(alpha = (bodyOpacity * BODY_MIX).coerceIn(0f, 1f)).compositeOver(frosted)
    } else {
        fallback.copy(alpha = bodyOpacity.coerceIn(0.55f, 1f)).compositeOver(frosted)
    }
}

/**
 * Light text on dark glass, dark text on light — whichever reads better on
 * [face], the way the themes choose ink per surface. The [accent] keeps its
 * hue and moves toward the text only as far as it must to read on the glass
 * (3:1, the floor for large and bold type, which is what an accent on glass
 * marks): a lime accent on lime glass would otherwise vanish into it.
 */
internal fun glassInk(face: Color, accent: Color): GlassInk {
    // Which way is decided by pure white against pure black, which between
    // them clear AA on any pane (their crossover is at about 4.58); the
    // softened ink is used when it still does, which is almost everywhere.
    val light = contrastRatio(Color.White, face) >= contrastRatio(Color.Black, face)
    val soft = if (light) LIGHT_INK else DARK_INK
    val text = if (contrastRatio(soft, face) >= TEXT_CONTRAST) soft else if (light) Color.White else Color.Black
    val muted = text.copy(alpha = MUTED_ALPHA)
    return GlassInk(text = text, muted = muted, accent = readableAccent(accent, face, text))
}

private fun readableAccent(accent: Color, face: Color, toward: Color): Color {
    val solid = accent.copy(alpha = 1f)
    if (contrastRatio(solid, face) >= ACCENT_CONTRAST) return accent
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (contrastRatio(lerp(solid, toward, mid), face) >= ACCENT_CONTRAST) high = mid else low = mid
    }
    return lerp(solid, toward, high).copy(alpha = accent.alpha)
}

/**
 * Gives [content] text that reads on the glass it sits on: the colour scheme's
 * text slots (onSurface, onSurfaceVariant, onBackground) and the content
 * colour become light on dark glass and dark on light glass, and the accent is
 * made readable on it. Glass is not the page, so the theme's own ink — dark in
 * a light theme — is the wrong answer on a dark pane, and the other way round.
 *
 * Used inside every pane of the shared material ([GlassPanel], [PressableGlass]
 * and so [GlassPill]) and the mini player, so the rule is in one place.
 */
@Composable
internal fun GlassInkScope(glass: PlayerGlassSettings, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = LocalGlassAccent.current ?: scheme.primary
    val ground = LocalGlassGround.current ?: scheme.background
    val tint = glassTint(glass.tintColor)
    val shaded = shaderGlassFor(glass) && rememberLiquidGlassAvailable()
    val frost = playerFrostTint(glass, ground.luminance() <= 0.5f)
    val face = glassFace(ground, frost, tint, glass.bodyOpacity, shaded, scheme.surfaceContainerHigh)
    val ink = remember(face, scheme.primary) { glassInk(face, scheme.primary) }
    val onGlass = remember(scheme, ink) {
        scheme.copy(
            onSurface = ink.text,
            onSurfaceVariant = ink.muted,
            onBackground = ink.text,
            primary = ink.accent,
        )
    }
    // MaterialTheme resets the text style to bodyLarge; whatever the caller
    // had set for this content is put back inside it.
    val style = LocalTextStyle.current
    CompositionLocalProvider(LocalGlassAccent provides accent) {
        MaterialTheme(colorScheme = onGlass, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(
                LocalContentColor provides ink.text,
                LocalTextStyle provides style,
                content = content,
            )
        }
    }
}

/** Near-white and near-black: pure ones glare on glass, and these still clear AA where they are chosen. */
internal val LIGHT_INK = Color(0xFFF5F5F0)
internal val DARK_INK = Color(0xFF15161A)

/** The quieter text: captions, values. */
private const val MUTED_ALPHA = 0.76f

/** How much of the tint the shader's body shows over the backdrop it lenses (its body mix, 0.42..0.72). */
private const val BODY_MIX = 0.6f

private const val ACCENT_CONTRAST = 3.0
private const val TEXT_CONTRAST = 4.5
