// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
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
import tf.monochrome.android.glyph.training.GlyphGauntletFinder
import tf.monochrome.android.glyph.training.GlyphGauntlets

class GlyphGauntletTest {

    private fun note(
        seconds: Float,
        lane: GlyphLane,
        type: GlyphNoteType = GlyphNoteType.TAP,
        endSeconds: Float = seconds,
        division: GlyphBeatDivision = GlyphBeatDivision.EIGHTH,
    ) = GlyphNote(
        lane = lane,
        type = type,
        beat = seconds * 2f,
        timeSeconds = seconds,
        endTimeSeconds = endSeconds,
        division = division,
        measure = (seconds / 2f).toInt(),
    )

    private fun chart(notes: List<GlyphNote>) = GlyphChart(
        difficulty = StepManiaDifficulty.HARD,
        meter = 10,
        chartName = "Gauntlet",
        stepsType = "dance-single",
        notes = notes.sortedBy { it.timeSeconds },
    )

    @Test
    fun everyGauntletHasAnIdThatResolves() {
        assertEquals(5, GlyphGauntlets.ALL.size)
        for (gauntlet in GlyphGauntlets.ALL) {
            assertNotNull("${gauntlet.id} does not resolve", GlyphGauntlets.byId(gauntlet.id))
            assertTrue(gauntlet.name.isNotBlank())
            assertTrue(gauntlet.description.isNotBlank())
            assertTrue(gauntlet.targetAccuracy in 0.5f..1f)
        }
        assertNull(GlyphGauntlets.byId("not-a-gauntlet"))
    }

    @Test
    fun theStreamDrillFindsTheEvenRunNotTheSparsePart() {
        // A sparse opening, then an even stream, then a ragged burst of the
        // same note count. The stream is the one worth practising.
        val notes = ArrayList<GlyphNote>()
        var time = 0f
        repeat(8) { notes += note(time, GlyphLane.entries[it % 4]); time += 1.4f }

        val streamStart = time
        repeat(40) { notes += note(time, GlyphLane.entries[it % 4]); time += 0.125f }

        time += 3f
        repeat(40) { index ->
            notes += note(time, GlyphLane.entries[index % 4])
            time += if (index % 3 == 0) 0.05f else 0.4f
        }

        val segment = GlyphGauntletFinder.findSegment(chart(notes), "streams")
        assertNotNull(segment)
        // The even run must be inside the chosen window.
        assertTrue(
            "picked ${segment!!.startSeconds}..${segment.endSeconds}, stream at $streamStart",
            segment.startSeconds <= streamStart + 2f &&
                segment.endSeconds >= streamStart + 2f,
        )
    }

    @Test
    fun theJumpDrillFindsWhereTwoLanesFireTogether() {
        val notes = ArrayList<GlyphNote>()
        var time = 0f
        repeat(20) { notes += note(time, GlyphLane.entries[it % 4]); time += 0.4f }

        val jumpStart = time
        repeat(12) {
            notes += note(time, GlyphLane.LEFT)
            notes += note(time, GlyphLane.RIGHT)
            time += 0.5f
        }

        val segment = GlyphGauntletFinder.findSegment(chart(notes), "jumps")
        assertNotNull(segment)
        assertTrue(
            "picked ${segment!!.startSeconds}..${segment.endSeconds}, jumps at $jumpStart",
            segment.endSeconds > jumpStart,
        )
    }

    @Test
    fun theHoldDrillFindsTheLongestHolds() {
        val notes = ArrayList<GlyphNote>()
        var time = 0f
        repeat(20) { notes += note(time, GlyphLane.entries[it % 4]); time += 0.4f }

        val holdStart = time
        repeat(8) {
            notes += note(time, GlyphLane.entries[it % 4], GlyphNoteType.HOLD, time + 1.0f)
            time += 1.2f
        }

        val segment = GlyphGauntletFinder.findSegment(chart(notes), "holds")
        assertNotNull(segment)
        assertTrue(segment!!.endSeconds > holdStart)
    }

    @Test
    fun aChartWithNoSuchPatternGetsNoSegment() {
        // Nothing but single taps in one lane: no jumps and no holds exist, and
        // inventing a passage would be worse than saying so.
        val notes = (0 until 30).map { note(it * 0.5f, GlyphLane.LEFT) }
        val built = chart(notes)

        assertNull(GlyphGauntletFinder.findSegment(built, "jumps"))
        assertNull(GlyphGauntletFinder.findSegment(built, "holds"))
        assertNull(GlyphGauntletFinder.findSegment(built, "alternating"))
    }

    @Test
    fun aChartTooShortToDrillIsRefused() {
        val notes = (0 until 3).map { note(it * 0.5f, GlyphLane.LEFT) }
        assertNull(GlyphGauntletFinder.findSegment(chart(notes), "streams"))
    }

    @Test
    fun anUnknownGauntletIdYieldsNothing() {
        val notes = (0 until 40).map { note(it * 0.25f, GlyphLane.entries[it % 4]) }
        assertNull(GlyphGauntletFinder.findSegment(chart(notes), "nonsense"))
    }

    @Test
    fun aFoundSegmentIsAlwaysPlayable() {
        val notes = (0 until 80).map { note(it * 0.2f, GlyphLane.entries[it % 4]) }
        val segment = GlyphGauntletFinder.findSegment(chart(notes), "alternating")
        assertNotNull(segment)
        // A negative start or a zero-length loop would divide by zero on the
        // first wrap.
        assertTrue(segment!!.startSeconds >= 0f)
        assertTrue(segment.lengthSeconds > 0f)
        assertTrue(segment.wrap(segment.endSeconds + 0.001f) in segment)
    }
}
