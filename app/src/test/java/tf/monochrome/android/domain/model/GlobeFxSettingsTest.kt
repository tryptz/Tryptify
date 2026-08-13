package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reactive-outline parameters.
 *
 * Two things here are load-bearing rather than cosmetic. The zoom ramp is the
 * only thing standing between the whole-Earth view and an unreadable smear, so
 * it is checked at both ends and for monotonicity in between. And [clamped] is
 * what a decoded JSON blob passes through — a persisted amplitude the renderer
 * would multiply the globe's radius by is worth pinning down.
 */
class GlobeFxSettingsTest {

    /** Matches MIN_ZOOM in WorldRadioScreen — the widest the globe ever goes. */
    private val minZoom = 0.85f

    @Test
    fun `effect is fully suppressed at the widest zoom`() {
        val fx = GlobeFxSettings(fullEffectZoom = 6f)
        assertEquals(0f, fx.zoomGain(minZoom, minZoom), 1e-4f)
    }

    @Test
    fun `effect reaches full strength at the configured zoom and stays there`() {
        val fx = GlobeFxSettings(fullEffectZoom = 6f)
        assertEquals(1f, fx.zoomGain(6f, minZoom), 1e-4f)
        assertEquals(1f, fx.zoomGain(48f, minZoom), 1e-4f)
    }

    @Test
    fun `gain rises monotonically between the two ends`() {
        val fx = GlobeFxSettings(fullEffectZoom = 6f)
        var previous = -1f
        var zoom = minZoom
        while (zoom <= 6f) {
            val gain = fx.zoomGain(zoom, minZoom)
            assertTrue("gain went backwards at zoom $zoom", gain >= previous - 1e-5f)
            assertTrue("gain left 0..1 at zoom $zoom", gain in 0f..1f)
            previous = gain
            zoom += 0.05f
        }
    }

    /**
     * The whole point of the ramp: at the wide view a mid-zoom setting must hold
     * the effect well below half, or the coastline detail it is protecting is
     * already gone by the time the gain gets there.
     */
    @Test
    fun `mid zoom still reads as mostly suppressed at whole-Earth scale`() {
        val fx = GlobeFxSettings(fullEffectZoom = 6f)
        assertTrue(fx.zoomGain(1.2f, minZoom) < 0.35f)
    }

    /**
     * The slider's bottom stop reads "Always" in the sheet, so it has to mean
     * it — full strength at every zoom including the widest, not a ramp
     * squeezed into the sliver between 0.85 and 1.
     */
    @Test
    fun `setting the ramp to its minimum disables it`() {
        val fx = GlobeFxSettings(fullEffectZoom = GlobeFxSettings.MIN_RAMP)
        assertEquals(1f, fx.zoomGain(minZoom, minZoom), 1e-4f)
        assertEquals(1f, fx.zoomGain(2f, minZoom), 1e-4f)
        assertEquals(1f, fx.zoomGain(48f, minZoom), 1e-4f)
    }

    /** The renderer's MIN_ZOOM and the ramp's floor have to stay in step. */
    @Test
    fun `ramp floor matches the globe's widest zoom`() {
        assertEquals(minZoom, GlobeFxSettings.MIN_RAMP, 1e-6f)
    }

    /** A ramp target below the minimum zoom must not divide by a zero span. */
    @Test
    fun `degenerate ramp target yields full gain rather than NaN`() {
        val fx = GlobeFxSettings(fullEffectZoom = 0.1f)
        val gain = fx.zoomGain(2f, minZoom)
        assertFalse(gain.isNaN())
        assertEquals(1f, gain, 1e-4f)
    }

    @Test
    fun `clamped pins every parameter into its slider range`() {
        val wild = GlobeFxSettings(
            amount = 12f,
            swell = -4f,
            shimmer = 99f,
            waveSpeed = 0f,
            waveDetail = 50f,
            fullEffectZoom = 900f,
        ).clamped()

        assertEquals(1f, wild.amount, 1e-6f)
        assertEquals(0f, wild.swell, 1e-6f)
        assertEquals(1.5f, wild.shimmer, 1e-6f)
        assertEquals(0.25f, wild.waveSpeed, 1e-6f)
        assertEquals(3f, wild.waveDetail, 1e-6f)
        assertEquals(24f, wild.fullEffectZoom, 1e-6f)
        assertEquals(
            GlobeFxSettings.MIN_RAMP,
            GlobeFxSettings(fullEffectZoom = -5f).clamped().fullEffectZoom,
            1e-6f,
        )
    }

    @Test
    fun `defaults survive clamping unchanged`() {
        assertEquals(GlobeFxSettings(), GlobeFxSettings().clamped())
    }

    /**
     * `enabled` gates the analyzer stake and the per-frame loop, so it has to be
     * false for every combination that would draw nothing — not just amount 0.
     */
    @Test
    fun `enabled is false whenever nothing would move`() {
        assertFalse(GlobeFxSettings(amount = 0f).enabled)
        assertFalse(GlobeFxSettings(amount = 0.005f).enabled)
        assertFalse(GlobeFxSettings(amount = 1f, swell = 0f, shimmer = 0f).enabled)
        assertTrue(GlobeFxSettings(amount = 1f, swell = 0f, shimmer = 0.4f).enabled)
        assertTrue(GlobeFxSettings().enabled)
    }
}
