package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A planet's sound, drawn: and at the defaults every chart planet has, exactly
 * the look it always had.
 */
class PlanetLookTest {

    @Test
    fun `no key is the atmosphere every planet had`() {
        assertEquals(0.45f, PlanetLook.atmosphereSaturation(0f), 0f)
        assertEquals(1f, PlanetLook.atmosphereValue(0f), 0f)
    }

    @Test
    fun `minor is deeper, major paler`() {
        assertTrue(PlanetLook.atmosphereSaturation(-1f) > PlanetLook.atmosphereSaturation(0f))
        assertTrue(PlanetLook.atmosphereSaturation(1f) < PlanetLook.atmosphereSaturation(0f))
        assertTrue(PlanetLook.atmosphereValue(-1f) < 1f)
        assertEquals(1f, PlanetLook.atmosphereValue(1f), 0f)
        for (tone in listOf(-5f, -1f, 0f, 1f, 5f)) {
            assertTrue(PlanetLook.atmosphereSaturation(tone) in 0f..1f)
            assertTrue(PlanetLook.atmosphereValue(tone) in 0f..1f)
        }
    }

    @Test
    fun `a cover turns once a period, from its own phase`() {
        assertEquals(0f, PlanetLook.spinDegrees(0f, 15f, 0f), 0f)
        assertEquals(90f, PlanetLook.spinDegrees(3.75f, 15f, 0f), 1e-3f)
        assertEquals(180f, PlanetLook.spinDegrees(7.5f + 15f * 40, 15f, 0f), 1e-2f)
        assertEquals(270f, PlanetLook.spinDegrees(3.75f, 15f, Math.PI.toFloat()), 1e-3f)
        for (t in listOf(0f, 1f, 1000f, 86_400f)) {
            val d = PlanetLook.spinDegrees(t, 15f, 6.2f)
            assertTrue("$t -> $d", d >= 0f && d < 360f)
        }
    }

    @Test
    fun `a planet with no tempo does not turn`() {
        for (period in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, PlanetLook.spinDegrees(12f, period, 1f), 0f)
        }
    }

    @Test
    fun `full shade is the shadow's own alpha, and a flat master lighter`() {
        for (alpha in 0..255) assertEquals(alpha, PlanetLook.shadeAlpha(alpha, 1f))
        assertEquals(140, PlanetLook.shadeAlpha(255, 0.55f))
        assertEquals(255, PlanetLook.shadeAlpha(255, 3f))
        assertEquals(0, PlanetLook.shadeAlpha(255, -1f))
    }
}
