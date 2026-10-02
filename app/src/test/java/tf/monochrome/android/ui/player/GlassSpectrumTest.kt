package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry behind the Glass spectrum style.
 *
 * [glassBodyProfile] decides the shape the glass shader bevels, and
 * [anchorInFrame] decides which slice of the cover that glass refracts. Both
 * fail silently when wrong — a body that splits into islands, a peak flattened
 * against the top of its layer, or glass lensing the wrong part of the art —
 * so they are pinned here.
 */
class GlassSpectrumTest {

    private val n = 256
    private val baseline = 300f

    /** An envelope whose every point sits [heightOf] px above the baseline. */
    private fun profile(
        minThickness: Float = 30f,
        ceiling: Float = 12f,
        heightOf: (Int) -> Float,
    ): FloatArray {
        val tops = FloatArray(n) { baseline - heightOf(it) }
        glassBodyProfile(tops, n, baseline, minThickness, ceiling, ripple = 0f, phase = 0f, scratch = FloatArray(n))
        return tops
    }

    @Test
    fun `a silent spectrum is still one body, a film of the minimum thickness`() {
        val tops = profile { 0f }
        // Away from the rounded ends, every point is the film's thickness up.
        for (j in n / 4 until 3 * n / 4) {
            assertEquals(baseline - 30f, tops[j], 0.01f)
        }
    }

    @Test
    fun `the tallest peak stops at the ceiling, never past it`() {
        // A spectrum pinned far above the box.
        val tops = profile(ceiling = 12f) { 10_000f }
        tops.forEach { assertTrue("top $it went above the ceiling", it >= 12f - 0.01f) }
        assertEquals(12f, tops[n / 2], 0.01f)
    }

    @Test
    fun `the ends round off to nothing`() {
        val tops = profile { 120f }
        assertEquals("first point sits on the baseline", baseline, tops[0], 0.01f)
        assertEquals("last point sits on the baseline", baseline, tops[n - 1], 0.01f)
        // ...and rises monotonically into the body across the taper.
        val taperEnd = (GLASS_TAPER * (n - 1)).toInt()
        for (j in 1..taperEnd) assertTrue(tops[j] <= tops[j - 1] + 0.001f)
    }

    @Test
    fun `a single-bin spike is smoothed into a rounded swell`() {
        val tops = profile { if (it == n / 2) 200f else 0f }
        val peak = baseline - tops[n / 2]
        val beside = baseline - tops[n / 2 + 1]
        val far = baseline - tops[n / 2 + 4]
        // Spread to its neighbours, lower than the raw spike...
        assertTrue("peak $peak should be below the raw spike", peak < 30f + 200f)
        assertTrue("neighbour $beside should be lifted by the spike", beside > 30f + 1f)
        // ...and the spread stays local: two passes of 1-2-1 reach two bins.
        assertEquals(30f, far, 0.01f)
    }

    @Test
    fun `louder is taller`() {
        val quiet = profile { 40f }
        val loud = profile { 160f }
        assertTrue(loud[n / 2] < quiet[n / 2])
    }

    @Test
    fun `a ripple moves the surface but never past the ceiling or the floor`() {
        val tops = FloatArray(n) { baseline - 10_000f }
        glassBodyProfile(tops, n, baseline, 30f, 12f, ripple = 8f, phase = 1.3f, scratch = FloatArray(n))
        tops.forEach { assertTrue(it >= 12f - 0.01f && it <= baseline + 0.01f) }
    }

    @Test
    fun `a pane is restated against the frame the cover is drawn in`() {
        // The hero art at (40, 300), 1000 px square; the spectrum band is its bottom 350 px.
        val frame = AnchorRect(left = 40f, top = 300f, rootW = 1000f, rootH = 1000f)
        val pane = AnchorRect(left = 40f, top = 950f, rootW = 1080f, rootH = 2400f)
        val r = anchorInFrame(pane, frame)
        assertEquals(0f, r.left, 0.001f)
        assertEquals(650f, r.top, 0.001f)
        assertEquals(1000f, r.rootW, 0.001f)
        assertEquals(1000f, r.rootH, 0.001f)

        // Through the same crop arithmetic as every other pane, a square cover
        // in a square frame maps the band to the bottom 35% of the art.
        val art = backdropArtRect(128, 128, r.rootW, r.rootH, r.left, r.top, 1000f, 350f)
        assertEquals(0f, art[0], 0.01f)
        assertEquals(128f * 0.65f, art[1], 0.01f)
        assertEquals(128f, art[2], 0.01f)
        assertEquals(128f * 0.35f, art[3], 0.01f)
    }

    @Test
    fun `an unmeasured frame means no art rather than the wrong art`() {
        val pane = AnchorRect(0f, 950f, 1080f, 2400f)
        assertEquals(AnchorRect.Unset, anchorInFrame(pane, AnchorRect.Unset))
        assertEquals(AnchorRect.Unset, anchorInFrame(AnchorRect.Unset, AnchorRect(0f, 0f, 100f, 100f)))
    }
}
