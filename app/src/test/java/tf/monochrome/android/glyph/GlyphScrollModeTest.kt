// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.engine.GlyphScrollMode

/**
 * The three scroll families, checked on the charts that tell them apart.
 *
 * On a constant-tempo chart with no stops every mode is a multiplier and the
 * tests would prove nothing. Each case here uses a tempo change or a stop,
 * because that is the only place the modes disagree — and disagreeing there is
 * the entire reason all three exist.
 */
class GlyphScrollModeTest {

    private val steady = GlyphTiming.constant(bpm = 120f)

    private val doubling = GlyphTiming(
        offsetSeconds = 0f,
        segments = listOf(
            GlyphTiming.BpmSegment(0f, 120f),
            GlyphTiming.BpmSegment(8f, 240f),
        ),
    )

    private val withStop = GlyphTiming(
        offsetSeconds = 0f,
        segments = listOf(GlyphTiming.BpmSegment(0f, 120f)),
        stops = listOf(GlyphTiming.Stop(beat = 4f, seconds = 1f)),
    )

    // ── XMod ────────────────────────────────────────────────────────────

    @Test
    fun xModMeasuresDistanceInBeats() {
        val mod = GlyphScrollMode.XMod(2f)
        // One second at 120 BPM is two beats, doubled by the multiplier.
        assertEquals(4f, mod.scrollUnits(steady, 0f, 1f), 1e-3f)
        assertEquals(8f, mod.scrollUnits(steady, 0f, 2f), 1e-3f)
    }

    @Test
    fun xModSpeedsUpWhenTheMusicDoes() {
        val mod = GlyphScrollMode.XMod(1f)
        // Four seconds covers eight beats at 120; the next four cover sixteen
        // at 240. The field visibly accelerates, which is XMod's whole nature.
        val before = mod.scrollUnits(doubling, 0f, 4f)
        val after = mod.scrollUnits(doubling, 4f, 8f)
        assertEquals(8f, before, 1e-2f)
        assertEquals(16f, after, 1e-2f)
        assertTrue(after > before)
    }

    @Test
    fun xModFreezesThroughAStop() {
        val mod = GlyphScrollMode.XMod(1f)
        // The stop sits at beat 4, which is t=2s, and lasts a second. No beats
        // pass, so no distance is covered and the arrows hold still.
        assertEquals(0f, mod.scrollUnits(withStop, 2.1f, 2.9f), 1e-2f)
        // Movement resumes the instant the stop ends.
        assertTrue(mod.scrollUnits(withStop, 3.0f, 3.5f) > 0.9f)
    }

    // ── CMod ────────────────────────────────────────────────────────────

    @Test
    fun cModCoversTheSameDistancePerSecondRegardless() {
        val mod = GlyphScrollMode.CMod(400f)
        val perSecond = 400f / 60f

        // Constant tempo, a tempo change, and a stop all give the same answer.
        // That is the guarantee CMod is bought for.
        assertEquals(perSecond, mod.scrollUnits(steady, 0f, 1f), 1e-3f)
        assertEquals(perSecond, mod.scrollUnits(doubling, 4f, 5f), 1e-3f)
        assertEquals(perSecond, mod.scrollUnits(withStop, 2.1f, 3.1f), 1e-3f)
    }

    @Test
    fun cModScrollsStraightThroughAStop() {
        val mod = GlyphScrollMode.CMod(400f)
        // The inverse of the XMod case, and the reason both modes exist.
        assertTrue(
            "a stop must not freeze a constant scroll",
            mod.scrollUnits(withStop, 2.1f, 2.9f) > 0f,
        )
    }

    @Test
    fun cModReadingTimeIsConstantAcrossTempi() {
        val mod = GlyphScrollMode.CMod(400f)
        assertEquals(mod.visibleSeconds(120f), mod.visibleSeconds(240f), 1e-6f)
        // Eight beats on screen at 400 BPM is 1.2 seconds of reading time.
        assertEquals(1.2f, mod.visibleSeconds(150f), 1e-3f)
    }

    // ── MMod ────────────────────────────────────────────────────────────

    @Test
    fun mModSolvesTheMultiplierFromTheChartsFastestSection() {
        val mod = GlyphScrollMode.MMod(targetBpm = 480f, chartMaxBpm = 240f)
        assertEquals(2f, mod.multiplier, 1e-4f)

        // At the fastest section it reads exactly like the CMod it was aimed at.
        assertEquals(
            GlyphScrollMode.CMod(480f).visibleSeconds(240f),
            mod.visibleSeconds(240f),
            1e-4f,
        )
        // And slower sections are genuinely slower — it is still XMod underneath.
        assertTrue(mod.visibleSeconds(120f) > mod.visibleSeconds(240f))
    }

    @Test
    fun mModKeepsMusicalSpacingLikeXMod() {
        val mMod = GlyphScrollMode.MMod(targetBpm = 240f, chartMaxBpm = 240f)
        val xMod = GlyphScrollMode.XMod(1f)
        // multiplier solves to 1, so the two must agree everywhere, stop included.
        assertEquals(
            xMod.scrollUnits(withStop, 0f, 5f),
            mMod.scrollUnits(withStop, 0f, 5f),
            1e-3f,
        )
    }

    // ── shared guarantees ───────────────────────────────────────────────

    @Test
    fun everyModeIsMonotonicAndZeroOverNoTime() {
        val modes = listOf(
            GlyphScrollMode.XMod(2f),
            GlyphScrollMode.CMod(400f),
            GlyphScrollMode.MMod(400f, 200f),
        )
        for (mode in modes) {
            assertEquals("${mode.label} over no time", 0f, mode.scrollUnits(doubling, 3f, 3f), 1e-4f)
            // A note in the future is always ahead of one nearer, or the
            // playfield would draw them out of order.
            val near = mode.scrollUnits(doubling, 0f, 4f)
            val far = mode.scrollUnits(doubling, 0f, 9f)
            assertTrue("${mode.label} must not go backwards", far > near)
        }
    }

    @Test
    fun nonsensicalValuesFallBackInsteadOfDividingByZero() {
        assertEquals(
            GlyphScrollMode.FALLBACK_SECONDS,
            GlyphScrollMode.CMod(0f).visibleSeconds(150f),
            1e-4f,
        )
        assertEquals(
            GlyphScrollMode.FALLBACK_SECONDS,
            GlyphScrollMode.XMod(2f).visibleSeconds(0f),
            1e-4f,
        )
        assertEquals(0f, GlyphScrollMode.CMod(0f).scrollUnits(steady, 0f, 5f), 1e-4f)
        // A chart whose maximum tempo could not be determined still plays.
        assertEquals(1f, GlyphScrollMode.MMod(400f, 0f).multiplier, 1e-4f)
    }

    @Test
    fun labelsUseTheNotationPlayersAlreadyRead() {
        assertEquals("2.00x", GlyphScrollMode.XMod(2f).label)
        assertEquals("C400", GlyphScrollMode.CMod(400f).label)
        assertEquals("M500", GlyphScrollMode.MMod(500f, 250f).label)
    }
}
