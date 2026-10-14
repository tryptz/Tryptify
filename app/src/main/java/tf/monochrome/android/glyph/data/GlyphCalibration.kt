// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import kotlin.math.roundToInt

/**
 * The audio offset, read from how the player has actually been hitting.
 *
 * StepMania's autosync idea, without a calibration track: a run already
 * records the mean of every press's distance from its note, and a phone
 * whose sound comes out late shows up there as a player who is late on every
 * song. The median of the recent runs' means is that lateness, taken past
 * the odd run played badly or on other headphones. It is measured with the
 * offset that was set at the time already applied, so it is a correction to
 * add to the offset, not a new one.
 */
object GlyphCalibration {
    /** Fewer timed hits than this and a run says more about the run than the phone. */
    const val MIN_HITS = 30

    /** Runs needed before there is anything to suggest. */
    const val MIN_RUNS = 3

    /** The offset never goes past this either way. */
    const val MAX_OFFSET_MS = 200

    /**
     * How much later, in whole milliseconds, the player has been hitting than
     * the notes, or null when too few runs say. Positive is late.
     */
    fun lateByMs(runs: List<GlyphAttempt>): Int? {
        val means = runs
            .filter { it.early + it.late >= MIN_HITS }
            .map { it.meanOffsetMs }
            .sorted()
        if (means.size < MIN_RUNS) return null
        val middle = means.size / 2
        val median = if (means.size % 2 == 1) means[middle] else (means[middle - 1] + means[middle]) / 2f
        return median.roundToInt()
    }

    /** [current] moved by [lateByMs], kept inside the range. */
    fun corrected(current: Int, lateByMs: Int): Int =
        (current + lateByMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
}
