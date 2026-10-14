// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphVisibleFrame

/**
 * The practice-loop window, the per-frame visible query and engine lifetime.
 *
 * These run on the Kotlin twin (the JVM cannot load the native library), and
 * cpp/glyph/tests asserts the same cases against the C++ engine, so the two
 * are held to one answer.
 */
class GlyphEngineWindowTest {

    private fun note(
        seconds: Float,
        lane: GlyphLane = GlyphLane.LEFT,
        type: GlyphNoteType = GlyphNoteType.TAP,
        endSeconds: Float = seconds,
    ) = GlyphNote(
        lane = lane,
        type = type,
        beat = seconds * 2f,
        timeSeconds = seconds,
        endTimeSeconds = endSeconds,
        division = GlyphBeatDivision.QUARTER,
        measure = (seconds / 2f).toInt(),
    )

    private fun chart(vararg notes: GlyphNote) = GlyphChart(
        difficulty = StepManiaDifficulty.MEDIUM,
        meter = 5,
        chartName = "test",
        stepsType = "dance-single",
        notes = notes.sortedWith(compareBy({ it.timeSeconds }, { it.lane.ordinal })),
    )

    @Test
    fun theKotlinTwinIsWhatTheJvmRuns() {
        // Nothing here can load a .so; the facade must fall back, not fail.
        assertFalse(GlyphGameplayEngine(chart(note(1f))).isNative)
    }

    @Test
    fun aLoopPassDoesNotOpenWithAMissForEveryNoteBeforeIt() {
        val engine = GlyphGameplayEngine(
            chart(note(1f), note(2f), note(10.5f), note(11f), note(30f, type = GlyphNoteType.HOLD, endSeconds = 31f)),
        )
        engine.reset(fromSeconds = 10f, untilSeconds = 12f)
        assertEquals("the loop is scored on what is inside it", 2, engine.scoreSnapshot.totalJudgements)

        engine.advanceTo(10f)
        assertEquals(0, engine.scoreSnapshot.judged)
        assertTrue("no misses for notes before the loop", engine.drainEvents().isEmpty())

        engine.advanceTo(10.45f)
        engine.press(GlyphLane.LEFT, 10.5f)
        engine.advanceTo(10.95f)
        engine.press(GlyphLane.LEFT, 11f)
        val score = engine.scoreSnapshot
        assertEquals(2, score.judged)
        assertEquals(0, score.counts[GlyphJudgement.MISS])
        assertEquals("a perfect pass of the loop reads as perfect", 1f, score.finalAccuracy, 1e-6f)
        assertEquals(1_000_000, score.score)
    }

    @Test
    fun notesOutsideTheLoopAreNeitherDrawnNorHittable() {
        val engine = GlyphGameplayEngine(chart(note(5f), note(30f)))
        engine.reset(fromSeconds = 10f, untilSeconds = 20f)
        val frame = GlyphVisibleFrame()
        engine.fillVisible(25f, 35f, 30f, GlyphScrollMode.DEFAULT, frame)
        assertEquals(0, frame.count)
        engine.advanceTo(29.9f)
        assertNull(engine.press(GlyphLane.LEFT, 30f))
    }

    @Test
    fun aPlainResetStillPlaysTheWholeChart() {
        // Including a note before zero, which a chart with a large offset has.
        val engine = GlyphGameplayEngine(chart(note(-0.5f), note(1f)))
        engine.advanceTo(2f)
        engine.reset()
        assertEquals(2, engine.scoreSnapshot.totalJudgements)
        engine.advanceTo(-0.6f)
        assertNotNull(engine.press(GlyphLane.LEFT, -0.5f))
    }

    @Test
    fun theVisibleFrameMatchesScrollUnitsExactly() {
        val doubling = GlyphTiming(
            offsetSeconds = 0f,
            segments = listOf(GlyphTiming.BpmSegment(0f, 120f), GlyphTiming.BpmSegment(8f, 240f)),
        )
        val engine = GlyphGameplayEngine(
            chart(
                note(3f),
                note(4.5f, lane = GlyphLane.UP, type = GlyphNoteType.HOLD, endSeconds = 6f),
                note(9f),
            ),
            timing = doubling,
        )
        val mode = GlyphScrollMode.XMod(1.5f)
        val frame = GlyphVisibleFrame(initialCapacity = 1)
        engine.fillVisible(2f, 7f, 2.5f, mode, frame)

        assertEquals("the frame grows to fit", 2, frame.count)
        assertEquals(0, frame.noteIndex(0))
        assertEquals(1, frame.noteIndex(1))
        assertEquals(mode.scrollUnits(doubling, 2.5f, 3f), frame.headUnits(0), 0f)
        assertEquals(mode.scrollUnits(doubling, 2.5f, 4.5f), frame.headUnits(1), 0f)
        assertEquals(mode.scrollUnits(doubling, 2.5f, 6f), frame.tailUnits(1), 0f)
        assertEquals(frame.headUnits(1), frame.bodyHeadUnits(1), 0f)
        assertFalse(frame.isHeld(1))
    }

    @Test
    fun aHeldBodyStopsAtTheReceptor() {
        val engine = GlyphGameplayEngine(chart(note(1f, type = GlyphNoteType.HOLD, endSeconds = 3f)))
        engine.advanceTo(0.95f)
        engine.press(GlyphLane.LEFT, 0.98f)
        val frame = GlyphVisibleFrame()
        engine.fillVisible(0.5f, 4f, 0.98f, GlyphScrollMode.CMod(400f), frame)
        assertEquals(1, frame.count)
        assertTrue(frame.isHeld(0))
        assertTrue(frame.isHeadJudged(0))
        assertEquals(0f, frame.bodyHeadUnits(0), 1e-6f)
        assertTrue(frame.headUnits(0) > 0f)
    }

    @Test
    fun judgedTapsLeaveTheFieldAndTheQueryStartsAtTheCursor() {
        val notes = (0 until 5000).map { i ->
            note(1f + i * 0.1f, lane = GlyphLane.entries[i % 4])
        }
        val engine = GlyphGameplayEngine(chart(*notes.toTypedArray()))
        engine.advanceTo(400f)
        val frame = GlyphVisibleFrame()
        engine.fillVisible(399.5f, 401f, 400f, GlyphScrollMode.DEFAULT, frame)
        assertTrue(frame.count in 1..64)
        assertTrue("timed-out notes are skipped, not drawn", frame.noteIndex(0) > 3900)
    }

    @Test
    fun comboIsReadWithoutASnapshot() {
        val engine = GlyphGameplayEngine(chart(note(1f), note(2f)))
        engine.advanceTo(0.9f)
        engine.press(GlyphLane.LEFT, 1f)
        assertEquals(1, engine.combo)
        assertEquals(engine.scoreSnapshot.combo, engine.combo)
        assertEquals(1, engine.heldMask)
        engine.release(GlyphLane.LEFT, 1.05f)
        assertEquals(0, engine.heldMask)
    }

    @Test
    fun timingLookupsAreUnchangedBySearching() {
        // The binary searches must land exactly where the old walks did.
        val timing = GlyphTiming(
            offsetSeconds = 0.1f,
            segments = listOf(
                GlyphTiming.BpmSegment(0f, 120f),
                GlyphTiming.BpmSegment(16f, 180f),
                GlyphTiming.BpmSegment(40f, 90f),
            ),
            stops = listOf(GlyphTiming.Stop(8f, 0.5f), GlyphTiming.Stop(20f, 0.25f), GlyphTiming.Stop(44f, 1f)),
        )
        var previous = Float.NEGATIVE_INFINITY
        var seconds = -1f
        while (seconds < 60f) {
            val beat = timing.secondsToBeat(seconds)
            assertTrue("beats never run backwards ($seconds s)", beat >= previous - 1e-4f)
            previous = beat
            seconds += 0.01f
        }
        for (b in 0..60) {
            val beat = b.toFloat()
            assertEquals(beat, timing.secondsToBeat(timing.beatToSeconds(beat)), 1e-2f)
        }
        assertEquals(90f..180f, timing.bpmRange)
    }
}
