package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ── Timing at any speed ────────────────────────────────────────────────

    @Test
    fun `heard and media time convert through the speed`() {
        assertEquals(4_000L, CrossfadeRamp.heardMs(6_000L, 1.5f))
        assertEquals(9_000L, CrossfadeRamp.mediaMs(6_000L, 1.5f))
        assertEquals(6_000L, CrossfadeRamp.heardMs(6_000L, 1f))
    }

    @Test
    fun `the blend begins one crossfade of heard time before the end`() {
        // 6 s blend at 1.5x: the last 9 s of media are 6 s of listening.
        assertEquals(171_000L, CrossfadeRamp.fadeStartMs(150_000L, 180_000L, 6_000L, 1.5f, 1_500L))
        // At half speed only 3 s of media fill the same 6 s.
        assertEquals(177_000L, CrossfadeRamp.fadeStartMs(150_000L, 180_000L, 6_000L, 0.5f, 1_500L))
    }

    @Test
    fun `preparation starts a lead ahead of the blend point, in heard time`() {
        val lead = 1_500L
        // 7.5 s heard left at 1.0x: blend 6 s + lead 1.5 s — prepare now.
        assertTrue(CrossfadeRamp.shouldPrepare(172_500L, 180_000L, 6_000L, 1f, lead))
        assertFalse(CrossfadeRamp.shouldPrepare(172_000L, 180_000L, 6_000L, 1f, lead))
        // The same media position at 2x is only 3.75 s of listening away.
        assertTrue(CrossfadeRamp.shouldPrepare(172_500L, 180_000L, 6_000L, 2f, lead))
        // And at 2x a point 15 s of media out is 7.5 s heard: prepare.
        assertTrue(CrossfadeRamp.shouldPrepare(165_000L, 180_000L, 6_000L, 2f, lead))
    }

    @Test
    fun `a seek into the last seconds still leaves the tail its head start`() {
        // Past the ideal point: the blend begins one lead from now.
        assertEquals(176_500L, CrossfadeRamp.fadeStartMs(175_000L, 180_000L, 6_000L, 1f, 1_500L))
        // Too close to blend at all.
        assertNull(CrossfadeRamp.fadeStartMs(178_200L, 180_000L, 6_000L, 1f, 1_500L))
    }

    @Test
    fun `a track that is all crossfade at its speed is not blended`() {
        // 10 s of track at 2x is 5 s heard: shorter than a 6 s blend.
        assertFalse(CrossfadeRamp.shouldPrepare(0L, 10_000L, 6_000L, 2f, 1_500L))
    }
}

