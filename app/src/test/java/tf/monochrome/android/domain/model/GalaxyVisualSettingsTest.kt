package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The galaxy's look: the shipped defaults, the clamp, and what is stored. */
class GalaxyVisualSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the defaults are the galaxy as it shipped`() {
        val d = GalaxyVisualSettings.DEFAULT
        assertEquals(1f, d.spin, 0f)
        assertEquals(1f, d.starSize, 0f)
        assertEquals(1f, d.rayShade, 0f)
        assertEquals(GalaxyAmount.NORMAL, d.dust)
        assertEquals(true, d.blackHole && d.godRays && d.smoke && d.musicReactive && d.planets && d.deepSky)
        assertEquals(d, d.clamped())
    }

    @Test
    fun `out-of-range and broken values are pulled back in`() {
        val wild = GalaxyVisualSettings(
            nebulae = 9f, rayStrength = -1f, spin = Float.NaN, smokeAmount = 100f,
            reactivity = Float.POSITIVE_INFINITY, starSize = 0f, rayShade = 7f,
        ).clamped()
        assertEquals(GalaxyVisualSettings.SHADE_RANGE.endInclusive, wild.rayShade, 0f)
        assertEquals(GalaxyVisualSettings.NEBULAE_RANGE.endInclusive, wild.nebulae, 0f)
        assertEquals(GalaxyVisualSettings.RAY_RANGE.start, wild.rayStrength, 0f)
        assertEquals(1f, wild.spin, 0f)
        assertEquals(GalaxyVisualSettings.SMOKE_RANGE.endInclusive, wild.smokeAmount, 0f)
        assertEquals(1f, wild.reactivity, 0f)
        assertEquals(GalaxyVisualSettings.STAR_SIZE_RANGE.start, wild.starSize, 0f)
    }

    @Test
    fun `what is stored is what comes back`() {
        val tuned = GalaxyVisualSettings(blackHole = false, spin = 0f, labels = GalaxyAmount.MORE, smokeAmount = 1.4f)
        val back = json.decodeFromString(GalaxyVisualSettings.serializer(), json.encodeToString(GalaxyVisualSettings.serializer(), tuned))
        assertEquals(tuned, back)
    }

    @Test
    fun `a copy from before a setting existed reads it as the default`() {
        val old = json.decodeFromString(GalaxyVisualSettings.serializer(), """{"spin":2.0,"someFutureThing":true}""")
        assertEquals(2f, old.spin, 0f)
        assertEquals(GalaxyVisualSettings.DEFAULT.copy(spin = 2f), old)
    }
}
