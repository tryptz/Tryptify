// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.SscParser

/**
 * pop'n music charts from the same stem analysis as the dance ones: nine
 * buttons, pop'n's own tiers, and a simfile StepMania reads as pnm-nine.
 */
class PopnChartTest {

    /** A beat-only analysis: downbeats loudest, then quarters, eighths and sixteenths. */
    private fun beatOnly(stems: StemFeatures? = null, ticks: Int = 256) = RhythmAnalysis(
        bpm = 150f,
        offsetSeconds = 0.1f,
        confidence = 0.9f,
        durationSeconds = ticks * 0.1f,
        gridStrengths = FloatArray(ticks) { tick ->
            when {
                tick % 16 == 0 -> 1f
                tick % 4 == 0 -> 0.8f
                tick % 2 == 0 -> 0.5f
                else -> 0.7f
            }
        },
        stems = stems,
    )

    private fun features(
        ticks: Int = 256,
        melody: (Int) -> Float = { 0f },
        contour: (Int) -> Float = { 0f },
        sustain: (Int) -> Int = { 0 },
    ) = StemFeatures(
        melodyOnsets = FloatArray(ticks, melody),
        bassOnsets = FloatArray(ticks),
        sustainTicks = IntArray(ticks, sustain),
        contour = FloatArray(ticks, contour),
        layers = setOf(StemLayer.VOCALS),
    )

    private fun popn(analysis: RhythmAnalysis, title: String = "Pop Song") = StepManiaMapGenerator().generate(
        analysis,
        StepManiaRequest(title, "Test", "pop.flac", difficulties = StepManiaDifficulty.entries.toSet(), course = CourseType.POPN),
    )

    private fun lanesOf(event: StepEvent): List<Int> = (0 until 9).filter { event.laneMask and (1 shl it) != 0 }

    @Test
    fun everyTierStaysOnItsButtons() {
        val charts = popn(beatOnly()).charts.associateBy { it.difficulty }
        // pop'n's EASY charts use three to nine buttons; the old 5-Buttons mode
        // the centre five. Normal, Hyper and EX use all nine.
        val spans = mapOf(
            StepManiaDifficulty.BEGINNER to 3..5,
            StepManiaDifficulty.EASY to 2..6,
            StepManiaDifficulty.MEDIUM to 0..8,
            StepManiaDifficulty.HARD to 0..8,
            StepManiaDifficulty.CHALLENGE to 0..8,
        )
        for ((difficulty, chart) in charts) {
            assertEquals(CourseType.POPN, chart.course)
            val lanes = chart.events.flatMap(::lanesOf)
            assertTrue("$difficulty has notes", lanes.isNotEmpty())
            assertTrue("$difficulty stays in ${spans.getValue(difficulty)}: $lanes", lanes.all { it in spans.getValue(difficulty) })
        }
        // The full-width tiers reach both whites.
        val ex = charts.getValue(StepManiaDifficulty.CHALLENGE).events.flatMap(::lanesOf).toSet()
        assertTrue("EX uses the left white", 0 in ex)
        assertTrue("EX uses the right white", 8 in ex)
        assertTrue("EX uses the red centre", 4 in ex)
    }

    @Test
    fun chordsAreMirrorPairsAroundTheRedButton() {
        val charts = popn(beatOnly()).charts
        var chords = 0
        for (chart in charts) for (event in chart.events) {
            val lanes = lanesOf(event)
            if (lanes.size < 2) continue
            chords += 1
            assertEquals("a chord is two buttons", 2, lanes.size)
            assertEquals("mirrored about the red button: $lanes", 8, lanes[0] + lanes[1])
        }
        assertTrue("Hard and EX put chords on the downbeats", chords > 0)
        // Never on the easy tiers.
        for (chart in charts.filter { it.difficulty.ordinal <= StepManiaDifficulty.MEDIUM.ordinal }) {
            assertTrue(chart.events.all { it.laneCount == 1 })
        }
    }

    @Test
    fun noButtonIsStruckTwiceRunning() {
        for (chart in popn(beatOnly()).charts) {
            val singles = chart.events.filter { it.laneCount == 1 }
            for (i in 1 until singles.size) {
                assertTrue("${chart.difficulty}: a jack at tick ${singles[i].tick}", singles[i].laneMask != singles[i - 1].laneMask)
            }
        }
    }

    @Test
    fun theTuneStepsAcrossTheButtons() {
        // Off-beat notes climbing by about a third each time, then falling.
        val tune = features(
            melody = { if (it % 4 == 2) 0.95f else 0f },
            contour = { tick -> 8f + 0.3f * ((tick / 4) % 8).let { if (it < 4) it else 7 - it } },
        )
        val ex = popn(beatOnly(tune)).charts.first { it.difficulty == StepManiaDifficulty.CHALLENGE }
        val tuneEvents = ex.events.filter { it.tick % 4 == 2 && it.laneCount == 1 }
        val lanes = tuneEvents.map { Integer.numberOfTrailingZeros(it.laneMask) }
        var withTheTune = 0
        var steps = 0
        for (i in 1 until tuneEvents.size) {
            val to = tuneEvents[i].tick / 4 % 8
            val rising = to in 1..3
            val falling = to in 5..7
            if (!rising && !falling) continue
            val moved = lanes[i] - lanes[i - 1]
            // Off an edge it turns back, so only count steps with room to move.
            if (rising && lanes[i - 1] == 8 || falling && lanes[i - 1] == 0) continue
            steps += 1
            if (rising && moved > 0 || falling && moved < 0) withTheTune += 1
        }
        assertTrue("buttons step with the tune: $withTheTune of $steps", steps > 4 && withTheTune * 10 >= steps * 7)
        // A third moves two buttons where a semitone moves one.
        assertTrue("a leap covers more than one button", lanes.zipWithNext().any { (a, b) -> kotlin.math.abs(b - a) >= 2 })
    }

    @Test
    fun aHeldNoteIsALongPopKun() {
        val tune = features(melody = { if (it == 32) 1f else 0f }, contour = { if (it == 32) 8f else 0f }, sustain = { if (it == 32) 12 else 0 })
        val simfile = popn(beatOnly(tune))
        val normal = simfile.charts.first { it.difficulty == StepManiaDifficulty.MEDIUM }
        val hold = normal.events.first { it.holdTicks > 0 }
        assertEquals(32, hold.tick)
        assertEquals(12, hold.holdTicks)
        // And it reads back as a hold on that button, 1.2 seconds long at 150 BPM.
        val parsed = SscParser.parse(simfile.ssc, CourseType.POPN)!!
        val read = parsed.chart(StepManiaDifficulty.MEDIUM)!!.notes.first { it.type == GlyphNoteType.HOLD }
        assertEquals(Integer.numberOfTrailingZeros(hold.laneMask), read.lane.ordinal)
        assertEquals(1.2f, read.holdDurationSeconds, 0.01f)
    }

    @Test
    fun theSimfileIsPnmNineWithNineColumnRows() {
        val simfile = popn(beatOnly())
        assertTrue(simfile.ssc.contains("#STEPSTYPE:pnm-nine;"))
        assertTrue(!simfile.ssc.contains("dance-single"))
        // pop'n's levels run 1 to 50: EX sits at 40.
        assertTrue(simfile.ssc.contains("#METER:40;"))
        val rows = simfile.ssc.lineSequence().filter { it.length in 1..12 && it.all { c -> c in "0123" } }.toList()
        assertTrue(rows.isNotEmpty())
        assertTrue("every row is nine wide", rows.all { it.length == 9 })
        assertTrue(rows.any { it != "000000000" })
    }

    @Test
    fun eachCourseReadsOnlyItsOwnCharts() {
        val analysis = beatOnly()
        val dance = StepManiaMapGenerator().generate(
            analysis,
            StepManiaRequest("Dance Song", "Test", "d.flac", difficulties = setOf(StepManiaDifficulty.MEDIUM)),
        )
        val popn = popn(analysis)
        // A pop'n file read as dance has nothing, and the other way round.
        assertNull(SscParser.parse(popn.ssc, CourseType.DANCE))
        assertNull(SscParser.parse(dance.ssc, CourseType.POPN))
        val read = SscParser.parse(popn.ssc, CourseType.POPN)
        assertNotNull(read)
        assertEquals(StepManiaDifficulty.entries, read!!.availableDifficulties)
        val ex = read.chart(StepManiaDifficulty.CHALLENGE)!!
        assertEquals(CourseType.POPN, ex.course)
        assertEquals(40, ex.meter)
        assertEquals(GlyphLane.forCourse(CourseType.POPN), ex.lanes)
        assertTrue("notes land on the nine buttons", ex.notes.any { it.lane.ordinal > 3 })
        // StepMania's own older spelling reads too.
        assertNotNull(SscParser.parse(popn.ssc.replace("pnm-nine", "popn-nine"), CourseType.POPN))
    }

    @Test
    fun theDanceChartIsUnchangedByTheCourseField() {
        val analysis = beatOnly()
        val request = StepManiaRequest("Same", "Test", "s.flac", difficulties = StepManiaDifficulty.entries.toSet())
        val explicit = StepManiaMapGenerator().generate(analysis, request.copy(course = CourseType.DANCE))
        val implicit = StepManiaMapGenerator().generate(analysis, request)
        assertEquals(implicit.ssc, explicit.ssc)
        assertTrue(implicit.ssc.contains("#STEPSTYPE:dance-single;"))
        assertTrue(implicit.charts.all { it.course == CourseType.DANCE && it.events.all { e -> e.laneMask < 16 } })
    }

    @Test
    fun courseNamesAndTiers() {
        assertEquals(CourseType.POPN, CourseType.forStepsType("pnm-nine"))
        assertEquals(CourseType.POPN, CourseType.forStepsType("POPN-NINE"))
        assertEquals(CourseType.DANCE, CourseType.forStepsType(""))
        assertEquals(CourseType.DANCE, CourseType.forStepsType(null))
        assertNull(CourseType.forStepsType("dance-double"))
        assertEquals(CourseType.POPN, CourseType.fromId("POPN"))
        assertEquals(CourseType.DANCE, CourseType.fromId("nonsense"))
        assertEquals(9, CourseType.MAX_LANES)
        assertEquals("Normal", CourseType.POPN.difficultyLabel(StepManiaDifficulty.MEDIUM))
        assertEquals("EX", CourseType.POPN.difficultyLabel(StepManiaDifficulty.CHALLENGE))
        assertEquals("Challenge", CourseType.DANCE.difficultyLabel(StepManiaDifficulty.CHALLENGE))
        assertEquals(".popn", CourseType.POPN.fileSuffix)
        assertEquals("", CourseType.DANCE.fileSuffix)
    }
}
