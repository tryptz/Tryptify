package tf.monochrome.android.audio.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standalone 10-band parametric EQ AudioProcessor for AutoEQ headphone correction.
 * Completely independent of the mixer/DSP engine — sits in ExoPlayer's pipeline
 * as its own processor, always active when EQ is enabled.
 *
 * Uses RBJ Audio EQ Cookbook biquad filters (peaking, low shelf, high shelf).
 */
@Singleton
@OptIn(UnstableApi::class)
class AutoEqProcessor @Inject constructor() : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Scratch float arrays
    private var scratchL = FloatArray(0)
    private var scratchR = FloatArray(0)

    // EQ state — written from UI thread, read from audio thread
    @Volatile private var eqEnabled = false
    @Volatile private var currentBands: Array<BandState> = emptyArray()
    @Volatile private var preampLinear = 1f

    // Per-band biquad filter state (audio thread only, rebuilt when bands change)
    private var filtersL = arrayOf<BiquadFilter>()
    private var filtersR = arrayOf<BiquadFilter>()
    private var filtersDirty = true
    private var sampleRate = 44100.0

    fun applyBands(bands: List<EqBand>, preamp: Float, enabled: Boolean) {
        eqEnabled = enabled
        preampLinear = if (preamp == 0f) 1f else 10f.pow(preamp / 20f)
        currentBands = bands.map { band ->
            BandState(
                freq = band.freq,
                gain = band.gain,
                q = band.q,
                type = band.type,
                enabled = band.enabled
            )
        }.toTypedArray()
        filtersDirty = true
    }

    // ── AudioProcessor implementation ────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return if (inputAudioFormat.channelCount == 1) {
            AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
        } else {
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val inputChannels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) return

        // Ensure scratch arrays
        if (scratchL.size < numFrames) {
            scratchL = FloatArray(numFrames)
            scratchR = FloatArray(numFrames)
        }

        // Deinterleave
        if (inputChannels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = inputBuffer.asFloatBuffer()
                for (i in 0 until numFrames) {
                    val s = fb.get(); scratchL[i] = s; scratchR[i] = s
                }
            } else {
                val sb = inputBuffer.asShortBuffer()
                for (i in 0 until numFrames) {
                    val s = sb.get().toFloat() / 32768f; scratchL[i] = s; scratchR[i] = s
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = inputBuffer.asFloatBuffer()
                for (i in 0 until numFrames) { scratchL[i] = fb.get(); scratchR[i] = fb.get() }
            } else {
                val sb = inputBuffer.asShortBuffer()
                for (i in 0 until numFrames) {
                    scratchL[i] = sb.get().toFloat() / 32768f
                    scratchR[i] = sb.get().toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(inputBuffer.position() + numFrames * frameSize)

        // Apply EQ if enabled
        if (eqEnabled) {
            if (filtersDirty) {
                rebuildFilters()
                filtersDirty = false
            }
            applyEq(numFrames)
        }

        // Interleave output (always stereo)
        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val fb = outputBuffer.asFloatBuffer()
            for (i in 0 until numFrames) { fb.put(scratchL[i]); fb.put(scratchR[i]) }
        } else {
            val sb = outputBuffer.asShortBuffer()
            for (i in 0 until numFrames) {
                sb.put((scratchL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                sb.put((scratchR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        outputBuffer.position(0)
        outputBuffer.limit(outBytes)
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            val formatChanged = inputFormat == AudioFormat.NOT_SET
                || inputFormat.sampleRate != pendingFormat.sampleRate
                || inputFormat.encoding != pendingFormat.encoding
                || inputFormat.channelCount != pendingFormat.channelCount
            if (formatChanged) {
                inputFormat = pendingFormat
                sampleRate = inputFormat.sampleRate.toDouble()
                filtersDirty = true
            }
            pendingFormat = AudioFormat.NOT_SET
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        filtersL = emptyArray()
        filtersR = emptyArray()
    }

    // ── DSP internals ───────────────────────────────────────────────────

    private fun applyEq(numFrames: Int) {
        // Preamp
        val gain = preampLinear
        if (gain != 1f) {
            for (i in 0 until numFrames) {
                scratchL[i] *= gain
                scratchR[i] *= gain
            }
        }
        // Biquad bands
        for (i in filtersL.indices) {
            filtersL[i].processBlock(scratchL, numFrames)
            filtersR[i].processBlock(scratchR, numFrames)
        }
    }

    private fun rebuildFilters() {
        val bands = currentBands
        val active = bands.filter { it.enabled && it.gain != 0f }
        filtersL = Array(active.size) { BiquadFilter() }
        filtersR = Array(active.size) { BiquadFilter() }
        for ((i, band) in active.withIndex()) {
            val type = when (band.type) {
                FilterType.LOWSHELF -> BiquadType.LOW_SHELF
                FilterType.HIGHSHELF -> BiquadType.HIGH_SHELF
                else -> BiquadType.PEAKING
            }
            filtersL[i].configure(type, sampleRate, band.freq.toDouble(), band.q.toDouble(), band.gain.toDouble())
            filtersR[i].configure(type, sampleRate, band.freq.toDouble(), band.q.toDouble(), band.gain.toDouble())
        }
    }

    // ── RBJ biquad (pure Kotlin, Transposed Direct Form II) ─────────────

    private enum class BiquadType { PEAKING, LOW_SHELF, HIGH_SHELF }

    private class BandState(
        val freq: Float, val gain: Float, val q: Float,
        val type: FilterType, val enabled: Boolean
    )

    private class BiquadFilter {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun configure(type: BiquadType, sr: Double, freq: Double, q: Double, gainDb: Double) {
            val w0 = 2.0 * Math.PI * freq / sr
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)
            val alpha = sinw0 / (2.0 * q)
            var nb0: Double; var nb1: Double; var nb2: Double
            var na0: Double; var na1: Double; var na2: Double

            when (type) {
                BiquadType.PEAKING -> {
                    val a = 10.0.pow(gainDb / 40.0)
                    nb0 = 1.0 + alpha * a; nb1 = -2.0 * cosw0; nb2 = 1.0 - alpha * a
                    na0 = 1.0 + alpha / a; na1 = -2.0 * cosw0; na2 = 1.0 - alpha / a
                }
                BiquadType.LOW_SHELF -> {
                    val a = 10.0.pow(gainDb / 40.0)
                    val sq = 2.0 * sqrt(a) * alpha
                    nb0 = a * ((a + 1) - (a - 1) * cosw0 + sq)
                    nb1 = 2.0 * a * ((a - 1) - (a + 1) * cosw0)
                    nb2 = a * ((a + 1) - (a - 1) * cosw0 - sq)
                    na0 = (a + 1) + (a - 1) * cosw0 + sq
                    na1 = -2.0 * ((a - 1) + (a + 1) * cosw0)
                    na2 = (a + 1) + (a - 1) * cosw0 - sq
                }
                BiquadType.HIGH_SHELF -> {
                    val a = 10.0.pow(gainDb / 40.0)
                    val sq = 2.0 * sqrt(a) * alpha
                    nb0 = a * ((a + 1) + (a - 1) * cosw0 + sq)
                    nb1 = -2.0 * a * ((a - 1) + (a + 1) * cosw0)
                    nb2 = a * ((a + 1) + (a - 1) * cosw0 - sq)
                    na0 = (a + 1) - (a - 1) * cosw0 + sq
                    na1 = 2.0 * ((a - 1) - (a + 1) * cosw0)
                    na2 = (a + 1) - (a - 1) * cosw0 - sq
                }
            }
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
            z1 = 0f; z2 = 0f
        }

        fun processBlock(data: FloatArray, n: Int) {
            for (i in 0 until n) {
                val x = data[i]
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                data[i] = y
            }
        }
    }
}
