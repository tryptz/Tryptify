package tf.monochrome.android.audio.resample

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Arbitrary-ratio resampler: consumes [ratio] input frames per output frame,
 * which plays the stream faster and higher together — the vinyl-style speed
 * change, where pitch rides the tempo.
 *
 * This exists to take that case away from Media3's Sonic. Sonic splits the job
 * as `s = speed / pitch` and `r = rate * pitch`: when pitch tracks speed, `s`
 * is exactly 1.0, lands in Sonic's `0.99999..1.00001` dead zone, and no
 * time-stretch runs at all — the whole effect is `adjustRate`, whose kernel
 * blends the two samples either side of the read position linearly. That is a
 * first-order interpolator with a `sinc^2` response and almost no image
 * rejection. Here the same job is done with a windowed-sinc polyphase kernel
 * whose cutoff follows the ratio, so speeding up band-limits before decimating
 * instead of folding the top octave back into the audible range.
 *
 * Time-stretching (preserve-pitch on) is a different problem and is left to
 * Sonic — see [tf.monochrome.android.audio.resample.TryptifyAudioProcessorChain],
 * which routes each mode to whichever of the two can do it.
 *
 * Sample rate and channel count are unchanged; only the frame *count* moves,
 * which is exactly the contract Sonic already has with the sink.
 */
@Singleton
@OptIn(UnstableApi::class)
class VariRateAudioProcessor @Inject constructor() : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    /** What [getOutput] hands on: [buffer], or EMPTY once it has been taken. */
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /**
     * The output's memory, kept across blocks. [getOutput] has to leave
     * [outputBuffer] empty, so the memory cannot live there: when it did,
     * every block found a capacity of 0 and allocated a new direct buffer.
     */
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    /** The end of stream's lookahead, still to be pushed out (see [queueEndOfStream]). */
    private var tailPending = false

    /**
     * A designed kernel, swapped in atomically. Keyed by [cutoffScale], the
     * one thing about the ratio the design depends on: every ratio at or
     * below 1 has the same kernel.
     */
    private class Table(val cutoffScale: Double, val kernel: SincKernel)

    /**
     * The last few tables designed, so going back to a speed used a moment ago
     * (toggling a preset, stepping the speed down and up again) costs a lookup
     * instead of a new 257 x 64 design on the caller's thread, which for the
     * player's own speed changes is the playback thread.
     */
    private val recentTables = AtomicReferenceArray<Table>(KERNEL_CACHE)
    private val nextRecent = AtomicInteger(0)

    private val tableRef = AtomicReference(tableFor(cutoffScale(1.0), 1.0))

    @Volatile private var ratio: Float = 1f

    // Audio-thread state.
    private var active: Table = tableRef.get()
    // One history and one work buffer per channel. Sized when the channel
    // count or block size grows, never per block.
    private var hist: Array<FloatArray> = emptyArray()
    private var work: Array<FloatArray> = emptyArray()
    private var pos = 0.0

    /**
     * One output frame's taps: the polyphase row for its read position,
     * blended once and then run over every channel.
     */
    private var taps = FloatArray(0)

    /**
     * Sets input frames consumed per output frame. 1.0 is a no-op and makes the
     * processor inactive.
     *
     * Designs the kernel here, on the caller's thread: it is a user-driven
     * control change, and the alternative is either designing sinc tables on
     * the audio thread or running with a cutoff that does not match the ratio.
     * A kernel designed recently is reused rather than designed again.
     */
    fun setRatio(newRatio: Float) {
        if (!newRatio.isFinite() || newRatio <= 0f) return
        val clamped = newRatio.coerceIn(MIN_RATIO, MAX_RATIO)
        if (clamped == ratio) return
        ratio = clamped
        val r = clamped.toDouble()
        val scale = cutoffScale(r)
        if (tableRef.get().cutoffScale != scale) tableRef.set(tableFor(scale, r))
    }

    /** A recent table with [scale], or a new one designed for [ratio] and remembered. */
    private fun tableFor(scale: Double, ratio: Double): Table {
        for (i in 0 until KERNEL_CACHE) {
            val t = recentTables.get(i)
            if (t != null && t.cutoffScale == scale) return t
        }
        val t = Table(scale, SincKernel.design(ratio, HALF_WIDTH, PHASES))
        recentTables.set(Math.floorMod(nextRecent.getAndIncrement(), KERNEL_CACHE), t)
        return t
    }

    fun getRatio(): Float = ratio

    // ── AudioProcessor ───────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Any channel count up to a 7.1.4 Atmos bed and beyond: the filter is
        // per channel. This used to stop at stereo, and a wider stream passed
        // through at 1.00x while the sink's clock ran at the new speed —
        // spatial-audio and unfolded Atmos beds drifted off their own
        // progress bar.
        if (inputAudioFormat.channelCount !in 1..MAX_CHANNELS) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        (pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET) &&
            kotlin.math.abs(ratio - 1f) >= RATIO_DEADZONE

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * channels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        val table = tableRef.get()
        if (table !== active) {
            // The kernel changed; the history is still valid audio, so only the
            // filter swaps. Read position carries over untouched.
            active = table
        }
        val halfWidth = active.kernel.halfWidth
        val histLen = 2 * halfWidth
        ensureBuffers(histLen, numFrames, channels)

        // work = carried history followed by this block.
        for (c in 0 until channels) System.arraycopy(hist[c], 0, work[c], 0, histLen)
        readInterleaved(inputBuffer, numFrames, channels, encoding, histLen)
        inputBuffer.position(inputBuffer.position() + numFrames * frameSize)

        val total = histLen + numFrames
        val maxOut = ceil(numFrames / ratio.toDouble()).toInt() + 2
        val outFrameSize = bytesPerSample * channels
        ensureOutput(maxOut * outFrameSize)

        var produced = 0
        val step = ratio.toDouble()
        val last = total - 1 - halfWidth
        val kernel = active.kernel
        val width = 2 * halfWidth
        if (taps.size != width) taps = FloatArray(width)
        while (floor(pos) <= last && produced < maxOut) {
            val i = floor(pos).toInt()
            val frac = pos - i
            // The taps depend on the read position alone, so they are worked
            // out once a frame rather than once a channel.
            blendTaps(kernel, frac, taps)
            val base = i - halfWidth + 1
            for (c in 0 until channels) {
                writeSample(produced * channels + c, encoding, convolve(work[c], base, taps, width))
            }
            produced++
            pos += step
        }

        // Slide the window: keep the last histLen frames, and move the read
        // position into their coordinates.
        for (c in 0 until channels) System.arraycopy(work[c], total - histLen, hist[c], 0, histLen)
        pos -= numFrames

        outputBuffer.position(0)
        outputBuffer.limit(produced * outFrameSize)
    }

    override fun getOutput(): ByteBuffer {
        if (tailPending && outputBuffer === AudioProcessor.EMPTY_BUFFER) {
            tailPending = false
            queueTail()
        }
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean =
        inputEnded && !tailPending && outputBuffer === AudioProcessor.EMPTY_BUFFER

    /**
     * Push the filter's own latency out as zeros so the last few milliseconds
     * of the track are not swallowed by the tail.
     *
     * Not here, though: in [getOutput], once the last block has been taken.
     * Media3's pipeline tells a stage the stream ended without checking that
     * the stage's previous output was read, and the output buffer is reused,
     * so filtering the tail here could overwrite a block still waiting to be
     * played.
     */
    override fun queueEndOfStream() {
        if (!inputEnded && inputFormat != AudioFormat.NOT_SET && isActive) tailPending = true
        inputEnded = true
    }

    private fun queueTail() {
        val halfWidth = active.kernel.halfWidth
        val channels = inputFormat.channelCount
        val bytesPerSample =
            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val tail = ByteBuffer
            .allocateDirect(halfWidth * bytesPerSample * channels)
            .order(ByteOrder.nativeOrder())
        // allocateDirect zero-fills, so this is silence of exactly the
        // filter's lookahead.
        tail.position(0).limit(halfWidth * bytesPerSample * channels)
        queueInput(tail)
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        tailPending = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
        }
        active = tableRef.get()
        val histLen = 2 * active.kernel.halfWidth
        val channels = inputFormat.channelCount.coerceAtLeast(1)
        hist = Array(channels) { FloatArray(histLen) }
        // Start reading at the first real input frame; the zeroed history in
        // front of it is the filter's ramp-in.
        pos = histLen.toDouble()
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        hist = emptyArray()
        work = emptyArray()
        buffer = AudioProcessor.EMPTY_BUFFER
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * The polyphase row for [frac] into [out], linearly interpolated between
     * the two nearest phases so the fraction is not quantised to the table's
     * resolution.
     */
    private fun blendTaps(kernel: SincKernel, frac: Double, out: FloatArray) {
        val phasePos = frac * kernel.phases
        val p = phasePos.toInt().coerceIn(0, kernel.phases - 1)
        val blend = (phasePos - p).toFloat()
        val a = kernel.taps[p]
        val b = kernel.taps[p + 1]
        for (k in out.indices) out[k] = a[k] + (b[k] - a[k]) * blend
    }

    /**
     * One output sample: [width] input frames from [base] against [taps], in
     * the same order the per-channel blend used to sum them, so the result
     * is the same to the bit.
     */
    private fun convolve(work: FloatArray, base: Int, taps: FloatArray, width: Int): Float {
        var acc = 0f
        for (k in 0 until width) acc += work[base + k] * taps[k]
        return acc
    }

    private fun ensureBuffers(histLen: Int, numFrames: Int, channels: Int) {
        val need = histLen + numFrames
        if (work.size != channels || work[0].size < need) {
            work = Array(channels) { FloatArray(need) }
        }
        if (hist.size != channels || hist[0].size != histLen) {
            hist = Array(channels) { FloatArray(histLen) }
            pos = histLen.toDouble()
        }
    }

    private fun ensureOutput(bytes: Int) {
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
    }

    private fun readInterleaved(
        buf: ByteBuffer,
        numFrames: Int,
        channels: Int,
        encoding: Int,
        offset: Int,
    ) {
        val start = buf.position()
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (n in 0 until numFrames) {
                val o = start + n * channels * 4
                for (c in 0 until channels) work[c][offset + n] = buf.getFloat(o + c * 4)
            }
        } else {
            for (n in 0 until numFrames) {
                val o = start + n * channels * 2
                for (c in 0 until channels) {
                    work[c][offset + n] = buf.getShort(o + c * 2).toFloat() / 32768f
                }
            }
        }
    }

    private fun writeSample(sampleIndex: Int, encoding: Int, value: Float) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            outputBuffer.putFloat(sampleIndex * 4, value)
        } else {
            outputBuffer.putShort(sampleIndex * 2, toPcm16(value))
        }
    }

    private fun toPcm16(v: Float): Short =
        (v * 32768f).toInt().coerceIn(-32768, 32767).toShort()

    private companion object {
        const val HALF_WIDTH = 32
        const val PHASES = 256
        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 20f
        const val MAX_CHANNELS = 16

        /** Matches Sonic's own tolerance for "close enough to 1 to skip". */
        const val RATIO_DEADZONE = 1e-4f

        /** Kernels remembered by [tableFor]: about 66 KB each. */
        const val KERNEL_CACHE = 4

        /**
         * What a kernel's design takes from the ratio: the cutoff comes down
         * by it when decimating and stays put otherwise ([SincKernel.design]).
         */
        fun cutoffScale(ratio: Double): Double = kotlin.math.min(1.0, 1.0 / ratio.coerceAtLeast(1e-6))
    }
}
