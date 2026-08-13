package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the globe's outlines are lit.
 *
 * [GlobeFxSettings.clamped] is what a decoded JSON blob passes through, and the
 * values feed a stroke width and an alpha — a persisted glow width of 900 is a
 * halo wider than the Earth, so the pinning is worth asserting rather than
 * trusting.
 */
class GlobeFxSettingsTest {

    @Test
    fun `clamped pins every parameter into its slider range`() {
        val wild = GlobeFxSettings(
            illumination = 12f,
            glowBrightness = -4f,
            glowWidth = 900f,
            landFill = 7f,
        ).clamped()

        assertEquals(1f, wild.illumination, 1e-6f)
        assertEquals(0f, wild.glowBrightness, 1e-6f)
        assertEquals(8f, wild.glowWidth, 1e-6f)
        assertEquals(0.8f, wild.landFill, 1e-6f)
        assertEquals(0f, GlobeFxSettings(landFill = -3f).clamped().landFill, 1e-6f)
    }

    /**
     * The land fill is what tells a viewer which side of a coast is which, so
     * "off" has to be reachable and has to mean it — that is the pre-land-layer
     * globe, and the renderer skips the fill pass entirely there.
     */
    @Test
    fun `land fill can be turned off entirely`() {
        assertEquals(0f, GlobeFxSettings(landFill = 0f).clamped().landFill, 1e-6f)
    }

    @Test
    fun `glow width never falls below the line it sits under`() {
        assertEquals(1f, GlobeFxSettings(glowWidth = 0f).clamped().glowWidth, 1e-6f)
        assertEquals(1f, GlobeFxSettings(glowWidth = -20f).clamped().glowWidth, 1e-6f)
    }

    @Test
    fun `defaults survive clamping unchanged`() {
        assertEquals(GlobeFxSettings(), GlobeFxSettings().clamped())
    }

    /**
     * `glowing` gates a second full stroke pass over every outline on the globe,
     * so it has to be false for both of the ways the halo would be invisible —
     * no brightness, and no width to spread into.
     */
    @Test
    fun `glowing is false whenever the halo would not show`() {
        assertFalse(GlobeFxSettings(glowBrightness = 0f).glowing)
        assertFalse(GlobeFxSettings(glowBrightness = 0.005f).glowing)
        assertFalse(GlobeFxSettings(glowBrightness = 0.5f, glowWidth = 1f).glowing)
        assertTrue(GlobeFxSettings(glowBrightness = 0.5f, glowWidth = 3f).glowing)
        assertTrue(GlobeFxSettings().glowing)
    }

    /** Illumination at zero must leave the globe exactly as it was drawn before. */
    @Test
    fun `illumination can be turned off entirely`() {
        assertEquals(0f, GlobeFxSettings(illumination = 0f).clamped().illumination, 1e-6f)
    }
}
