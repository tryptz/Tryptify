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
 * The operations come in two shapes. The whole-buffer ones — [normalize],
 * [reverse], [toMono] — are the fast path the capture screen uses. The range
 * ones — [deleteRange], [silenceRange], [gainRange] and friends — are what the
 * wave editor drives from its selection, and they all share [clampRange] so
 * that "no selection", "inverted selection" and "selection past the end" mean
 * the same thing everywhere rather than each op inventing its own answer.
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

    // ── selection ───────────────────────────────────────────────────────

    /**
     * Normalises a selection into a valid, ordered, in-bounds range.
     *
     * Every range operation starts here so they all agree on the awkward
     * cases: handles dragged past each other are swapped rather than treated
     * as empty, and anything outside the buffer is clipped to it. An empty
     * result means "nothing selected", and every op below returns the buffer
     * untouched for it — the editor should never destroy audio because a
     * selection collapsed.
     */
    fun clampRange(buffer: Buffer, from: Int, to: Int): IntRange {
        val lo = minOf(from, to).coerceIn(0, buffer.frames)
        val hi = maxOf(from, to).coerceIn(0, buffer.frames)
        return lo until hi
    }

    /** Removes the selection and closes the gap. */
    fun deleteRange(buffer: Buffer, from: Int, to: Int): Buffer {
        val range = clampRange(buffer, from, to)
        if (range.isEmpty()) return buffer
        // Refuse to leave less than a playable sample behind. Deleting
        // everything is what "clear" would mean, and this operation is not it.
        if (buffer.frames - (range.last + 1 - range.first) < MIN_FRAMES) return buffer

        val keep = buffer.frames - (range.last + 1 - range.first)
        val left = FloatArray(keep)
        val right = if (buffer.right != null) FloatArray(keep) else null
        var out = 0
        for (i in 0 until buffer.frames) {
            if (i in range) continue
            left[out] = buffer.left[i]
            right?.set(out, buffer.right!![i])
            out += 1
        }
        return Buffer(left, right, buffer.sampleRate)
    }

    /** Zeroes the selection, keeping the buffer's length. */
    fun silenceRange(buffer: Buffer, from: Int, to: Int): Buffer =
        mapRange(buffer, from, to) { _, _ -> 0f }

    /** Applies [db] to the selection only. */
    fun gainRange(buffer: Buffer, from: Int, to: Int, db: Float): Buffer {
        if (abs(db) < 1e-4f) return buffer
        val factor = Math.pow(10.0, db / 20.0).toFloat()
        return mapRange(buffer, from, to) { value, _ -> value * factor }
    }

    /** Ramps the selection up from silence across its own length. */
    fun fadeInRange(buffer: Buffer, from: Int, to: Int): Buffer {
        val range = clampRange(buffer, from, to)
        val length = range.last + 1 - range.first
        if (range.isEmpty() || length <= 1) return buffer
        return mapRange(buffer, from, to) { value, index -> value * (index.toFloat() / (length - 1)) }
    }

    /** Ramps the selection down to silence across its own length. */
    fun fadeOutRange(buffer: Buffer, from: Int, to: Int): Buffer {
        val range = clampRange(buffer, from, to)
        val length = range.last + 1 - range.first
        if (range.isEmpty() || length <= 1) return buffer
        return mapRange(buffer, from, to) { value, index ->
            value * (1f - index.toFloat() / (length - 1))
        }
    }

    /** Reverses the selection in place, leaving the rest of the buffer alone. */
    fun reverseRange(buffer: Buffer, from: Int, to: Int): Buffer {
        val range = clampRange(buffer, from, to)
        if (range.isEmpty()) return buffer
        val left = buffer.left.copyOf()
        val right = buffer.right?.copyOf()
        var lo = range.first
        var hi = range.last
        while (lo < hi) {
            val l = left[lo]; left[lo] = left[hi]; left[hi] = l
            right?.let { val r = it[lo]; it[lo] = it[hi]; it[hi] = r }
            lo += 1
            hi -= 1
        }
        return Buffer(left, right, buffer.sampleRate)
    }

    /** Scales the selection so *its* peak reaches the ceiling. */
    fun normalizeRange(buffer: Buffer, from: Int, to: Int): Buffer {
        val range = clampRange(buffer, from, to)
        if (range.isEmpty()) return buffer
        var peak = 0f
        for (i in range) {
            peak = max(peak, abs(buffer.left[i]))
            buffer.right?.let { peak = max(peak, abs(it[i])) }
        }
        if (peak < SILENCE_FLOOR) return buffer
        val factor = NORMALIZE_CEILING / peak
        return mapRange(buffer, from, to) { value, _ -> value * factor }
    }

    /** Peak of the selection, for the editor's readout. */
    fun peakOfRange(buffer: Buffer, from: Int, to: Int): Float {
        val range = clampRange(buffer, from, to)
        if (range.isEmpty()) return 0f
        var peak = 0f
        for (i in range) {
            peak = max(peak, abs(buffer.left[i]))
            buffer.right?.let { peak = max(peak, abs(it[i])) }
        }
        return peak
    }

    /**
     * Applies [transform] across a selection. `index` counts from the start of
     * the selection, which is what lets the fades be written as one-liners.
     */
    private inline fun mapRange(
        buffer: Buffer,
        from: Int,
        to: Int,
        transform: (value: Float, index: Int) -> Float,
    ): Buffer {
        val range = clampRange(buffer, from, to)
        if (range.isEmpty()) return buffer
        val left = buffer.left.copyOf()
        val right = buffer.right?.copyOf()
        for (i in range) {
            val offset = i - range.first
            left[i] = transform(left[i], offset)
            right?.set(i, transform(right[i], offset))
        }
        return Buffer(left, right, buffer.sampleRate)
    }

    // ── edges ───────────────────────────────────────────────────────────

    /**
     * Moves [frame] to the nearest upward zero crossing within [search] frames.
     *
     * The reason to bother: a selection boundary that lands mid-waveform is a
     * step discontinuity, and a looped sample cut that way clicks once per
     * repeat. Snapping the edit points to a zero crossing removes the click at
     * source instead of hiding it under a fade. Returns [frame] unchanged when
     * nothing suitable is close enough — a wrong snap is worse than none.
     */
    fun snapToZeroCrossing(buffer: Buffer, frame: Int, search: Int = 512): Int {
        if (buffer.frames < 2) return frame
        val origin = frame.coerceIn(0, buffer.frames - 1)
        val reach = search.coerceAtLeast(1)
        for (distance in 0..reach) {
            // Outward from the requested point, so the closest crossing wins
            // and the handle barely appears to move.
            for (candidate in intArrayOf(origin - distance, origin + distance)) {
                if (candidate < 0 || candidate >= buffer.frames - 1) continue
                val here = buffer.left[candidate]
                val next = buffer.left[candidate + 1]
                if (here <= 0f && next > 0f) return candidate
            }
        }
        return origin
    }

    /**
     * Crops leading and trailing audio quieter than [threshold].
     *
     * A little padding is kept in front of the first loud sample: cutting
     * exactly on the transient shaves the attack, which is the part of a
     * percussive hit that carries its character.
     */
    fun trimSilence(buffer: Buffer, threshold: Float = 0.01f, padMs: Float = 2f): Buffer {
        if (buffer.frames < MIN_FRAMES) return buffer
        var first = -1
        var last = -1
        for (i in 0 until buffer.frames) {
            val level = max(abs(buffer.left[i]), buffer.right?.let { abs(it[i]) } ?: 0f)
            if (level >= threshold) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return buffer   // silent throughout: nothing to trim to
        val pad = msToFrames(padMs, buffer.sampleRate)
        val start = (first - pad).coerceAtLeast(0)
        val end = (last + 1 + pad).coerceAtMost(buffer.frames)
        return trim(buffer, start, end)
    }

    /**
     * Min/max pairs for one window of the buffer, for a zoomed waveform.
     *
     * [peaks] draws the whole thing; this draws whatever the editor is looking
     * at, so zooming in costs the same as zooming out. When the window is
     * shorter than the bucket count the pairs degenerate to individual samples,
     * which is exactly right — at that zoom the user is looking at the wave
     * itself.
     */
    fun peaksOfRange(buffer: Buffer, from: Int, to: Int, buckets: Int = 512): FloatArray {
        val count = buckets.coerceIn(8, 4096)
        val out = FloatArray(count * 2)
        val start = from.coerceIn(0, buffer.frames)
        val end = to.coerceIn(start, buffer.frames)
        val span = end - start
        if (span <= 0) return out

        for (b in 0 until count) {
            val bucketStart = start + (span.toLong() * b / count).toInt()
            val bucketEnd = (start + (span.toLong() * (b + 1) / count).toInt())
                .coerceAtMost(end)
                .coerceAtLeast(bucketStart + 1)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in bucketStart until minOf(bucketEnd, buffer.frames)) {
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
}
