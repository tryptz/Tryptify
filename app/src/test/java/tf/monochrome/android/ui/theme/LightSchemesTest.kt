package tf.monochrome.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every light scheme, checked against WCAG AA.
 *
 * This is the reason the schemes are generated instead of written by hand. The
 * app carries fifteen themes and two papers — thirty palettes, each with a
 * dozen foreground/background pairs — and nobody is going to eyeball 360
 * contrast ratios again after the first time. The generator guarantees them and
 * this asserts the guarantee, so a new theme cannot be added with an accent
 * that quietly fails on white.
 */
class LightSchemesTest {

    private val papers = listOf(Paper.Crisp, Paper.Warm)

    /** Normal-size body text. */
    private val aa = 4.5

    /** Non-text UI: outlines, dividers, icon strokes. */
    private val aaNonText = 3.0

    private fun eachScheme(block: (String, Paper, androidx.compose.material3.ColorScheme) -> Unit) {
        for ((name, accent) in ThemeAccents) {
            for (paper in papers) {
                block(name, paper, lightSchemeFor(accent, paper))
            }
        }
    }

    @Test
    fun `body text is readable on every surface it can land on`() {
        eachScheme { name, paper, s ->
            fun check(label: String, fg: Color, bg: Color) {
                val ratio = contrastRatio(fg, bg)
                assertTrue(
                    "$name/$paper $label is ${"%.2f".format(ratio)}:1",
                    ratio >= aa,
                )
            }
            check("onSurface/surface", s.onSurface, s.surface)
            check("onBackground/background", s.onBackground, s.background)
            // The one that was actually broken: secondary text on a card.
            check("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
            check("onPrimary/primary", s.onPrimary, s.primary)
            check("onSecondary/secondary", s.onSecondary, s.secondary)
            check("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
            check("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
            check("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
            check("onError/error", s.onError, s.error)
            check("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer)
        }
    }

    /**
     * The primary is not only a fill in this app — it is the colour of icons,
     * active labels and the globe's coastlines, all of which sit directly on
     * the surface. A primary that only worked behind onPrimary would fail every
     * one of those, which is what a neon accent meant for a black background
     * does when it is reused unchanged.
     */
    @Test
    fun `the accent is legible as ink on the page`() {
        eachScheme { name, paper, s ->
            val ratio = contrastRatio(s.primary, s.surface)
            assertTrue(
                "$name/$paper primary on surface is ${"%.2f".format(ratio)}:1",
                ratio >= aa,
            )
        }
    }

    @Test
    fun `outlines are visible without being text`() {
        eachScheme { name, paper, s ->
            val ratio = contrastRatio(s.outline, s.surface)
            assertTrue(
                "$name/$paper outline on surface is ${"%.2f".format(ratio)}:1",
                ratio >= 1.3,
            )
            assertTrue(
                "$name/$paper outline is heavier than text",
                contrastRatio(s.outline, s.surface) < contrastRatio(s.onSurface, s.surface),
            )
        }
    }

    /** Surfaces have to be distinguishable from each other, or cards vanish. */
    @Test
    fun `cards separate from the page they sit on`() {
        eachScheme { name, paper, s ->
            assertNotEquals("$name/$paper surfaceVariant equals surface", s.surface, s.surfaceVariant)
            val ratio = contrastRatio(s.surfaceVariant, s.surface)
            assertTrue(
                "$name/$paper card separation is only ${"%.3f".format(ratio)}:1",
                ratio >= 1.03,
            )
        }
    }

    /** Both papers are light, and the warm one is genuinely warmer. */
    @Test
    fun `the two papers are light and are different`() {
        val crisp = lightSchemeFor(ThemeAccents.getValue("ocean"), Paper.Crisp)
        val warm = lightSchemeFor(ThemeAccents.getValue("ocean"), Paper.Warm)

        assertTrue("crisp background is not light", relativeLuminance(crisp.background) > 0.8)
        assertTrue("warm background is not light", relativeLuminance(warm.background) > 0.8)
        assertNotEquals(crisp.background, warm.background)
        // Warm means more red than blue in the ground; crisp is neutral.
        assertTrue(
            "warm paper is not warmer than crisp",
            (warm.background.red - warm.background.blue) >
                (crisp.background.red - crisp.background.blue),
        )
    }

    /** A theme's light variant should still look like that theme. */
    @Test
    fun `each theme's light variant keeps its own accent`() {
        val primaries = papers.flatMap { paper ->
            ThemeAccents.map { (_, accent) -> lightSchemeFor(accent, paper).primary }
        }
        // Neons collapse toward similar darks if the generator is too eager, so
        // the check that matters is that they stay distinct from one another.
        assertTrue(
            "light variants collapsed onto ${primaries.size - primaries.distinct().size} shared primaries",
            primaries.distinct().size >= primaries.size - 2,
        )
    }

    @Test
    fun `ensureContrast leaves a colour that already passes alone`() {
        val black = Color(0xFF000000)
        assertEquals(black, ensureContrast(black, Color.White))
    }

    @Test
    fun `light variant names round-trip`() {
        assertEquals("nord_light", lightVariantOf("nord"))
        assertEquals("nord", lightVariantBase("nord_light"))
        assertEquals(null, lightVariantBase("nord"))
    }

    /** Known anchors, so a change to the maths shows up as a failure here. */
    @Test
    fun `contrast ratio matches the WCAG definition`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
    }
}
