// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPalette

/**
 * StepTech: the mode's own surface language.
 *
 * Gameplay is deliberately flatter than the rest of Tryptify. The player
 * interface is glass over album art because it is a thing to look at; a
 * playfield is a thing to read at 165 Hz while moving, and every gram of blur,
 * bevel and translucency behind it costs legibility and frame time for nothing.
 * So: near-black grounds, flat panels, one accent per meaning, and the pack's
 * own colours doing the talking.
 *
 * Glass is not banned — menus and overlays may use it, and where they do they
 * take the app's existing material and every invariant that comes with it. The
 * rule is that the *playfield* does not.
 */
object GlyphTheme {

    /** The 8 px grid the pack is drawn on. Spacing is a multiple of it. */
    val Grid = 8.dp

    val ScreenPadding = 16.dp
    val PanelCorner = 12.dp
    val PanelBorder = 1.dp

    /** Ink, from the pack. Everything sits on this or a shade of it. */
    val Ink = Color(0xFF0B1020)
    val InkRaised = Color(0xFF141A2E)
    val InkPanel = Color(0xFF10162A)
    val Paper = Color(0xFFF8FAFF)
    val Muted = Color(0xFF78839C)
    val Hairline = Color(0x1AF8FAFF)

    /** Feedback accents that are not the pack's own artwork. */
    val Positive = Color(0xFF63F2A2)
    val Warning = Color(0xFFFFD95A)
    val Negative = Color(0xFFFF5F6D)
    val Early = Color(0xFF58D9FF)
    val Late = Color(0xFFFF9659)

    /**
     * Lane accents, used only for lane *chrome* — never as the sole way to tell
     * one lane from another. Direction is carried by the arrow's shape, and
     * every lane control also carries its name.
     */
    fun laneAccent(lane: GlyphLane): Color = when (lane) {
        GlyphLane.LEFT -> Color(0xFFFF74C8)
        GlyphLane.DOWN -> Color(0xFF58D9FF)
        GlyphLane.UP -> Color(0xFF63F2A2)
        GlyphLane.RIGHT -> Color(0xFFFFD95A)
    }

    /** The beat palette, from the pack's own manifest when it could be read. */
    fun beatColor(division: GlyphBeatDivision, palette: GlyphPalette?): Color {
        val hex = palette?.beatColors?.get(division.paletteKey)
        return hex?.let(::parseHex) ?: fallbackBeatColor(division)
    }

    private fun fallbackBeatColor(division: GlyphBeatDivision): Color = when (division) {
        GlyphBeatDivision.QUARTER -> Color(0xFFFF5F6D)
        GlyphBeatDivision.EIGHTH -> Color(0xFF58D9FF)
        GlyphBeatDivision.TWELFTH -> Color(0xFFA77BFF)
        GlyphBeatDivision.SIXTEENTH -> Color(0xFFFFD95A)
        GlyphBeatDivision.TWENTY_FOURTH -> Color(0xFFFF74C8)
        GlyphBeatDivision.THIRTY_SECOND -> Color(0xFFFF9659)
        GlyphBeatDivision.FORTY_EIGHTH -> Color(0xFF52E6D8)
        GlyphBeatDivision.SIXTY_FOURTH -> Color(0xFF63F2A2)
    }

    fun parseHex(value: String): Color? = runCatching {
        val digits = value.removePrefix("#")
        Color((0xFF000000L or digits.toLong(16)).toInt())
    }.getOrNull()
}

/**
 * JetBrains Mono, the mode's provisional interface face.
 *
 * Monospaced on purpose rather than for looks: a score, a combo and a
 * millisecond offset all change every frame, and in a proportional face each
 * change re-flows the number and the eye has to re-find it. Fixed advance
 * widths mean a digit changing in place stays in place.
 *
 * The font already ships with the app for the general typeface picker, so this
 * costs nothing extra in the APK. Loaded once per composition tree and
 * remembered; a failure falls back to the platform monospace rather than
 * refusing to draw.
 */
@Composable
fun rememberStepTechFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            FontFamily(
                Font(FONT_ASSET, context.assets, FontWeight.Normal),
                Font(FONT_ASSET, context.assets, FontWeight.Medium),
                Font(FONT_ASSET, context.assets, FontWeight.SemiBold),
                Font(FONT_ASSET, context.assets, FontWeight.Bold),
            )
        }.getOrDefault(FontFamily.Monospace)
    }
}

private const val FONT_ASSET = "fonts/jetbrains_mono.ttf"

/** The mode's text styles. Tight, technical, and few. */
class GlyphTypography(family: FontFamily) {
    val readout = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, letterSpacing = (-0.5).sp,
    )
    val title = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, letterSpacing = 0.sp,
    )
    val body = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = 0.sp,
    )
    val label = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 0.8.sp,
    )
    val mono = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, letterSpacing = 0.sp,
    )
}

@Composable
@ReadOnlyComposable
fun glyphTypography(family: FontFamily): GlyphTypography = GlyphTypography(family)
