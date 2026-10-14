// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

/**
 * Where a song's drop is: the moment it lands hardest, for the preview.
 *
 * StepMania previews a song from its #SAMPLESTART, which a simfile's author
 * sets by hand. Generated charts have no author, so the drop is found the
 * way an ear finds it: the point where what follows is loudest and busiest
 * against what came just before. Each candidate is scored as the level over
 * the next window, plus how far that is above the window before — a big
 * landing into a loud section wins, a long loud section with no build-up
 * comes second.
 *
 * Takes evenly spaced levels — the waveform's bars, or the rhythm analysis'
 * sixteenth-note strengths — and the time they span. The first and last
 * stretches are left out: a song's first second is not its drop, and a
 * preview has to have the song left to play.
 */
object DropFinder {

    /** How much of the song a side of the comparison covers, in seconds. */
    private const val WINDOW_SECONDS = 8f
    private const val SKIP_START_SHARE = 0.08f
    private const val SKIP_END_SHARE = 0.25f
    private const val LANDING_WEIGHT = 1.5f

    /**
     * The drop's time in seconds, or null when there is too little to go on.
     * [levels] are 0 or more, evenly over [durationSeconds].
     */
    fun find(levels: List<Float>, durationSeconds: Float): Float? {
        val n = levels.size
        if (n < 8 || durationSeconds <= 0f) return null
        val step = durationSeconds / n
        val window = (WINDOW_SECONDS / step).toInt().coerceIn(1, n / 4)
        val first = maxOf(window, (n * SKIP_START_SHARE).toInt())
        val last = minOf(n - window, (n * (1f - SKIP_END_SHARE)).toInt())
        if (first >= last) return null

        // Prefix sums: every window's mean in constant time.
        val sums = DoubleArray(n + 1)
        for (i in 0 until n) sums[i + 1] = sums[i] + levels[i].coerceAtLeast(0f)
        fun mean(from: Int, to: Int) = ((sums[to] - sums[from]) / (to - from)).toFloat()

        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in first until last) {
            val after = mean(i, i + window)
            val before = mean(i - window, i)
            val score = after + LANDING_WEIGHT * (after - before)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0 || bestScore <= 0f) return null
        return best * step
    }
}
