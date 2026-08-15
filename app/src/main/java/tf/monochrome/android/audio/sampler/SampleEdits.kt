// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import kotlin.math.abs
import kotlin.math.max

/**
 * The destructive edits the Sampler screen offers after a capture: trim, gain,
 * normalize, fades, reverse.
 *
 * Pure functions over float arrays, with no Android and no I/O, so
 * `SampleEditsTest` can assert their results directly. Each returns a new
 * array rather than mutating — an edit chain is short and the arrays are
 * seconds long, so the clarity is worth far more than the copies.
 *
 * This is deliberately not a waveform editor. The point of the Sampler screen
 * is to get a hit out of a track and into a pattern in a few seconds; anything
 * that needs more than trim / level / fade belongs in a tool built for it.
 */
object SampleEdits {

    /** Peak below which a buffer is treated as silence rather than normalized. */
    private const val SILENCE_FLOOR = 1e-5f

    /** Largest peak normalize will target, leaving a little headroom. */
    const val NORMALIZE_CEILING = 0.98f

    data class Buffer(
        val left: FloatArray,
        val right: FloatArray?,
        val sampleRate: Int,
    ) {
        val frames: Int get() = left.size

        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else frames * 1000L / sampleRate

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Keeps `[startFrame, endFrame)`.
     *
     * Both ends are clamped into range and an inverted or empty selection
     * returns the buffer untouched — a trim handle dragged past its partner
     * should do nothing, not produce a zero-length sample the engine then has
     * to defend against.
     */
    fun trim(buffer: Buffer, startFrame: Int, endFrame: Int): Buffer {
        val start = startFrame.coerceIn(0, buffer.frames)
        val end = endFrame.coerceIn(0, buffer.frames)
        if (end - start < MIN_FRAMES) return buffer
        return Buffer(
            buffer.left.copyOfRange(start, end),
            buffer.right?.copyOfRange(start, end),
            buffer.sampleRate,
        )
    }

    /** Applies a linear gain. [db] of 0 returns the buffer unchanged. */
    fun gainDb(buffer: Buffer, db: Float): Buffer {
        if (abs(db) < 1e-4f) return buffer
        val factor = Math.pow(10.0, db / 20.0).toFloat()
        return scale(buffer, factor)
    }

    fun scale(buffer: Buffer, factor: Float): Buffer = Buffer(
        FloatArray(buffer.frames) { buffer.left[it] * factor },
        buffer.right?.let { r -> FloatArray(buffer.frames) { r[it] * factor } },
        buffer.sampleRate,
    )

    /** Highest absolute sample across both channels. */
    fun peak(buffer: Buffer): Float {
        var peak = 0f
        for (v in buffer.left) peak = max(peak, abs(v))
        buffer.right?.let { for (v in it) peak = max(peak, abs(v)) }
        return peak
    }

    /**
     * Scales so the loudest sample sits at [NORMALIZE_CEILING].
     *
     * A silent or near-silent buffer is returned untouched: normalizing it
     * would multiply the noise floor by an enormous factor and hand the user
     * a hiss where they expected a sound.
     */
    fun normalize(buffer: Buffer): Buffer {
        val peak = peak(buffer)
        if (peak < SILENCE_FLOOR) return buffer
        return scale(buffer, NORMALIZE_CEILING / peak)
    }

    /**
     * Linear fade in over [ms].
     *
     * Even a couple of milliseconds matters here: a trim that lands mid-cycle
     * starts on a step discontinuity, which is the click that makes a sampled
     * hit sound broken. The Sampler screen applies a short one by default for
     * exactly that reason.
     */
    fun fadeIn(buffer: Buffer, ms: Float): Buffer {
        val frames = msToFrames(ms, buffer.sampleRate).coerceAtMost(buffer.frames)
        if (frames <= 0) return buffer
        val left = buffer.left.copyOf()
        val right = buffer.right?.copyOf()
        for (i in 0 until frames) {
            val g = i.toFloat() / frames
            left[i] *= g
            right?.set(i, right[i] * g)
        }
        return Buffer(left, right, buffer.sampleRate)
    }

    /** Linear fade out over [ms]. */
    fun fadeOut(buffer: Buffer, ms: Float): Buffer {
        val frames = msToFrames(ms, buffer.sampleRate).coerceAtMost(buffer.frames)
        if (frames <= 0) return buffer
        val left = buffer.left.copyOf()
        val right = buffer.right?.copyOf()
        val start = buffer.frames - frames
        for (i in 0 until frames) {
            val g = 1f - i.toFloat() / frames
            left[start + i] *= g
            right?.set(start + i, right[start + i] * g)
        }
        return Buffer(left, right, buffer.sampleRate)
    }

    fun reverse(buffer: Buffer): Buffer = Buffer(
        buffer.left.reversedArray(),
        buffer.right?.reversedArray(),
        buffer.sampleRate,
    )

    /** Sums to mono — halves the file and is usually right for a drum hit. */
    fun toMono(buffer: Buffer): Buffer {
        val right = buffer.right ?: return buffer
        return Buffer(
            FloatArray(buffer.frames) { (buffer.left[it] + right[it]) * 0.5f },
            null,
            buffer.sampleRate,
        )
    }

    /**
     * Finds the first frame that rises above [threshold], for the "snap the
     * start to the transient" the trim handles do on a fresh capture. Returns
     * 0 when nothing crosses it, which leaves the sample as recorded.
     */
    fun firstOnset(buffer: Buffer, threshold: Float = 0.02f): Int {
        for (i in 0 until buffer.frames) {
            val l = abs(buffer.left[i])
            val r = buffer.right?.let { abs(it[i]) } ?: 0f
            if (max(l, r) >= threshold) return i
        }
        return 0
    }

    /**
     * Downsamples to [buckets] min/max pairs for drawing.
     *
     * Peaks, not averages: an average-based waveform of a percussive hit is a
     * flat smear that tells the user nothing about where the transient is,
     * which is the one thing they are looking at it to find.
     *
     * The result is `2 × buckets` floats, alternating min and max.
     */
    fun peaks(buffer: Buffer, buckets: Int = 512): FloatArray {
        val count = buckets.coerceIn(8, 4096)
        val out = FloatArray(count * 2)
        if (buffer.frames <= 0) return out

        val perBucket = max(1, buffer.frames / count)
        for (b in 0 until count) {
            val start = b * perBucket
            if (start >= buffer.frames) break
            val end = minOf(start + perBucket, buffer.frames)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in start until end) {
                val l = buffer.left[i]
                if (l < lo) lo = l
                if (l > hi) hi = l
                buffer.right?.let {
                    val r = it[i]
                    if (r < lo) lo = r
                    if (r > hi) hi = r
                }
            }
            out[b * 2] = if (lo == Float.MAX_VALUE) 0f else lo
            out[b * 2 + 1] = if (hi == -Float.MAX_VALUE) 0f else hi
        }
        return out
    }

    private fun msToFrames(ms: Float, sampleRate: Int): Int =
        if (ms <= 0f || sampleRate <= 0) 0 else (ms * 0.001f * sampleRate).toInt()

    /** Shortest sample the engine will play — below this a voice cannot interpolate. */
    const val MIN_FRAMES = 8
}
