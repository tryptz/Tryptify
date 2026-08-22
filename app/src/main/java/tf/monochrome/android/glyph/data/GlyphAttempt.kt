// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.engine.GlyphJudgement

/**
 * One saved run, and the ghost that can be played back against it.
 *
 * Versioned from the first release rather than when it first needs to change,
 * because the thing being stored is a player's history: the wrong migration
 * story here costs someone their records. [GlyphAttemptStore] refuses to guess
 * at a version it does not know instead of misreading it as the current one.
 *
 * Deliberately compact. A ghost is the timing of each note, not a recording of
 * the screen: three small numbers per judgement, stored as parallel arrays
 * rather than a list of objects, so a five-minute Challenge run is a few tens
 * of kilobytes of JSON instead of megabytes of rendered frames.
 */
@Serializable
data class GlyphAttempt(
    /** Bumped whenever the stored shape changes. See [GlyphAttemptStore]. */
    @SerialName("v") val version: Int = CURRENT_VERSION,
    @SerialName("id") val id: String,
    @SerialName("chartId") val chartId: String,
    @SerialName("songTitle") val songTitle: String,
    @SerialName("difficulty") val difficulty: StepManiaDifficulty,
    @SerialName("playedAtEpochMs") val playedAtEpochMs: Long,
    @SerialName("score") val score: Int,
    @SerialName("accuracy") val accuracy: Float,
    @SerialName("maxCombo") val maxCombo: Int,
    @SerialName("judgementCounts") val judgementCounts: Map<String, Int> = emptyMap(),
    @SerialName("early") val early: Int = 0,
    @SerialName("late") val late: Int = 0,
    @SerialName("meanOffsetMs") val meanOffsetMs: Float = 0f,
    @SerialName("deviationMs") val deviationMs: Float = 0f,
    /** Speed the run was played at. A 0.7× run is not a 1.0× record. */
    @SerialName("speed") val speed: Float = 1f,
    @SerialName("mirror") val mirror: Boolean = false,
    /** Set when the run was a training segment rather than the whole song. */
    @SerialName("segmentStart") val segmentStartSeconds: Float? = null,
    @SerialName("segmentEnd") val segmentEndSeconds: Float? = null,
    @SerialName("ghost") val ghost: GlyphGhost? = null,
) {
    val isFullRun: Boolean get() = segmentStartSeconds == null

    fun judgementCount(judgement: GlyphJudgement): Int =
        judgementCounts[judgement.name] ?: 0

    companion object {
        /**
         * 1 — the shape shipped with the mode.
         *
         * When this changes, add a migration in [GlyphAttemptStore.migrate] and
         * leave the old reader in place. Attempts are the only thing in this
         * mode a player cannot regenerate.
         */
        const val CURRENT_VERSION = 1
    }
}

/**
 * A previous run's timing, for ghost playback.
 *
 * Parallel arrays rather than a list of records: the JSON is roughly a third
 * the size, and playback only ever walks them in order. Times are milliseconds
 * as ints because sub-millisecond ghost precision is below what anyone can
 * perceive and floats would double the width for nothing.
 */
@Serializable
data class GlyphGhost(
    @SerialName("v") val version: Int = GlyphAttempt.CURRENT_VERSION,
    /** Song position of each judged note, ascending. */
    @SerialName("t") val timesMs: List<Int> = emptyList(),
    /** Lane index per judgement, 0..3. */
    @SerialName("l") val lanes: List<Int> = emptyList(),
    /** Signed timing error per judgement, negative is early. */
    @SerialName("o") val offsetsMs: List<Int> = emptyList(),
    /** Judgement ordinal per note. */
    @SerialName("j") val judgements: List<Int> = emptyList(),
) {
    /**
     * True when the four arrays agree about how many judgements there were.
     *
     * A ghost that fails this is dropped rather than played: reading past the
     * end of a shorter array during playback would be a crash in the middle of
     * a song.
     */
    val isConsistent: Boolean
        get() = timesMs.size == lanes.size &&
            timesMs.size == offsetsMs.size &&
            timesMs.size == judgements.size

    val size: Int get() = timesMs.size

    fun judgementAt(index: Int): GlyphJudgement =
        GlyphJudgement.entries.getOrElse(judgements[index]) { GlyphJudgement.MISS }

    /** Ghost judgements landing in `[fromMs, toMs)`, for the overlay. */
    fun between(fromMs: Int, toMs: Int): List<Int> {
        if (!isConsistent) return emptyList()
        val indices = ArrayList<Int>()
        for (index in timesMs.indices) {
            val time = timesMs[index]
            if (time >= toMs) break
            if (time >= fromMs) indices += index
        }
        return indices
    }

    companion object {
        val EMPTY = GlyphGhost()
    }
}

/**
 * Collects a ghost while a run is in progress.
 *
 * Kept out of the engine because the engine has no business knowing whether
 * anyone is recording. Bounded: a run that somehow produces more judgements
 * than [MAX_JUDGEMENTS] stops recording rather than growing without limit.
 */
class GlyphGhostRecorder {
    private val times = ArrayList<Int>()
    private val lanes = ArrayList<Int>()
    private val offsets = ArrayList<Int>()
    private val judgements = ArrayList<Int>()

    fun record(songSeconds: Float, lane: Int, offsetSeconds: Float?, judgement: GlyphJudgement) {
        if (times.size >= MAX_JUDGEMENTS) return
        times += (songSeconds * 1000f).toInt()
        lanes += lane
        offsets += ((offsetSeconds ?: 0f) * 1000f).toInt()
        judgements += judgement.ordinal
    }

    fun build(): GlyphGhost = GlyphGhost(
        version = GlyphAttempt.CURRENT_VERSION,
        timesMs = times.toList(),
        lanes = lanes.toList(),
        offsetsMs = offsets.toList(),
        judgements = judgements.toList(),
    )

    fun clear() {
        times.clear()
        lanes.clear()
        offsets.clear()
        judgements.clear()
    }

    val size: Int get() = times.size

    private companion object {
        /** Far above any real chart; a guard against a runaway, not a limit. */
        const val MAX_JUDGEMENTS = 50_000
    }
}
