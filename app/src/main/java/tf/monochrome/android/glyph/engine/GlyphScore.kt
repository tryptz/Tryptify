// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Running score, combo and timing statistics.
 *
 * A plain accumulator with no clock and no chart: it is handed judgements and
 * offsets and answers questions about them. Keeping it this small is what makes
 * the scoring rules directly testable — `GlyphScoreTest` drives it with
 * sequences of judgements rather than by simulating a song.
 *
 * Offsets are summed rather than stored. A five-minute Challenge chart is a few
 * thousand notes, and the results screen needs the mean, the spread and the
 * early/late split, none of which require keeping every sample. The per-note
 * detail that *is* worth keeping goes to the ghost recorder, which stores it
 * compactly and on purpose.
 */
class GlyphScore(
    /** Judgements the chart can award. A hold counts twice: head and tail. */
    private val totalJudgements: Int,
) {
    private val counts = IntArray(GlyphJudgement.entries.size)

    private var weightSum = 0f
    private var offsetSum = 0.0
    private var offsetSquareSum = 0.0
    private var offsetSamples = 0

    var combo: Int = 0
        private set

    var maxCombo: Int = 0
        private set

    var early: Int = 0
        private set

    var late: Int = 0
        private set

    /** Mines hit and holds dropped, which the results screen reports separately. */
    var minesHit: Int = 0
        private set

    var holdsDropped: Int = 0
        private set

    val judged: Int get() = counts.sum()

    fun count(judgement: GlyphJudgement): Int = counts[judgement.ordinal]

    /**
     * Points out of [MAX_SCORE].
     *
     * Scaled by the chart's own judgement count so a short chart and a long one
     * are on the same scale, which is what lets a training segment's score mean
     * anything next to a full run's.
     */
    val score: Int
        get() = if (totalJudgements <= 0) 0 else {
            (weightSum / totalJudgements * MAX_SCORE).toInt().coerceIn(0, MAX_SCORE)
        }

    /**
     * Accuracy over the notes judged so far, not over the whole chart.
     *
     * Deliberately: mid-song this should read as "how well am I playing", not
     * be dragged toward zero by a chart that has barely started.
     */
    val accuracy: Float
        get() = if (judged <= 0) 0f else weightSum / judged

    /** Accuracy if every remaining note were missed — the results-screen figure. */
    val finalAccuracy: Float
        get() = if (totalJudgements <= 0) 0f else weightSum / totalJudgements

    val grade: GlyphGrade get() = GlyphGrade.forAccuracy(finalAccuracy)

    /** Mean signed offset in seconds. Negative is early. */
    val meanOffsetSeconds: Float
        get() = if (offsetSamples == 0) 0f else (offsetSum / offsetSamples).toFloat()

    /**
     * Standard deviation of the offsets — the consistency readout.
     *
     * A player with a 40 ms mean and a 5 ms deviation has a calibration problem
     * and is otherwise playing well; one with a 0 ms mean and a 60 ms deviation
     * does not. Reporting only the mean hides the difference.
     */
    val offsetDeviationSeconds: Float
        get() {
            if (offsetSamples < 2) return 0f
            val mean = offsetSum / offsetSamples
            val variance = (offsetSquareSum / offsetSamples - mean * mean).coerceAtLeast(0.0)
            return sqrt(variance).toFloat()
        }

    /** 0 = every hit early, 1 = every hit late, 0.5 = balanced. */
    val lateShare: Float
        get() = (early + late).let { if (it == 0) 0.5f else late.toFloat() / it }

    /**
     * Record one judged note.
     *
     * [offsetSeconds] is null for judgements with no timing to speak of — a
     * note that timed out, or a hold tail that simply completed.
     */
    fun record(judgement: GlyphJudgement, offsetSeconds: Float?) {
        counts[judgement.ordinal] += 1
        weightSum += judgement.weight

        if (judgement.continuesCombo) {
            combo += 1
            if (combo > maxCombo) maxCombo = combo
        } else {
            combo = 0
        }

        if (offsetSeconds != null && judgement.isHit) {
            offsetSum += offsetSeconds
            offsetSquareSum += offsetSeconds.toDouble() * offsetSeconds
            offsetSamples += 1
            // A hit inside a millisecond of the line is neither early nor late;
            // counting it as one would put a bias in the balance readout that
            // the player cannot act on.
            if (offsetSeconds < -DEAD_ZONE_SECONDS) early += 1
            else if (offsetSeconds > DEAD_ZONE_SECONDS) late += 1
        }
    }

    fun recordMineHit() {
        minesHit += 1
        combo = 0
    }

    fun recordHoldDropped() {
        holdsDropped += 1
    }

    /** A snapshot for the UI. Cheap enough to take every frame. */
    fun snapshot(): GlyphScoreSnapshot = GlyphScoreSnapshot(
        score = score,
        combo = combo,
        maxCombo = maxCombo,
        accuracy = accuracy,
        finalAccuracy = finalAccuracy,
        judged = judged,
        totalJudgements = totalJudgements,
        counts = GlyphJudgement.entries.associateWith { counts[it.ordinal] },
        early = early,
        late = late,
        minesHit = minesHit,
        holdsDropped = holdsDropped,
        meanOffsetSeconds = meanOffsetSeconds,
        offsetDeviationSeconds = offsetDeviationSeconds,
    )

    companion object {
        const val MAX_SCORE = 1_000_000

        /** Offsets inside this count as neither early nor late. */
        const val DEAD_ZONE_SECONDS = 0.001f
    }
}

/** An immutable view of a [GlyphScore], safe to hand to composables. */
data class GlyphScoreSnapshot(
    val score: Int = 0,
    val combo: Int = 0,
    val maxCombo: Int = 0,
    val accuracy: Float = 0f,
    val finalAccuracy: Float = 0f,
    val judged: Int = 0,
    val totalJudgements: Int = 0,
    val counts: Map<GlyphJudgement, Int> = emptyMap(),
    val early: Int = 0,
    val late: Int = 0,
    val minesHit: Int = 0,
    val holdsDropped: Int = 0,
    val meanOffsetSeconds: Float = 0f,
    val offsetDeviationSeconds: Float = 0f,
) {
    val grade: GlyphGrade get() = GlyphGrade.forAccuracy(finalAccuracy)

    val lateShare: Float
        get() = (early + late).let { if (it == 0) 0.5f else late.toFloat() / it }

    /** "12 ms early" / "on time", for the live readout. */
    val offsetLabel: String
        get() {
            val ms = (meanOffsetSeconds * 1000f).toInt()
            return when {
                abs(ms) < 2 -> "on time"
                ms < 0 -> "${-ms} ms early"
                else -> "$ms ms late"
            }
        }
}
