// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.GeneratedSimfile
import tf.monochrome.android.audio.stepmania.RhythmAnalysis
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.audio.stepmania.StepManiaMapGenerator
import tf.monochrome.android.audio.stepmania.StepManiaRequest
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.chart.SscParser

class GlyphChartTest {

    // ── the timing map ──────────────────────────────────────────────────

    @Test
    fun constantTempoMapsBeatsToSecondsBothWays() {
        val timing = GlyphTiming.constant(bpm = 120f)

        assertEquals(0f, timing.beatToSeconds(0f), 1e-5f)
        assertEquals(0.5f, timing.beatToSeconds(1f), 1e-5f)
        assertEquals(2f, timing.beatToSeconds(4f), 1e-5f)
        assertEquals(4f, timing.secondsToBeat(2f), 1e-4f)

        // Round-tripping is the property that stops a chart drifting.
        for (beat in 0..2000) {
            val seconds = timing.beatToSeconds(beat.toFloat())
            assertEquals(beat.toFloat(), timing.secondsToBeat(seconds), 1e-2f)
        }
    }

    @Test
    fun offsetShiftsTheWholeChartAgainstTheAudio() {
        // By the time a timing map exists the sign is already settled: this is
        // "beat 0 happens 0.2 s into the audio", which is what a -0.2 #OFFSET
        // means once SscParser has flipped it.
        val timing = GlyphTiming(offsetSeconds = 0.2f, segments = listOf(GlyphTiming.BpmSegment(0f, 120f)))
        assertEquals(0.2f, timing.beatToSeconds(0f), 1e-5f)
        assertEquals(0.7f, timing.beatToSeconds(1f), 1e-5f)
    }

    @Test
    fun aTempoChangeAppliesFromItsOwnBeatOnward() {
        val timing = GlyphTiming(
            offsetSeconds = 0f,
            segments = listOf(
                GlyphTiming.BpmSegment(0f, 120f),
                GlyphTiming.BpmSegment(8f, 240f),
            ),
        )

        assertEquals("eight beats at 120", 4f, timing.beatToSeconds(8f), 1e-4f)
        assertEquals("then eight more at 240", 6f, timing.beatToSeconds(16f), 1e-4f)
        assertEquals(120f, timing.bpmAt(7.99f), 1e-3f)
        assertEquals(240f, timing.bpmAt(8f), 1e-3f)
        assertEquals(16f, timing.secondsToBeat(6f), 1e-2f)
        assertEquals(120f..240f, timing.bpmRange)
    }

    @Test
    fun aStopHoldsTheBeatStillWithoutLosingThePlace() {
        val timing = GlyphTiming(
            offsetSeconds = 0f,
            segments = listOf(GlyphTiming.BpmSegment(0f, 120f)),
            stops = listOf(GlyphTiming.Stop(beat = 4f, seconds = 1f)),
        )

        assertEquals(2f, timing.beatToSeconds(4f), 1e-4f)
        // Beat 5 is a beat after the stop ends, so 2 s + 1 s stop + 0.5 s.
        assertEquals(3.5f, timing.beatToSeconds(5f), 1e-4f)
        // During the stop the beat does not advance.
        assertEquals(4f, timing.secondsToBeat(2.5f), 1e-2f)
        assertEquals(4f, timing.secondsToBeat(2.99f), 1e-2f)
    }

    @Test
    fun anEmptyOrUnusableBpmListFallsBackRatherThanDividingByZero() {
        val timing = GlyphTiming(0f, emptyList())
        assertEquals(GlyphTiming.DEFAULT_BPM, timing.startBpm, 1e-4f)
        assertTrue(timing.beatToSeconds(4f).isFinite())

        val negative = GlyphTiming(0f, listOf(GlyphTiming.BpmSegment(0f, -60f)))
        assertEquals(GlyphTiming.DEFAULT_BPM, negative.startBpm, 1e-4f)
    }

    // ── beat colours ────────────────────────────────────────────────────

    @Test
    fun subdivisionColourComesFromThePositionInTheMeasure() {
        // A 16-row measure: rows 0,4,8,12 are quarters; 2,6 are eighths; the
        // odd rows are sixteenths.
        assertEquals(GlyphBeatDivision.QUARTER, GlyphBeatDivision.forRow(0, 16))
        assertEquals(GlyphBeatDivision.QUARTER, GlyphBeatDivision.forRow(4, 16))
        assertEquals(GlyphBeatDivision.EIGHTH, GlyphBeatDivision.forRow(2, 16))
        assertEquals(GlyphBeatDivision.SIXTEENTH, GlyphBeatDivision.forRow(1, 16))

        // A 12-row measure is triplet time: row 1 is a twelfth, not a sixteenth.
        assertEquals(GlyphBeatDivision.QUARTER, GlyphBeatDivision.forRow(0, 12))
        assertEquals(GlyphBeatDivision.TWELFTH, GlyphBeatDivision.forRow(1, 12))
        assertEquals(GlyphBeatDivision.QUARTER, GlyphBeatDivision.forRow(3, 12))
    }

    // ── the SSC parser ──────────────────────────────────────────────────

    @Test
    fun readsBackTheSimfileTheGeneratorWrites() {
        // The parser's real contract: whatever the generator produces must load
        // and play. Generating and re-reading is what proves it.
        val analysis = RhythmAnalysis(
            bpm = 150f,
            offsetSeconds = 0.125f,
            confidence = 0.9f,
            durationSeconds = 12f,
            gridStrengths = FloatArray(176) { tick ->
                when {
                    tick % 16 == 0 -> 1f
                    tick % 4 == 0 -> 0.8f
                    else -> 0.5f
                }
            },
        )
        val input = SampleEdits.Buffer(FloatArray(8_000), null, 1_000)
        val generated: GeneratedSimfile = StepManiaMapGenerator({ _, _ -> analysis }).generate(
            mix = input,
            drumStem = input,
            request = StepManiaRequest(
                title = "Round Trip",
                artist = "Tryptify",
                musicFileName = "round.flac",
                difficulties = setOf(
                    StepManiaDifficulty.EASY,
                    StepManiaDifficulty.MEDIUM,
                    StepManiaDifficulty.HARD,
                ),
            ),
        )

        val parsed = SscParser.parse(generated.ssc)
        assertNotNull("the generator's own output must parse", parsed)
        assertEquals("Round Trip", parsed!!.title)
        assertEquals("round.flac", parsed.musicFileName)
        assertEquals(150f, parsed.timing.startBpm, 1e-3f)
        assertEquals(
            listOf(
                StepManiaDifficulty.EASY,
                StepManiaDifficulty.MEDIUM,
                StepManiaDifficulty.HARD,
            ),
            parsed.availableDifficulties,
        )

        // The generator writes a negative OFFSET for a positive lead-in, and the
        // parser flips it back, so beat 0 lands at +0.125 s of audio.
        assertEquals(0.125f, parsed.timing.beatToSeconds(0f), 1e-3f)

        // Density must still rise with difficulty after the round trip.
        val easy = parsed.chart(StepManiaDifficulty.EASY)!!
        val hard = parsed.chart(StepManiaDifficulty.HARD)!!
        assertTrue(easy.notes.size < hard.notes.size)
        assertTrue(easy.notes.all { it.type == GlyphNoteType.TAP })
    }

    @Test
    fun parsesHoldsRollsMinesAndLifts() {
        val ssc = """
            #TITLE:Types;
            #ARTIST:Test;
            #MUSIC:types.mp3;
            #OFFSET:0.000;
            #BPMS:0.000=120.000;

            #NOTEDATA:;
            #CHARTNAME:Types;
            #STEPSTYPE:dance-single;
            #DIFFICULTY:Hard;
            #METER:9;
            #NOTES:
            2000
            0M00
            00L0
            0004
            0000
            0000
            3003
            0000
            ;
        """.trimIndent()

        val parsed = SscParser.parse(ssc)
        assertNotNull(parsed)
        val chart = parsed!!.chart(StepManiaDifficulty.HARD)!!

        val hold = chart.notes.first { it.lane == GlyphLane.LEFT && it.type == GlyphNoteType.HOLD }
        // Eight rows to the measure, so row 6 is beat 3, which is 1.5 s at 120.
        assertEquals(0f, hold.timeSeconds, 1e-4f)
        assertEquals(1.5f, hold.endTimeSeconds, 1e-4f)

        val roll = chart.notes.first { it.type == GlyphNoteType.ROLL }
        assertEquals(GlyphLane.RIGHT, roll.lane)
        assertEquals(1.5f, roll.endTimeSeconds, 1e-4f)

        assertEquals(1, chart.notes.count { it.type == GlyphNoteType.MINE })
        assertEquals(1, chart.notes.count { it.type == GlyphNoteType.LIFT })

        // A hold is a head and a tail; the mine is neither.
        assertEquals(setOf(GlyphNoteType.HOLD, GlyphNoteType.ROLL, GlyphNoteType.LIFT),
            chart.scorableNotes.map { it.type }.toSet())
        assertEquals(5, chart.judgementCount)
    }

    @Test
    fun aHoldWithNoTailIsPlayedAsATap() {
        val ssc = """
            #TITLE:Unterminated;
            #MUSIC:x.mp3;
            #OFFSET:0.000;
            #BPMS:0.000=120.000;
            #NOTEDATA:;
            #DIFFICULTY:Easy;
            #NOTES:
            2000
            0000
            0000
            0000
            ;
        """.trimIndent()

        val chart = SscParser.parse(ssc)!!.chart(StepManiaDifficulty.EASY)!!
        // Dropping it would silently remove a note the chart plainly asks for.
        assertEquals(1, chart.notes.size)
        assertEquals(GlyphNoteType.TAP, chart.notes.first().type)
    }

    @Test
    fun aChartWithAPerChartTempoKeepsItsOwnTiming() {
        val ssc = """
            #TITLE:Override;
            #MUSIC:x.mp3;
            #OFFSET:0.000;
            #BPMS:0.000=120.000;
            #NOTEDATA:;
            #DIFFICULTY:Medium;
            #BPMS:0.000=240.000;
            #NOTES:
            1000
            0000
            0000
            0000
            1000
            0000
            0000
            0000
            ;
        """.trimIndent()

        val chart = SscParser.parse(ssc)!!.chart(StepManiaDifficulty.MEDIUM)!!
        // Row 4 of an 8-row measure is beat 2, which is 0.5 s at 240 rather
        // than the 1 s the song header would give.
        assertEquals(0.5f, chart.notes[1].timeSeconds, 1e-4f)
    }

    @Test
    fun malformedInputIsRefusedRatherThanHalfLoaded() {
        assertNull("nothing at all", SscParser.parse(""))
        assertNull("no note data", SscParser.parse("#TITLE:Empty;\n#BPMS:0.000=120.000;"))
        assertNull(
            "a chart with no difficulty cannot be offered to the player",
            SscParser.parse("#TITLE:X;\n#BPMS:0.000=120.000;\n#NOTEDATA:;\n#NOTES:\n1000\n;"),
        )
    }

    @Test
    fun oneBrokenDifficultyDoesNotCostTheOthers() {
        val ssc = """
            #TITLE:Partly broken;
            #MUSIC:x.mp3;
            #BPMS:0.000=120.000;
            #NOTEDATA:;
            #DIFFICULTY:NotARealDifficulty;
            #NOTES:
            1000
            ;
            #NOTEDATA:;
            #DIFFICULTY:Easy;
            #NOTES:
            1000
            0000
            0000
            0000
            ;
        """.trimIndent()

        val parsed = SscParser.parse(ssc)
        assertNotNull(parsed)
        assertEquals(listOf(StepManiaDifficulty.EASY), parsed!!.availableDifficulties)
    }

    @Test
    fun shortRowsArePaddedRatherThanThrowing() {
        val ssc = """
            #TITLE:Truncated;
            #MUSIC:x.mp3;
            #BPMS:0.000=120.000;
            #NOTEDATA:;
            #DIFFICULTY:Easy;
            #NOTES:
            1
            0000
            0000
            0000
            ;
        """.trimIndent()

        val chart = SscParser.parse(ssc)!!.chart(StepManiaDifficulty.EASY)!!
        assertEquals(1, chart.notes.size)
        assertEquals(GlyphLane.LEFT, chart.notes.first().lane)
    }
}
