package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The blend curves and the decision of when to start one. The equal-power
 * property is the point of the whole thing — a linear fade audibly dips in the
 * middle of every transition — so it's asserted directly.
 */
class CrossfadeRampTest {

    private val tolerance = 0.001f

    @Test
    fun `fades start and end at the right levels`() {
        assertEquals(0f, CrossfadeRamp.fadeIn(0f), tolerance)
        assertEquals(1f, CrossfadeRamp.fadeIn(1f), tolerance)
        assertEquals(1f, CrossfadeRamp.fadeOut(0f), tolerance)
        assertEquals(0f, CrossfadeRamp.fadeOut(1f), tolerance)
    }

    @Test
    fun `combined power stays flat across the blend`() {
        // out^2 + in^2 == 1 everywhere is what keeps perceived loudness
        // constant. A linear fade would give 0.5 at the midpoint and dip ~3dB.
        var p = 0f
        while (p <= 1f) {
            val out = CrossfadeRamp.fadeOut(p)
            val incoming = CrossfadeRamp.fadeIn(p)
            assertTrue(
                "power dipped at progress=$p",
                abs((out * out + incoming * incoming) - 1f) < 0.001f,
            )
            p += 0.05f
        }
    }

    @Test
    fun `the midpoint sits above half, unlike a linear fade`() {
        val mid = CrossfadeRamp.fadeIn(0.5f)
        assertTrue("expected ~0.707, got $mid", mid > 0.7f && mid < 0.72f)
    }

    @Test
    fun `fades are monotonic`() {
        var previousIn = -1f
        var previousOut = 2f
        var p = 0f
        while (p <= 1f) {
            val incoming = CrossfadeRamp.fadeIn(p)
            val out = CrossfadeRamp.fadeOut(p)
            assertTrue(incoming >= previousIn)
            assertTrue(out <= previousOut)
            previousIn = incoming
            previousOut = out
            p += 0.05f
        }
    }

    @Test
    fun `progress is clamped so a late tick cannot wrap the curve`() {
        assertEquals(0f, CrossfadeRamp.progress(-100, 4_000), tolerance)
        assertEquals(0.5f, CrossfadeRamp.progress(2_000, 4_000), tolerance)
        assertEquals(1f, CrossfadeRamp.progress(9_999, 4_000), tolerance)
        assertEquals(1f, CrossfadeRamp.progress(0, 0), tolerance)
    }

    @Test
    fun `out of range progress is clamped in the curves too`() {
        assertEquals(0f, CrossfadeRamp.fadeIn(-1f), tolerance)
        assertEquals(1f, CrossfadeRamp.fadeIn(5f), tolerance)
    }

    // --- when to start ---

    @Test
    fun `starts once the track is within the blend length of the end`() {
        assertFalse(CrossfadeRamp.shouldStart(positionMs = 100_000, durationMs = 200_000, crossfadeMs = 5_000))
        assertTrue(CrossfadeRamp.shouldStart(positionMs = 195_000, durationMs = 200_000, crossfadeMs = 5_000))
        assertTrue(CrossfadeRamp.shouldStart(positionMs = 199_000, durationMs = 200_000, crossfadeMs = 5_000))
    }

    @Test
    fun `never starts when blending is off`() {
        assertFalse(CrossfadeRamp.shouldStart(positionMs = 199_000, durationMs = 200_000, crossfadeMs = 0))
    }

    @Test
    fun `never starts on an unknown duration`() {
        // A live stream or a not-yet-prepared item reports no duration; blending
        // from an unknown end point would fire immediately and every tick after.
        assertFalse(CrossfadeRamp.shouldStart(positionMs = 1_000, durationMs = 0, crossfadeMs = 5_000))
        assertFalse(CrossfadeRamp.shouldStart(positionMs = 1_000, durationMs = -1, crossfadeMs = 5_000))
    }

    @Test
    fun `never starts on a track shorter than the blend itself`() {
        // Otherwise a 2s interstitial with a 12s blend would start blending
        // before it had begun.
        assertFalse(CrossfadeRamp.shouldStart(positionMs = 0, durationMs = 2_000, crossfadeMs = 12_000))
    }
}
