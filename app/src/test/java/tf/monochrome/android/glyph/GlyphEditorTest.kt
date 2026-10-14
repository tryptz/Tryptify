// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.SscChartWriter
import tf.monochrome.android.glyph.chart.SscParser
import tf.monochrome.android.glyph.editor.DraftNote
import tf.monochrome.android.glyph.editor.GlyphChartDraft
import tf.monochrome.android.glyph.editor.GlyphSnap

/** The map editor's chart model, and how it is written into a simfile and read back. */
class GlyphEditorTest {

    private val beat = GlyphChartDraft.TICKS_PER_BEAT

    @Test
    fun aSecondTapOfTheSameKindTakesTheNoteAway() {
        val once = GlyphChartDraft().place(0, 1, GlyphNoteType.TAP)
        assertEquals(1, once.noteCount)
        assertEquals(0, once.place(0, 1, GlyphNoteType.TAP).noteCount)
    }

    @Test
    fun aMineOnATapReplacesIt() {
        val draft = GlyphChartDraft().place(0, 2, GlyphNoteType.TAP).place(0, 2, GlyphNoteType.MINE)
        assertEquals(listOf(DraftNote(0, 2, GlyphNoteType.MINE)), draft.notes)
    }

    @Test
    fun aHoldClearsItsStretchOfTheLaneAndBlocksTapsInside() {
        val draft = GlyphChartDraft()
            .place(beat, 0, GlyphNoteType.TAP)
            .place(beat, 1, GlyphNoteType.TAP)
            .hold(0, 2 * beat, 0)
        assertEquals(2, draft.noteCount)
        val hold = draft.noteAt(beat, 0)!!
        assertEquals(GlyphNoteType.HOLD, hold.type)
        assertEquals(2 * beat, hold.endTick)
        // Inside the body nothing happens; erasing there takes the hold away.
        assertSame(draft, draft.place(beat, 0, GlyphNoteType.TAP))
        assertEquals(1, draft.erase(beat, 0).noteCount)
    }

    @Test
    fun aHoldWithNoLengthIsATap() {
        val draft = GlyphChartDraft().hold(beat, beat, 3)
        assertEquals(GlyphNoteType.TAP, draft.notes.single().type)
    }

    @Test
    fun snapsLandOnTheirOwnLines() {
        assertEquals(beat / 4 * 3, GlyphSnap.SIXTEENTH.snap(beat / 4 * 3 + 2))
        assertEquals(beat / 3, GlyphSnap.TWELFTH.snap(beat / 3 - 3))
        assertEquals(beat, GlyphSnap.QUARTER.snap(beat - 10))
        assertEquals(0, GlyphSnap.EIGHTH.snap(-5))
    }

    @Test
    fun aMeasureIsWrittenAtTheFewestRowsThatHoldIt() {
        // Quarters only: four rows. An eighth: eight. A twelfth: twelve.
        val quarters = GlyphChartDraft().place(0, 0, GlyphNoteType.TAP).place(beat, 3, GlyphNoteType.TAP)
        assertEquals(4, quarters.toNoteData().trim().lines().size)
        val eighth = quarters.place(beat / 2, 1, GlyphNoteType.TAP)
        assertEquals(8, eighth.toNoteData().trim().lines().size)
        val twelfth = quarters.place(beat / 3, 2, GlyphNoteType.TAP)
        assertEquals(12, twelfth.toNoteData().trim().lines().size)
    }

    @Test
    fun aDraftSurvivesTheSimfileAndComesBackTheSame() {
        val draft = GlyphChartDraft()
            .place(0, 0, GlyphNoteType.TAP)
            .place(beat / 2, 1, GlyphNoteType.TAP)
            .place(beat / 3 * 4, 2, GlyphNoteType.TAP)
            .place(beat * 3 / 4 + beat * 2, 3, GlyphNoteType.MINE)
            .hold(beat * 5, beat * 7, 1)
            .place(beat * 9, 0, GlyphNoteType.TAP)
        val text = SscChartWriter.newSimfile(
            title = "Edit",
            artist = "Test",
            musicFileName = "edit.mp3",
            bpm = 150f,
            beatZeroSeconds = 0.25f,
            difficulty = StepManiaDifficulty.HARD,
            meter = 9,
            noteData = draft.toNoteData(),
        )
        val simfile = SscParser.parse(text)!!
        assertEquals(0.25f, simfile.timing.offsetSeconds, 1e-4f)
        assertEquals(150f, simfile.timing.startBpm, 1e-4f)
        val chart = simfile.chart(StepManiaDifficulty.HARD)!!
        assertEquals(9, chart.meter)
        assertEquals(draft, GlyphChartDraft.of(chart, simfile.timing))
    }

    @Test
    fun savingOneChartKeepsEveryOtherOneAndTheHeader() {
        val original = """
            #TITLE:Keep me;
            #ARTIST:Someone;
            #OFFSET:-0.100;
            #BPMS:0.000=120.000;
            #SOMETHINGELSE:untouched;

            #NOTEDATA:;
            #DIFFICULTY:Easy;
            #METER:2;
            #NOTES:
            1000
            0000
            0000
            0000
            ;

            #NOTEDATA:;
            #DIFFICULTY:Medium;
            #METER:5;
            #NOTES:
            0100
            0000
            0000
            0000
            ;
        """.trimIndent()
        val edited = GlyphChartDraft().place(0, 3, GlyphNoteType.TAP).place(beat, 2, GlyphNoteType.TAP)
        val text = SscChartWriter.withChart(original, StepManiaDifficulty.MEDIUM, 5, edited.toNoteData())
        assertTrue(text.contains("#SOMETHINGELSE:untouched;"))
        val simfile = SscParser.parse(text)!!
        assertEquals("Keep me", simfile.title)
        assertEquals(GlyphLane.LEFT, simfile.chart(StepManiaDifficulty.EASY)!!.notes.single().lane)
        val medium = simfile.chart(StepManiaDifficulty.MEDIUM)!!
        assertEquals(listOf(GlyphLane.RIGHT, GlyphLane.UP), medium.notes.map { it.lane })

        // A difficulty the file does not have is added after the others.
        val added = SscChartWriter.withChart(text, StepManiaDifficulty.CHALLENGE, 11, edited.toNoteData())
        val withChallenge = SscParser.parse(added)!!
        assertEquals(3, withChallenge.charts.size)
        assertEquals(11, withChallenge.chart(StepManiaDifficulty.CHALLENGE)!!.meter)
    }

    @Test
    fun aNewTempoReplacesTheHeadersOwn() {
        val original = "#TITLE:T;\n#OFFSET:0.000;\n#BPMS:0.000=120.000;\n\n#NOTEDATA:;\n#DIFFICULTY:Easy;\n#NOTES:\n1000\n;\n"
        val text = SscChartWriter.withTempo(original, 174f, 1.5f)
        val simfile = SscParser.parse(text)!!
        assertEquals(174f, simfile.timing.startBpm, 1e-4f)
        assertEquals(1.5f, simfile.timing.offsetSeconds, 1e-4f)
        // The note now falls where beat zero is.
        assertEquals(1.5f, simfile.charts.single().notes.single().timeSeconds, 1e-4f)
    }

    @Test
    fun anEmptyDraftStillWritesAMeasure() {
        val data = GlyphChartDraft().toNoteData()
        assertEquals(4, data.trim().lines().size)
        assertNull(GlyphChartDraft().noteAt(0, 0))
        assertNotNull(GlyphChartDraft().place(0, 0, GlyphNoteType.TAP).noteAt(0, 0))
    }
}
