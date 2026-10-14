// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.training

import kotlin.math.abs

/**
 * The Training Ground's waveform, built while the song is decoded rather than
 * after.
 *
 * The picker needs a couple of hundred bars, and those used to be cut from the
 * whole song decoded into memory first: up to ten minutes of stereo float, two
 * hundred and thirty megabytes, on every song picked — and two at once when the
 * player picked another before the first had finished. That is the crash this
 * replaces. Here each decoded block is folded into [CAPACITY] fine buckets as
 * it arrives and thrown away, so the memory is a few kilobytes whatever the
 * song's length.
 *
 * The fine buckets span [expectedFrames] when the file says how long it is.
 * When it does not, or says wrong, they start small and double their span,
 * merging pairs, whenever they fill — so a song of any length still ends up in
 * at most [CAPACITY] of them.
 *
 * Each bar is the loudest sample in its stretch, either channel, 0 to 1: the
 * picker draws a symmetric bar, so it needs a magnitude, not a min and a max.
 * [loudness] is the other measure, the RMS over each stretch: a loud modern
 * master peaks near full scale almost everywhere, and only its average level
 * says where it builds and where it drops.
 */
class GlyphWaveformBuilder(expectedFrames: Long = 0L) {

    private val fine = FloatArray(CAPACITY)
    private val energy = DoubleArray(CAPACITY)
    private var span: Long =
        if (expectedFrames > 0L) maxOf(1L, (expectedFrames + CAPACITY - 1) / CAPACITY) else INITIAL_SPAN
    private var current = 0
    private var inBucket = 0L

    var frames = 0L
        private set

    fun add(left: FloatArray, right: FloatArray?, count: Int) {
        for (i in 0 until count) {
            var magnitude = abs(left[i])
            if (right != null) {
                val r = abs(right[i])
                if (r > magnitude) magnitude = r
            }
            if (inBucket == span) {
                current += 1
                inBucket = 0L
                if (current == CAPACITY) compact()
            }
            if (magnitude > fine[current]) fine[current] = magnitude
            val mid = if (right != null) (left[i] + right[i]) * 0.5f else left[i]
            energy[current] += (mid * mid).toDouble()
            inBucket += 1
        }
        frames += count
    }

    /** [bars] magnitudes, 0 to 1, evenly over the song; empty when nothing was decoded. */
    fun bars(bars: Int): List<Float> {
        if (frames == 0L || bars <= 0) return emptyList()
        val used = current + if (inBucket > 0L) 1 else 0
        return List(bars) { bar ->
            val from = (used.toLong() * bar / bars).toInt()
            val to = maxOf(from + 1, (used.toLong() * (bar + 1) / bars).toInt()).coerceAtMost(used)
            var peak = 0f
            for (k in from until to) if (fine[k] > peak) peak = fine[k]
            peak.coerceIn(0f, 1f)
        }
    }

    /**
     * [bars] RMS levels, evenly over the song, of the mono mix; empty when
     * nothing was decoded. What a drop is found from.
     */
    fun loudness(bars: Int): List<Float> {
        if (frames == 0L || bars <= 0) return emptyList()
        val used = current + if (inBucket > 0L) 1 else 0
        return List(bars) { bar ->
            val from = (used.toLong() * bar / bars).toInt()
            val to = maxOf(from + 1, (used.toLong() * (bar + 1) / bars).toInt()).coerceAtMost(used)
            var sum = 0.0
            var count = 0L
            for (k in from until to) {
                sum += energy[k]
                count += if (k == current) inBucket else span
            }
            if (count <= 0L) 0f else kotlin.math.sqrt(sum / count).toFloat()
        }
    }

    /** Pairs merged, span doubled: room for as much again. */
    private fun compact() {
        val half = CAPACITY / 2
        for (k in 0 until half) {
            fine[k] = maxOf(fine[2 * k], fine[2 * k + 1])
            energy[k] = energy[2 * k] + energy[2 * k + 1]
        }
        fine.fill(0f, half, CAPACITY)
        energy.fill(0.0, half, CAPACITY)
        span *= 2
        current = half
    }

    companion object {
        /** About fifty milliseconds a bucket on a five-minute song. */
        const val CAPACITY = 8192

        private const val INITIAL_SPAN = 256L
    }
}
