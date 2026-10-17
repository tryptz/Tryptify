// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * A playable chart, in beats and seconds.
 *
 * This is the runtime side of the SSC the generator writes — not a second
 * format. Everything here is derived from parsed `#NOTES` and `#BPMS`, so a
 * simfile edited by hand or produced by another tool plays identically to one
 * Tryptify generated. Note times are resolved to seconds once, at load, because
 * resolving beats against a BPM list per frame is how a chart with a tempo
 * change starts drifting.
 */
data class GlyphChart(
    val difficulty: StepManiaDifficulty,
    val meter: Int,
    val chartName: String,
    val stepsType: String,
    /** Sorted by [GlyphNote.timeSeconds], then lane. */
    val notes: List<GlyphNote>,
    /** The buttons the chart is for, as [stepsType] names them. */
    val course: CourseType = CourseType.forStepsType(stepsType) ?: CourseType.DANCE,
) {
    /** The lanes this chart can put a note on, in column order. */
    val lanes: List<GlyphLane> get() = GlyphLane.forCourse(course)

    /** Notes that count toward the score — mines and fakes do not. */
    val scorableNotes: List<GlyphNote> = notes.filter { it.type.isScorable }

    /** Every subdivision this chart actually uses, for a targeted prewarm. */
    val divisionsUsed: Set<GlyphBeatDivision> =
        notes.mapTo(LinkedHashSet()) { it.division }

    val lastNoteSeconds: Float = notes.maxOfOrNull { it.endTimeSeconds } ?: 0f

    /**
     * The maximum score, in judgement terms: a hold is its head plus its tail,
     * so a chart's ceiling is not simply its note count.
     */
    val judgementCount: Int = scorableNotes.sumOf { if (it.type.hasTail) 2 else 1 }

    fun notesBetween(startSeconds: Float, endSeconds: Float): List<GlyphNote> =
        notes.filter { it.timeSeconds in startSeconds..endSeconds }
}

/**
 * One note.
 *
 * [beat] is kept alongside [timeSeconds] because the two answer different
 * questions: scoring and rendering want seconds, while the metronome, the
 * measure counter and the beat colour want beats, and re-deriving one from the
 * other at 165 Hz is wasted work.
 */
data class GlyphNote(
    val lane: GlyphLane,
    val type: GlyphNoteType,
    val beat: Float,
    val timeSeconds: Float,
    /** Equal to [timeSeconds] for anything without a tail. */
    val endTimeSeconds: Float,
    val division: GlyphBeatDivision,
    /** Zero-based measure, for the section readout and the results graph. */
    val measure: Int,
) {
    val holdDurationSeconds: Float get() = endTimeSeconds - timeSeconds
}

/**
 * What a note asks the player to do.
 *
 * The set matches SSC's note characters so a chart round-trips. [FAKE] and
 * [MINE] are drawn and are part of the chart, but never enter scoring — which
 * is why [isScorable] exists rather than callers filtering on type everywhere.
 */
enum class GlyphNoteType(
    val sscCharacter: Char,
    val isScorable: Boolean,
    val hasTail: Boolean,
    val label: String,
) {
    TAP('1', isScorable = true, hasTail = false, label = "Tap"),
    HOLD('2', isScorable = true, hasTail = true, label = "Hold"),
    ROLL('4', isScorable = true, hasTail = true, label = "Roll"),
    MINE('M', isScorable = false, hasTail = false, label = "Mine"),
    LIFT('L', isScorable = true, hasTail = false, label = "Lift"),
    FAKE('F', isScorable = false, hasTail = false, label = "Fake"),
    ;

    val isHoldLike: Boolean get() = this == HOLD || this == ROLL
}

/**
 * A whole simfile: metadata, one timing map, and every difficulty in it.
 *
 * The timing map is shared rather than copied per chart because it belongs to
 * the song. A Beginner and a Challenge chart of the same song must agree about
 * where beat 96 falls, and giving each its own copy is how they stop agreeing.
 */
data class GlyphSimfile(
    val title: String,
    val artist: String,
    val musicFileName: String,
    val credit: String,
    val timing: GlyphTiming,
    val charts: List<GlyphChart>,
    /** #SAMPLESTART: where the song is previewed from, or null when unset. */
    val sampleStartSeconds: Float? = null,
    /** #SAMPLELENGTH: how long the preview runs. */
    val sampleLengthSeconds: Float? = null,
    /** #DISPLAYBPM: the tempo the song says it is, or null to read the timing. */
    val displayBpm: GlyphDisplayBpm? = null,
) {
    /**
     * The tempo a list shows and sorts by: the display tempo's top, as
     * StepMania's BPM sort uses, else the timing's own. A hidden display
     * tempo (`*`) keeps the timing's, so the song still sorts somewhere.
     */
    val listedBpm: Float
        get() = (displayBpm as? GlyphDisplayBpm.Shown)?.high ?: timing.startBpm

    fun chart(difficulty: StepManiaDifficulty): GlyphChart? =
        charts.firstOrNull { it.difficulty == difficulty }

    val availableDifficulties: List<StepManiaDifficulty> =
        charts.map { it.difficulty }.sortedBy { it.ordinal }
}

/**
 * `#DISPLAYBPM`: what a song shows as its tempo, apart from its timing — so a
 * chart that jumps to 1500 BPM for a beat as a gimmick still reads 150. As
 * StepMania reads it: one value, a range `low:high`, or `*` for a tempo the
 * song keeps hidden.
 */
sealed interface GlyphDisplayBpm {
    data class Shown(val low: Float, val high: Float) : GlyphDisplayBpm
    data object Hidden : GlyphDisplayBpm

    companion object {
        /** The tag's value, or null when it is missing, empty or not a tempo. */
        fun parse(raw: String?): GlyphDisplayBpm? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text == "*") return Hidden
            val parts = text.split(':').map { it.trim().toFloatOrNull() }
            if (parts.isEmpty() || parts.size > 2 || parts.any { it == null || it <= 0f || !it.isFinite() }) return null
            val values = parts.filterNotNull()
            return Shown(values.min(), values.max())
        }
    }
}
