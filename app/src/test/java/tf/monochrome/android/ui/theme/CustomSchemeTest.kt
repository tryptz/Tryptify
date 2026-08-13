package tf.monochrome.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom scheme, checked against WCAG AA on adversarial input.
 *
 * The presets are fifteen pairs someone designed. This one is built from
 * whatever two colours the listener pokes into a picker — including a black
 * accent on a black ground, or a yellow accent on white — so it cannot lean on
 * anything being sensible. What holds it together is the contrast floor: every
 * foreground is pushed toward the readable ink until it passes on the surface it
 * sits on. This sweeps a grid of pairs, plus the genuinely hostile cases, and
 * asserts the floor was actually reached.
 */
class CustomSchemeTest {

    private val aa = 4.5
    private val aaNonText = 3.0

    /**
     * A spread of hues at a few brightnesses, plus black and white.
     *
     * Literal ARGB rather than built from `android.graphics.Color.HSVToColor`,
     * which is a stubbed no-op under the plain-JVM test runtime — the whole
     * reason [customScheme] is written against `androidx…Color` and pure maths.
     */
    private val samples = listOf(
        0xFF000000, 0xFFFFFFFF,
        // deep / mid / bright, across the wheel
        0xFF3A0D0D, 0xFFB23030, 0xFFF08A8A, // red
        0xFF3A2A0D, 0xFFB27A2A, 0xFFF0C87A, // amber
        0xFF163A0D, 0xFF4CB230, 0xFFA6F07A, // green
        0xFF0D3A38, 0xFF2AB2A8, 0xFF7AF0E6, // teal
        0xFF0D1E3A, 0xFF3060B2, 0xFF7AA6F0, // blue
        0xFF2A0D3A, 0xFF7A30B2, 0xFFC87AF0, // purple
        0xFF3A0D2A, 0xFFB2307A, 0xFFF07AC8, // magenta
        0xFF1A1A1E, 0xFF808088, 0xFFE0E0E6, // neutral greys
    ).map { Color(it) }

    private fun pairs(block: (accent: Color, background: Color) -> Unit) {
        for (accent in samples) {
            for (background in samples) {
                block(accent, background)
            }
        }
    }

    @Test
    fun `text is readable on every surface for any pair of colors`() {
        pairs { accent, background ->
            val s = customScheme(accent, background)
            val tag = "accent=${accent.hex()} bg=${background.hex()}"

            fun check(label: String, fg: Color, bg: Color, min: Double) {
                val ratio = contrastRatio(fg, bg)
                assertTrue(
                    "$tag $label is ${"%.2f".format(ratio)}:1 (need $min)",
                    ratio >= min - 0.01,
                )
            }
            check("onBackground", s.onBackground, s.background, aa)
            check("onSurface", s.onSurface, s.surface, aa)
            check("onSurfaceVariant", s.onSurfaceVariant, s.surfaceVariant, aa)
            check("onPrimary", s.onPrimary, s.primary, aa)
            check("onSecondary", s.onSecondary, s.secondary, aa)
            check("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer, aa)
            check("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer, aa)
            check("onError", s.onError, s.error, aa)
            check("outline", s.outline, s.surface, 1.4)
        }
    }

    /**
     * The background the listener picked is the background they get. Legibility
     * is bought by moving the *foregrounds*, never by quietly overriding the one
     * colour the whole choice was about.
     */
    @Test
    fun `the chosen background is used verbatim`() {
        pairs { accent, background ->
            val s = customScheme(accent, background)
            assertTrue(
                "background drifted: asked ${background.hex()}, got ${s.background.hex()}",
                background.hex() == s.background.hex(),
            )
        }
    }

    /**
     * The ground decides dark vs light, and the surfaces have to sit on the
     * correct side of it — a card must be lighter than a dark page and darker
     * than a light one, or the layering inverts and everything looks wrong.
     */
    @Test
    fun `surfaces are lifted the right way off the ground`() {
        pairs { accent, background ->
            val s = customScheme(accent, background)
            val bg = relativeLuminance(background)
            val surface = relativeLuminance(s.surface)
            // Skip mid-tones where "lighter" is ambiguous; the extremes are
            // where an inverted layer would actually be visible.
            if (bg < 0.05) {
                assertTrue("dark ground but surface not lifted", surface >= bg)
            } else if (bg > 0.8) {
                assertTrue("light ground but surface not darkened", surface <= bg)
            }
        }
    }

    private fun Color.hex(): String =
        "#" + (
            (red * 255).toInt() shl 16 or
                ((green * 255).toInt() shl 8) or
                (blue * 255).toInt()
            ).toString(16).padStart(6, '0')
}
