// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.editor

import kotlin.math.roundToInt
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming

/**
 * A chart being edited, on a grid of ticks rather than in seconds.
 *
 * [TICKS_PER_BEAT] is 48, which holds every snap the editor offers exactly —
 * quarters to 48ths, the triplet 12ths and 24ths among them — so a note placed
 * on a snap line is saved on it, with no float to round. A chart loaded from a
 * file is read onto the same grid; anything finer than a 48th (a 64th or a
 * 192nd) moves to the nearest 48th, which is the only lossy step.
 *
 * Immutable: every edit returns a new draft, which is what makes undo a stack
 * of drafts and nothing more.
 */
data class GlyphChartDraft(
    /** Sorted by tick, then lane. */
    val notes: List<DraftNote> = emptyList(),
) {
    val noteCount: Int get() = notes.size

    /** The last tick anything reaches, a hold's end included. */
    val lastTick: Int get() = notes.maxOfOrNull { it.endTick } ?: 0

    /** The note whose head is on [tick] in [lane], or whose hold covers it. */
    fun noteAt(tick: Int, lane: Int): DraftNote? =
        notes.firstOrNull { it.lane == lane && tick in it.tick..it.endTick }

    /**
     * A tap or a mine at [tick]. The same kind already there is taken away, so
     * a second tap undoes the first; a different kind is replaced. Inside a
     * hold's body nothing happens: the hold is there, and a note cannot be.
     */
    fun place(tick: Int, lane: Int, type: GlyphNoteType): GlyphChartDraft {
        require(!type.hasTail) { "holds are placed with hold()" }
        val existing = noteAt(tick, lane)
        return when {
            existing == null -> with(DraftNote(tick, lane, type))
            existing.tick != tick -> this
            existing.type == type -> without(existing)
            else -> without(existing).with(DraftNote(tick, lane, type))
        }
    }

    /**
     * A hold (or a roll) from [fromTick] to [toTick] in [lane]. Whatever was in
     * that stretch of the lane goes, since a hold owns it. An end at or before
     * the head is a tap instead: a hold with no length is not a hold.
     */
    fun hold(fromTick: Int, toTick: Int, lane: Int, type: GlyphNoteType = GlyphNoteType.HOLD): GlyphChartDraft {
        require(type.isHoldLike) { "a hold is a hold or a roll" }
        val start = minOf(fromTick, toTick)
        val end = maxOf(fromTick, toTick)
        if (end == start) return place(start, lane, GlyphNoteType.TAP)
        val cleared = notes.filterNot { it.lane == lane && it.endTick >= start && it.tick <= end }
        return GlyphChartDraft(cleared).with(DraftNote(start, lane, type, end))
    }

    /** Take away whatever is at [tick] in [lane], a hold it falls inside included. */
    fun erase(tick: Int, lane: Int): GlyphChartDraft = noteAt(tick, lane)?.let { without(it) } ?: this

    private fun with(note: DraftNote): GlyphChartDraft =
        GlyphChartDraft((notes + note).sortedWith(ORDER))

    private fun without(note: DraftNote): GlyphChartDraft = GlyphChartDraft(notes - note)

    /**
     * StepMania's `#NOTES` body: a measure at a time, `,` between measures,
     * each at the fewest rows that still land every note on a row of its own,
     * as StepMania's editor saves them. At least one measure, so an empty
     * draft still writes something a parser accepts as a chart's shape.
     */
    fun toNoteData(): String {
        val measures = (lastTick / TICKS_PER_MEASURE + 1).coerceAtLeast(1)
        // What each lane shows on each tick: a head's own character, or '3'
        // where a hold or a roll ends.
        val cells = HashMap<Int, CharArray>()
        fun cell(tick: Int): CharArray = cells.getOrPut(tick) { CharArray(LANES) { '0' } }
        for (note in notes) {
            cell(note.tick)[note.lane] = note.type.sscCharacter
            if (note.type.hasTail) cell(note.endTick)[note.lane] = '3'
        }
        return buildString {
            for (measure in 0 until measures) {
                val first = measure * TICKS_PER_MEASURE
                val used = cells.keys.filter { it in first until first + TICKS_PER_MEASURE }.map { it - first }
                val rows = ROWS_PER_MEASURE.first { rows ->
                    val step = TICKS_PER_MEASURE / rows
                    used.all { it % step == 0 }
                }
                val step = TICKS_PER_MEASURE / rows
                for (row in 0 until rows) {
                    val lanes = cells[first + row * step]
                    if (lanes == null) append("0000") else append(lanes)
                    append('\n')
                }
                if (measure + 1 < measures) append(",\n")
            }
        }
    }

    companion object {
        const val TICKS_PER_BEAT = 48
        const val BEATS_PER_MEASURE = 4
        const val TICKS_PER_MEASURE = TICKS_PER_BEAT * BEATS_PER_MEASURE
        const val LANES = 4

        /** The row counts StepMania saves a measure at, coarsest first. */
        private val ROWS_PER_MEASURE = listOf(4, 8, 12, 16, 24, 32, 48, 64, 96, 192)

        private val ORDER = compareBy<DraftNote>({ it.tick }, { it.lane })

        /** The tick nearest [beat]. */
        fun tickOf(beat: Float): Int = (beat * TICKS_PER_BEAT).roundToInt()

        /**
         * [chart] read onto the grid. A hold's end is taken from its end time
         * through [timing], the same map the chart was read with, so it lands
         * back on the beat it was written on.
         */
        fun of(chart: GlyphChart, timing: GlyphTiming): GlyphChartDraft {
            val notes = chart.notes.map { note ->
                val tick = tickOf(note.beat)
                val end = if (note.type.hasTail) {
                    tickOf(timing.secondsToBeat(note.endTimeSeconds)).coerceAtLeast(tick + 1)
                } else {
                    tick
                }
                DraftNote(tick, note.lane.ordinal, note.type, end)
            }
            // Two notes the grid rounded onto one cell keep the first.
            return GlyphChartDraft(notes.distinctBy { it.tick to it.lane }.sortedWith(ORDER))
        }
    }
}

/** One note of a [GlyphChartDraft]: a head on [tick] in [lane], to [endTick] for a hold. */
data class DraftNote(
    val tick: Int,
    val lane: Int,
    val type: GlyphNoteType,
    val endTick: Int = tick,
)

/** The editor's snaps: notes a beat, StepMania's names for them. */
enum class GlyphSnap(val perBeat: Int, val label: String) {
    QUARTER(1, "4th"),
    EIGHTH(2, "8th"),
    TWELFTH(3, "12th"),
    SIXTEENTH(4, "16th"),
    TWENTY_FOURTH(6, "24th"),
    THIRTY_SECOND(8, "32nd"),
    ;

    val ticks: Int get() = GlyphChartDraft.TICKS_PER_BEAT / perBeat

    /** [tick] moved to the nearest line of this snap. */
    fun snap(tick: Int): Int = ((tick.toFloat() / ticks).roundToInt() * ticks).coerceAtLeast(0)
}

/** What a tap on the grid does. */
enum class GlyphEditTool(val label: String) {
    TAP("Tap"),
    HOLD("Hold"),
    MINE("Mine"),
    ERASE("Erase"),
}
