package tf.monochrome.android.audio.input

/**
 * Single-producer / single-consumer float ring for the stereo line-in path.
 *
 * The producer is the [StereoInputEngine] capture thread draining `AudioRecord`;
 * the consumer is the audio thread inside `MixBusProcessor.queueInput`. Neither
 * side locks and neither side allocates once constructed.
 *
 * Thread-safety rests on each side owning exactly one cursor: the writer only
 * ever stores [writePos], the reader only ever stores [readPos], and both are
 * `@Volatile` monotonic counters (never wrapped) so a stale read can only ever
 * under-estimate how much data is available — which degrades to a zero-fill,
 * not to corruption. The array index is the counter masked by [capacity] - 1,
 * which is why capacity is forced to a power of two.
 *
 * ## Clock drift
 *
 * The input device (a USB interface) and the output device (whatever the
 * `AudioTrack` is on) run off independent crystals, so the two clocks
 * *will* diverge — a few frames per minute is normal, and it accumulates.
 * Left alone, a fast input clock grows the queue until it overflows and a slow
 * one starves it. Both are handled on the **reader** side in [read]: it trims
 * back to [targetFill] whenever the backlog exceeds [maxFill], and zero-fills
 * on starvation. Doing the correction there rather than in the writer is what
 * keeps the SPSC cursor discipline intact, and it also bounds monitoring
 * latency to a small multiple of the DSP block size instead of the ring's full
 * capacity.
 *
 * A drift correction costs one discontinuity in the input signal. At realistic
 * drift rates that is minutes apart, and it is strictly better than the
 * alternative of latency creeping upward for the whole listening session.
 */
internal class InputRingBuffer(capacityFrames: Int = DEFAULT_CAPACITY_FRAMES) {

    /** Power-of-two frame capacity, so index maths is a mask rather than a modulo. */
    val capacity: Int = nextPowerOfTwo(capacityFrames.coerceIn(MIN_CAPACITY_FRAMES, MAX_CAPACITY_FRAMES))

    private val mask = capacity - 1
    private val bufL = FloatArray(capacity)
    private val bufR = FloatArray(capacity)

    @Volatile private var writePos: Long = 0L
    @Volatile private var readPos: Long = 0L

    /** Blocks the consumer had to zero-fill because the producer fell behind. */
    @Volatile var underruns: Long = 0L
        private set

    /** Blocks the producer dropped because the consumer was not draining. */
    @Volatile var overruns: Long = 0L
        private set

    /** Frames discarded by drift trimming. Distinct from [overruns] — this one is expected. */
    @Volatile var driftTrims: Long = 0L
        private set

    /** Frames currently queued. Safe to call from either side (advisory on both). */
    fun available(): Int {
        val fill = writePos - readPos
        return if (fill > capacity) capacity else fill.toInt()
    }

    /** Drops everything queued. Call from the consumer side while the producer is stopped. */
    fun clear() {
        readPos = writePos
        underruns = 0L
        overruns = 0L
        driftTrims = 0L
    }

    /**
     * Producer: append [frames] frames of interleaved stereo from [src].
     *
     * When [monoSource] is true, [src] holds one sample per frame and it is
     * duplicated to both channels — the fallback for input hardware that only
     * reports a mono capture configuration.
     *
     * Returns false and counts an overrun if the block does not fit. Dropping
     * the incoming block (rather than overwriting queued audio) keeps the
     * writer off the reader's cursor.
     */
    fun write(src: FloatArray, frames: Int, monoSource: Boolean): Boolean {
        if (frames <= 0) return true
        val needed = if (monoSource) frames else frames * 2
        if (needed > src.size) return false

        val w = writePos
        if (w - readPos + frames > capacity) {
            overruns++
            return false
        }

        if (monoSource) {
            for (i in 0 until frames) {
                val idx = ((w + i) and mask.toLong()).toInt()
                val s = src[i]
                bufL[idx] = s
                bufR[idx] = s
            }
        } else {
            for (i in 0 until frames) {
                val idx = ((w + i) and mask.toLong()).toInt()
                bufL[idx] = src[i * 2]
                bufR[idx] = src[i * 2 + 1]
            }
        }
        // Publish only after the samples are in place.
        writePos = w + frames
        return true
    }

    /**
     * Consumer: fill [outL]/[outR] with [frames] frames, applying [gain].
     *
     * Always writes exactly [frames] samples to each array — a shortfall is
     * zero-filled rather than left stale, so an underrun is silence instead of
     * a repeated buffer. Returns the number of real captured frames written.
     */
    fun read(outL: FloatArray, outR: FloatArray, frames: Int, gain: Float = 1f): Int {
        if (frames <= 0) return 0
        if (outL.size < frames || outR.size < frames) return 0

        trimDrift(frames)

        var r = readPos
        val fill = (writePos - r).coerceIn(0L, capacity.toLong()).toInt()
        val n = minOf(fill, frames)

        for (i in 0 until n) {
            val idx = ((r + i) and mask.toLong()).toInt()
            outL[i] = bufL[idx] * gain
            outR[i] = bufR[idx] * gain
        }
        if (n < frames) {
            java.util.Arrays.fill(outL, n, frames, 0f)
            java.util.Arrays.fill(outR, n, frames, 0f)
            underruns++
        }
        r += n
        readPos = r
        return n
    }

    /**
     * Discard the oldest frames when the backlog has crept past [maxFill],
     * leaving [targetFill] queued. Both bounds derive from the consumer's block
     * size, so the latency this buffer contributes stays proportional to the
     * DSP block the user picked rather than to the ring's capacity.
     */
    private fun trimDrift(frames: Int) {
        val maxFill = (frames * MAX_FILL_BLOCKS).coerceAtMost(capacity)
        val fill = (writePos - readPos)
        if (fill <= maxFill) return
        val target = (frames * TARGET_FILL_BLOCKS).toLong().coerceAtMost(maxFill.toLong())
        val drop = fill - target
        readPos += drop
        driftTrims += drop
    }

    companion object {
        /**
         * 64 Ki frames ≈ 1.4 s at 48 kHz across both channels (512 KB). Only a
         * fraction is ever queued — [trimDrift] holds the working set near
         * [TARGET_FILL_BLOCKS] blocks — but the headroom means the largest
         * selectable DSP block (16384) still gets its full drift window.
         */
        const val DEFAULT_CAPACITY_FRAMES = 65536
        const val MIN_CAPACITY_FRAMES = 256
        const val MAX_CAPACITY_FRAMES = 1 shl 20

        /** Backlog ceiling before the reader trims, in consumer blocks. */
        const val MAX_FILL_BLOCKS = 4

        /** Backlog the reader trims back to, in consumer blocks. */
        const val TARGET_FILL_BLOCKS = 2

        fun nextPowerOfTwo(v: Int): Int {
            var n = 1
            while (n < v) n = n shl 1
            return n
        }
    }
}
