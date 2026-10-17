// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPopnColour
import tf.monochrome.android.glyph.asset.GlyphPopnLane
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.chart.SscChartWriter
import tf.monochrome.android.glyph.chart.SscParser
import tf.monochrome.android.glyph.editor.GlyphChartDraft
import tf.monochrome.android.glyph.engine.GlyphAutoplay
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphTimingWindows
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.withTurn
import tf.monochrome.android.glyph.input.GlyphKeyBindings
import tf.monochrome.android.ui.glyph.GlyphFieldBounds
import tf.monochrome.android.ui.glyph.glyphLaneSpans
import tf.monochrome.android.ui.glyph.popnPadGeometry

/** Playing a nine-button chart: the lanes, the turns, the keys, the pad and the engine. */
class GlyphPopnPlayTest {

    private fun note(seconds: Float, lane: GlyphLane, type: GlyphNoteType = GlyphNoteType.TAP, end: Float = seconds) =
        GlyphNote(lane, type, beat = seconds * 2f, timeSeconds = seconds, endTimeSeconds = end, division = GlyphBeatDivision.QUARTER, measure = 0)

    private fun popnChart(notes: List<GlyphNote>) = GlyphChart(
        difficulty = StepManiaDifficulty.MEDIUM,
        meter = 22,
        chartName = "Normal",
        stepsType = "pnm-nine",
        notes = notes.sortedBy { it.timeSeconds },
    )

    @Test
    fun theNineLanesAreTheNineButtons() {
        assertEquals(9, GlyphLane.entries.size)
        assertEquals(4, GlyphLane.DANCE.size)
        assertEquals(GlyphLane.DANCE, GlyphLane.forCourse(CourseType.DANCE))
        assertEquals(GlyphLane.entries, GlyphLane.forCourse(CourseType.POPN))
        assertTrue(GlyphLane.LEFT.isDance && !GlyphLane.RED.isDance)
        // Left to right: white yellow green blue red blue green yellow white.
        val colours = GlyphPopnLane.entries.map { it.colour }
        assertEquals(colours, colours.reversed())
        assertEquals(GlyphPopnColour.RED, GlyphPopnLane.of(GlyphLane.RED).colour)
        assertEquals(listOf(1, 3, 5, 7, 9), GlyphPopnLane.BOTTOM_ROW.map { it.button })
        assertEquals(listOf(2, 4, 6, 8), GlyphPopnLane.TOP_ROW.map { it.button })
        assertEquals("Left white", GlyphLane.LEFT.labelIn(CourseType.POPN))
        assertEquals("Left", GlyphLane.LEFT.labelIn(CourseType.DANCE))
    }

    @Test
    fun aPopnChartKnowsItsCourse() {
        val chart = popnChart(listOf(note(1f, GlyphLane.RIGHT_WHITE)))
        assertEquals(CourseType.POPN, chart.course)
        assertEquals(9, chart.lanes.size)
        // The course is read from the steps type when a chart is made, as the parser makes one.
        val dance = GlyphChart(StepManiaDifficulty.MEDIUM, 8, "Medium", "dance-single", chart.notes)
        assertEquals(CourseType.DANCE, dance.course)
        assertEquals(4, dance.lanes.size)
    }

    @Test
    fun turnsOnPopnMirrorAboutTheRedButtonAndSlideTheRest() {
        for (lane in GlyphLane.entries) {
            assertEquals(GlyphLane.entries[8 - lane.ordinal], GlyphTurn.MIRROR.map(lane, course = CourseType.POPN))
        }
        assertEquals(GlyphLane.RED, GlyphTurn.MIRROR.map(GlyphLane.RED, course = CourseType.POPN))
        assertEquals(GlyphLane.RIGHT_WHITE, GlyphTurn.LEFT.map(GlyphLane.LEFT, course = CourseType.POPN))
        assertEquals(GlyphLane.LEFT, GlyphTurn.RIGHT.map(GlyphLane.RIGHT_WHITE, course = CourseType.POPN))
        for (turn in GlyphTurn.entries) {
            val mapped = GlyphLane.entries.map { turn.map(it, 3L, CourseType.POPN) }
            assertEquals("$turn keeps every button", GlyphLane.entries.toSet(), mapped.toSet())
            // The dance turns leave pop'n's extra buttons where they are.
            for (lane in GlyphLane.entries.drop(4)) assertEquals(lane, turn.map(lane, 3L, CourseType.DANCE))
        }
        val chart = popnChart(listOf(note(1f, GlyphLane.LEFT), note(2f, GlyphLane.RED)))
        assertEquals(listOf(GlyphLane.RIGHT_WHITE, GlyphLane.RED), chart.withTurn(GlyphTurn.MIRROR).notes.map { it.lane })
    }

    @Test
    fun eachCourseHasItsOwnKeys() {
        val keys = GlyphKeyBindings.DEFAULT
        // Dance as it always was.
        assertEquals(GlyphLane.LEFT, keys.laneFor(21))
        assertEquals(GlyphLane.LEFT, keys.laneFor(32))
        // pop'n: A S D F, Space, J K L ; and the number row.
        assertEquals(GlyphLane.LEFT, keys.laneFor(29, CourseType.POPN))
        assertEquals(GlyphLane.RED, keys.laneFor(62, CourseType.POPN))
        assertEquals(GlyphLane.RIGHT_WHITE, keys.laneFor(74, CourseType.POPN))
        assertEquals(GlyphLane.RED, keys.laneFor(12, CourseType.POPN))
        assertNull(keys.laneFor(21, CourseType.POPN))
        // Binding in one course leaves the other alone.
        // D is pop'n's left green (lane 2); moved to red, it leaves there.
        val rebound = keys.bind(GlyphLane.RED, 0, 32, CourseType.POPN)
        assertEquals(GlyphLane.UP, rebound.movedFrom)
        assertEquals(GlyphKeyBindings.NONE, rebound.bindings.key(GlyphLane.UP, 0, CourseType.POPN))
        assertEquals(32, rebound.bindings.key(GlyphLane.RED, 0, CourseType.POPN))
        assertEquals(GlyphLane.LEFT, rebound.bindings.laneFor(32))
        assertEquals(GlyphLane.LEFT, rebound.bindings.laneFor(29, CourseType.POPN))
        // Both survive the preferences, and a line from before pop'n keeps its dance keys.
        assertEquals(rebound.bindings, GlyphKeyBindings.decode(rebound.bindings.encode()))
        val old = GlyphKeyBindings.decode("21,32;20,34;19,38;22,39")
        assertEquals(GlyphKeyBindings.DEFAULT, old)
        val oldMoved = GlyphKeyBindings.decode("62,32;20,34;19,38;22,39")
        assertEquals(GlyphLane.LEFT, oldMoved.laneFor(62))
        assertEquals(GlyphLane.RED, oldMoved.laneFor(62, CourseType.POPN))
        assertEquals(GlyphKeyBindings.DEFAULT, rebound.bindings.reset(CourseType.POPN))
    }

    @Test
    fun theFieldSplitsIntoNineLanesAndThePadIntoTwoRows() {
        val bounds = GlyphFieldBounds.of(1080, 1f, 9)
        assertEquals(120, bounds.laneWidthPx)
        assertEquals(9, bounds.lanes)
        val spans = glyphLaneSpans(1080, 1f, 1f, 9)
        assertEquals(9, spans.size)
        assertEquals(0, spans.first().first)
        assertEquals(1079, spans.last().last)

        val pad = popnPadGeometry(1000, 2000, minRowPx = 100, maxRowPx = 400)
        assertEquals(280, pad.rowHeightPx)
        assertEquals(200, pad.cellWidth)
        // The bottom row's five fill the width; the top row's four sit between them.
        val bottom = GlyphPopnLane.BOTTOM_ROW.map { pad.cell(it) }
        assertEquals(0, bottom.first().left)
        assertEquals(1000, bottom.last().left + bottom.last().width)
        assertEquals(2000, bottom.first().top + bottom.first().height)
        val top = GlyphPopnLane.TOP_ROW.map { pad.cell(it) }
        assertEquals(100, top.first().left)
        assertEquals(pad.padTop, top.first().top)
        assertEquals(bottom.first().top, top.first().top + top.first().height)
        // A thumb in the middle of the bottom edge is the red button; above it, between blue and blue, nothing.
        assertEquals(GlyphPopnLane.RED, pad.buttonAt(500, 1900))
        assertEquals(GlyphPopnLane.LEFT_BLUE, pad.buttonAt(400, 1500))
        assertNull(pad.buttonAt(500, 100))
    }

    @Test
    fun theEnginePlaysAllNineButtons() {
        val notes = GlyphLane.entries.mapIndexed { i, lane -> note(1f + i * 0.5f, lane) } +
            note(6f, GlyphLane.RIGHT_WHITE, GlyphNoteType.HOLD, 7f)
        val engine = GlyphGameplayEngine(chart = popnChart(notes), windows = GlyphTimingWindows.STANDARD, timing = GlyphTiming.constant(120f))
        val events = GlyphAutoplay().advance(engine, 8f)
        assertEquals("every note and the hold's tail judged", notes.size + 1, events.size)
        assertTrue(events.all { it.judgement == GlyphJudgement.MARVELOUS })
        assertEquals(GlyphLane.entries.toSet() , events.map { it.lane }.toSet())
        // A press on the right white is only that button's.
        engine.reset(0f)
        engine.advanceTo(5f)
        engine.press(GlyphLane.RIGHT_WHITE, 5f)
        assertTrue(engine.isHeld(GlyphLane.RIGHT_WHITE))
        assertTrue(!engine.isHeld(GlyphLane.RIGHT_YELLOW))
    }

    @Test
    fun theEditorSavesNineColumnsAndTheWriterKeepsTheOtherCourse() {
        val draft = GlyphChartDraft(course = CourseType.POPN).place(0, 8, GlyphNoteType.TAP).place(48, 4, GlyphNoteType.TAP)
        assertEquals(9, draft.laneCount)
        val rows = draft.toNoteData().lines().filter { it.isNotEmpty() && it != "," }
        assertTrue(rows.all { it.length == 9 })
        assertEquals("000000001", rows.first())

        val dance = SscChartWriter.newSimfile("S", "A", "s.mp3", 120f, 0f, StepManiaDifficulty.MEDIUM, 8, "1000\n0000\n0000\n0000\n")
        val both = SscChartWriter.withChart(dance, StepManiaDifficulty.MEDIUM, 22, draft.toNoteData(), CourseType.POPN)
        // The same difficulty of each course, side by side, each read by its own.
        assertEquals(listOf(GlyphLane.LEFT), SscParser.parse(both, CourseType.DANCE)!!.chart(StepManiaDifficulty.MEDIUM)!!.notes.map { it.lane })
        assertEquals(listOf(GlyphLane.RIGHT_WHITE, GlyphLane.RED), SscParser.parse(both, CourseType.POPN)!!.chart(StepManiaDifficulty.MEDIUM)!!.notes.map { it.lane })
        // An edit to the pop'n chart leaves the dance one as it was.
        val edited = SscChartWriter.withChart(both, StepManiaDifficulty.MEDIUM, 22, draft.erase(48, 4).toNoteData(), CourseType.POPN)
        assertEquals(listOf(GlyphLane.LEFT), SscParser.parse(edited, CourseType.DANCE)!!.chart(StepManiaDifficulty.MEDIUM)!!.notes.map { it.lane })
        assertEquals(listOf(GlyphLane.RIGHT_WHITE), SscParser.parse(edited, CourseType.POPN)!!.chart(StepManiaDifficulty.MEDIUM)!!.notes.map { it.lane })
        // Two files become one simfile for the pack, the second's header left behind.
        val popnFile = SscChartWriter.newSimfile("S", "A", "s.mp3", 120f, 0f, StepManiaDifficulty.HARD, 32, draft.toNoteData(), CourseType.POPN)
        val merged = SscChartWriter.withOtherCourse(dance, popnFile)
        assertEquals(1, Regex("#TITLE:").findAll(merged).count())
        assertEquals(StepManiaDifficulty.HARD, SscParser.parse(merged, CourseType.POPN)!!.availableDifficulties.single())
        assertEquals(StepManiaDifficulty.MEDIUM, SscParser.parse(merged, CourseType.DANCE)!!.availableDifficulties.single())
        assertEquals(dance, SscChartWriter.withOtherCourse(dance, null))
    }
}
