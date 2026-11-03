// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.domain.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kick detector the music links and the lyrics' beat engine share: a hit
 * reads high and the gap between hits low, on a quiet song, a loud flat one,
 * and straight after a pause.
 */
class BandRangeTest {
    private val frame = 1f / 60f

    /** Kicks at [hitDb] over a bed at [bedDb], 2 a second; the reading on the hits and between them, last half. */
    private fun BandRange.kicks(bedDb: Float, hitDb: Float, seconds: Float): Pair<Float, Float> {
        var onHit = 1f
        var between = 0f
        val steps = (seconds / frame).toInt()
        for (i in 0 until steps) {
            val t = i * frame
            val hit = (t % 0.5f) < 0.06f
            val v = step(if (hit) hitDb else bedDb, LinkShape.DEFAULT_QUIET_DB, frame)
            if (i > steps / 2) {
                if (hit) onHit = minOf(onHit, v) else if ((t % 0.5f) > 0.3f) between = maxOf(between, v)
            }
        }
        return onHit to between
    }

    @Test
    fun `a kick reads high and the gap low`() {
        val (hit, gap) = BandRange().kicks(bedDb = -30f, hitDb = -12f, seconds = 6f)
        assertTrue("hit $hit gap $gap", hit > 0.8f && gap < 0.2f)
    }

    @Test
    fun `a loud flat master still kicks`() {
        // 4 dB between the kick and the bed, as on a brick-walled master.
        val (hit, gap) = BandRange().kicks(bedDb = -8f, hitDb = -4f, seconds = 8f)
        assertTrue("hit $hit gap $gap", hit > 0.7f && gap < 0.3f)
    }

    @Test
    fun `after a pause it kicks at once, not stuck high`() {
        val range = BandRange()
        range.kicks(bedDb = -30f, hitDb = -12f, seconds = 4f)
        repeat((3f / frame).toInt()) { range.step(-120f, LinkShape.DEFAULT_QUIET_DB, frame) }
        val (hit, gap) = range.kicks(bedDb = -30f, hitDb = -12f, seconds = 2f)
        assertTrue("hit $hit gap $gap", hit > 0.8f && gap < 0.2f)
    }
}
