package tf.monochrome.android.audio.resample

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
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
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    /** Designed off the audio thread, swapped in atomically. */
    private class Table(val ratio: Double, val kernel: SincKernel)

    private val tableRef = AtomicReference(Table(1.0, SincKernel.design(1.0)))

    @Volatile private var ratio: Float = 1f

    // Audio-thread state.
    private var active: Table = tableRef.get()
    private var histL = FloatArray(0)
    private var histR = FloatArray(0)
    private var workL = FloatArray(0)
    private var workR = FloatArray(0)
    private var pos = 0.0

    /**
     * Sets input frames consumed per output frame. 1.0 is a no-op and makes the
     * processor inactive.
     *
     * Designs the kernel here, on the caller's thread: it is a user-driven
     * control change, and the alternative is either designing sinc tables on
     * the audio thread or running with a cutoff that does not match the ratio.
     */
    fun setRatio(newRatio: Float) {
        if (!newRatio.isFinite() || newRatio <= 0f) return
        val clamped = newRatio.coerceIn(MIN_RATIO, MAX_RATIO)
        if (clamped == ratio) return
        ratio = clamped
        val r = clamped.toDouble()
        if (tableRef.get().ratio != r) {
            tableRef.set(Table(r, SincKernel.design(r, HALF_WIDTH, PHASES)))
        }
    }

    fun getRatio(): Float = ratio

    // ── AudioProcessor ───────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            // Anything wider passes through untouched rather than failing
            // playback; the sink's own path still applies the speed.
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
        System.arraycopy(histL, 0, workL, 0, histLen)
        if (channels == 2) System.arraycopy(histR, 0, workR, 0, histLen)
        readInterleaved(inputBuffer, numFrames, channels, encoding, histLen)
        inputBuffer.position(inputBuffer.position() + numFrames * frameSize)

        val total = histLen + numFrames
        val maxOut = ceil(numFrames / ratio.toDouble()).toInt() + 2
        val outFrameSize = bytesPerSample * channels
        ensureOutput(maxOut * outFrameSize)

        var produced = 0
        val step = ratio.toDouble()
        val last = total - 1 - halfWidth
        while (floor(pos) <= last && produced < maxOut) {
            val i = floor(pos).toInt()
            val frac = pos - i
            val l = filter(workL, i, frac, active.kernel)
            val r = if (channels == 2) filter(workR, i, frac, active.kernel) else l
            writeFrame(produced, channels, encoding, l, r)
            produced++
            pos += step
        }

        // Slide the window: keep the last histLen frames, and move the read
        // position into their coordinates.
        System.arraycopy(workL, total - histLen, histL, 0, histLen)
        if (channels == 2) System.arraycopy(workR, total - histLen, histR, 0, histLen)
        pos -= numFrames

        outputBuffer.position(0)
        outputBuffer.limit(produced * outFrameSize)
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        // Push the filter's own latency out as zeros so the last few
        // milliseconds of the track are not swallowed by the tail.
        if (!inputEnded && inputFormat != AudioFormat.NOT_SET && isActive) {
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
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
        }
        active = tableRef.get()
        val histLen = 2 * active.kernel.halfWidth
        histL = FloatArray(histLen)
        histR = FloatArray(histLen)
        // Start reading at the first real input frame; the zeroed history in
        // front of it is the filter's ramp-in.
        pos = histLen.toDouble()
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        histL = FloatArray(0)
        histR = FloatArray(0)
        workL = FloatArray(0)
        workR = FloatArray(0)
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * One output sample: the polyphase row for [frac], linearly interpolated
     * between the two nearest phases so the fraction is not quantised to the
     * table's resolution.
     */
    private fun filter(work: FloatArray, i: Int, frac: Double, kernel: SincKernel): Float {
        val width = 2 * kernel.halfWidth
        val base = i - kernel.halfWidth + 1
        val phasePos = frac * kernel.phases
        val p = phasePos.toInt().coerceIn(0, kernel.phases - 1)
        val blend = (phasePos - p).toFloat()
        val a = kernel.taps[p]
        val b = kernel.taps[p + 1]
        var acc = 0f
        for (k in 0 until width) {
            val tap = a[k] + (b[k] - a[k]) * blend
            acc += work[base + k] * tap
        }
        return acc
    }

    private fun ensureBuffers(histLen: Int, numFrames: Int, channels: Int) {
        val need = histLen + numFrames
        if (workL.size < need) {
            workL = FloatArray(need)
            workR = FloatArray(need)
        }
        if (histL.size != histLen) {
            histL = FloatArray(histLen)
            histR = FloatArray(histLen)
            pos = histLen.toDouble()
        }
        if (channels == 1 && histR.size != histLen) histR = FloatArray(histLen)
    }

    private fun ensureOutput(bytes: Int) {
        if (outputBuffer.capacity() < bytes) {
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
    }

    private fun readInterleaved(
        buf: ByteBuffer,
        numFrames: Int,
        channels: Int,
        encoding: Int,
        offset: Int,
    ) {
        val start = buf.position()
        if (channels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (n in 0 until numFrames) workL[offset + n] = buf.getFloat(start + n * 4)
            } else {
                for (n in 0 until numFrames) {
                    workL[offset + n] = buf.getShort(start + n * 2).toFloat() / 32768f
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (n in 0 until numFrames) {
                    val o = start + n * 8
                    workL[offset + n] = buf.getFloat(o)
                    workR[offset + n] = buf.getFloat(o + 4)
                }
            } else {
                for (n in 0 until numFrames) {
                    val o = start + n * 4
                    workL[offset + n] = buf.getShort(o).toFloat() / 32768f
                    workR[offset + n] = buf.getShort(o + 2).toFloat() / 32768f
                }
            }
        }
    }

    private fun writeFrame(index: Int, channels: Int, encoding: Int, l: Float, r: Float) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            if (channels == 1) {
                outputBuffer.putFloat(index * 4, l)
            } else {
                val o = index * 8
                outputBuffer.putFloat(o, l)
                outputBuffer.putFloat(o + 4, r)
            }
        } else {
            if (channels == 1) {
                outputBuffer.putShort(index * 2, toPcm16(l))
            } else {
                val o = index * 4
                outputBuffer.putShort(o, toPcm16(l))
                outputBuffer.putShort(o + 2, toPcm16(r))
            }
        }
    }

    private fun toPcm16(v: Float): Short =
        (v * 32768f).toInt().coerceIn(-32768, 32767).toShort()

    private companion object {
        const val HALF_WIDTH = 32
        const val PHASES = 256
        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 20f

        /** Matches Sonic's own tolerance for "close enough to 1 to skip". */
        const val RATIO_DEADZONE = 1e-4f
    }
}
