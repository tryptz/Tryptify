// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphDemoChart
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphCalibration
import tf.monochrome.android.glyph.engine.GlyphAutoplay
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.input.GlyphKeyBindings

/** The keyboard's lanes, the offset read from runs, and the previews' autoplay. */
class GlyphKeysAndAutoplayTest {

    @Test
    fun theDefaultKeysAreTheArrowsAndDfjk() {
        val keys = GlyphKeyBindings.DEFAULT
        assertEquals(GlyphLane.LEFT, keys.laneFor(21))
        assertEquals(GlyphLane.LEFT, keys.laneFor(32))
        assertEquals(GlyphLane.RIGHT, keys.laneFor(39))
        assertNull(keys.laneFor(GlyphKeyBindings.NONE))
        assertEquals("←", GlyphKeyBindings.label(21))
        assertEquals("D", GlyphKeyBindings.label(32))
        assertEquals("Space", GlyphKeyBindings.label(62))
    }

    @Test
    fun aKeyMovedToAnotherLaneLeavesItsOldOne() {
        // D (32) is on Left; put it on Up's second slot.
        val rebound = GlyphKeyBindings.DEFAULT.bind(GlyphLane.UP, 1, 32)
        assertEquals(GlyphLane.LEFT, rebound.movedFrom)
        assertEquals(GlyphLane.UP, rebound.bindings.laneFor(32))
        assertEquals(GlyphKeyBindings.NONE, rebound.bindings.key(GlyphLane.LEFT, 1))
        // Setting a slot to the key it already has moves nothing.
        assertNull(GlyphKeyBindings.DEFAULT.bind(GlyphLane.LEFT, 0, 21).movedFrom)
    }

    @Test
    fun bindingsSurviveThePreferences() {
        val keys = GlyphKeyBindings.DEFAULT.bind(GlyphLane.DOWN, 0, 62).bindings
        assertEquals(keys, GlyphKeyBindings.decode(keys.encode()))
        assertEquals(GlyphKeyBindings.DEFAULT, GlyphKeyBindings.decode("nonsense"))
        assertEquals(GlyphKeyBindings.DEFAULT, GlyphKeyBindings.decode(null))
    }

    @Test
    fun backAndEscapeNeverBind() {
        assertTrue(GlyphKeyBindings.isReserved(4))
        assertTrue(GlyphKeyBindings.isReserved(111))
        assertFalse(GlyphKeyBindings.isReserved(32))
    }

    private fun run(offsetMs: Float, hits: Int = 100) = GlyphAttempt(
        id = "r$offsetMs", chartId = "c", songTitle = "S", difficulty = StepManiaDifficulty.EASY,
        playedAtEpochMs = 1L, score = 1, accuracy = 1f, maxCombo = 1,
        early = hits / 2, late = hits / 2, meanOffsetMs = offsetMs,
    )

    @Test
    fun theOffsetIsTheMedianOfRecentRunsAndIgnoresShortOnes() {
        assertNull(GlyphCalibration.lateByMs(listOf(run(20f), run(22f))))
        // The four that count are -90, 18, 21 and 25: the middle two average 19.5.
        val runs = listOf(run(18f), run(25f), run(21f), run(300f, hits = 4), run(-90f))
        assertEquals(20, GlyphCalibration.lateByMs(runs))
        assertEquals(21, GlyphCalibration.lateByMs(runs + run(30f)))
        assertEquals(GlyphCalibration.MAX_OFFSET_MS, GlyphCalibration.corrected(190, 40))
        assertEquals(35, GlyphCalibration.corrected(15, 20))
    }

    @Test
    fun autoplayHitsEveryStepPerfectly() {
        val chart = GlyphDemoChart.chart
        GlyphGameplayEngine(chart = chart, timing = GlyphDemoChart.timing, preferNative = false).use { engine ->
            val autoplay = GlyphAutoplay()
            val judged = ArrayList<GlyphJudgement>()
            var position = 0f
            val end = chart.lastNoteSeconds + 1f
            while (position < end) {
                position += 1f / 60f
                autoplay.advance(engine, position).forEach { judged += it.judgement }
            }
            assertTrue(judged.isNotEmpty())
            assertTrue(judged.all { it == GlyphJudgement.MARVELOUS })
            assertEquals(chart.judgementCount, judged.size)
        }
    }

    @Test
    fun aLoopWrapStartsTheStretchAgainWithNothingOwed() {
        val chart = GlyphDemoChart.chart
        GlyphGameplayEngine(chart = chart, timing = GlyphDemoChart.timing, preferNative = false).use { engine ->
            val autoplay = GlyphAutoplay()
            val start = GlyphDemoChart.loopStartSeconds
            val length = GlyphDemoChart.loopSeconds
            var position = start
            val judged = ArrayList<GlyphJudgement>()
            // Two laps, wrapping back to the start between them.
            repeat(2) {
                position = start
                while (position < start + length) {
                    autoplay.advance(engine, position).forEach { judged += it.judgement }
                    position += 1f / 60f
                }
            }
            assertTrue(judged.none { it == GlyphJudgement.MISS })
        }
    }

    @Test
    fun theDemoChartRepeatsExactlyAcrossItsLoop() {
        val chart = GlyphDemoChart.chart
        val start = GlyphDemoChart.loopStartSeconds
        val length = GlyphDemoChart.loopSeconds
        val first = chart.notes.filter { it.timeSeconds >= start && it.timeSeconds < start + length }
        val second = chart.notes.filter { it.timeSeconds >= start + length && it.timeSeconds < start + 2 * length }
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.lane, b.lane)
            assertEquals(a.timeSeconds + length, b.timeSeconds, 1e-3f)
        }
    }
}
