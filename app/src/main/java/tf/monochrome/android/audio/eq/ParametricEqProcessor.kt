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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standalone general-purpose parametric EQ AudioProcessor. Independent of AutoEQ,
 * so users can chain both (AutoEQ for headphone correction + Parametric for tone shaping).
 *
 * Sits in the ExoPlayer pipeline *after* AutoEQ. Uses RBJ Audio EQ Cookbook biquad
 * filters (peaking, low shelf, high shelf).
 */
@Singleton
@OptIn(UnstableApi::class)
class ParametricEqProcessor @Inject constructor() : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var scratchL = FloatArray(0)
    private var scratchR = FloatArray(0)

    // Group the three UI-thread writes into one immutable snapshot published atomically.
    // The audio thread reads the reference once per block — guaranteed to see a consistent
    // (enabled, preamp, bands) triple, never a half-updated one.
    private data class Snapshot(
        val enabled: Boolean,
        val preampLinear: Float,
        val bands: Array<BandState>
    )

    private val stateRef = AtomicReference(Snapshot(false, 1f, emptyArray()))
    private var appliedSnapshot: Snapshot? = null

    private var filtersL = arrayOf<BiquadFilter>()
    private var filtersR = arrayOf<BiquadFilter>()
    private var sampleRate = 44100.0

    fun applyBands(bands: List<EqBand>, preamp: Float, enabled: Boolean) {
        val snap = Snapshot(
            enabled = enabled,
            preampLinear = if (preamp == 0f) 1f else 10f.pow(preamp / 20f),
            bands = bands.map { band ->
                BandState(
                    freq = band.freq,
                    gain = band.gain,
                    q = band.q,
                    type = band.type,
                    enabled = band.enabled
                )
            }.toTypedArray()
        )
        stateRef.set(snap)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount > 2) {
            // Multichannel passthrough (downmix toggle off): go inactive
            // instead of failing playback — EQ simply doesn't apply. Clear
            // both trackers so isActive() reads false immediately (Media3's
            // pipeline checkState()s active processors against NOT_SET).
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
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

        if (scratchL.size < numFrames) {
            scratchL = FloatArray(numFrames)
            scratchR = FloatArray(numFrames)
        }

        // Deinterleave with index-based reads — no asFloatBuffer / asShortBuffer
        // view allocations on the audio thread.
        val startPos = inputBuffer.position()
        if (inputChannels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getFloat(startPos + i * 4)
                    scratchL[i] = s; scratchR[i] = s
                }
            } else {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getShort(startPos + i * 2).toFloat() / 32768f
                    scratchL[i] = s; scratchR[i] = s
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 8
                    scratchL[i] = inputBuffer.getFloat(off)
                    scratchR[i] = inputBuffer.getFloat(off + 4)
                }
            } else {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 4
                    scratchL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                    scratchR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)

        val snap = stateRef.get()
        if (snap.enabled) {
            if (snap !== appliedSnapshot) {
                rebuildFilters(snap.bands)
                appliedSnapshot = snap
            }
            applyEq(numFrames, snap.preampLinear)
        }

        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        // Interleave via positional put* — no view allocations on the hot path.
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until numFrames) {
                val off = i * 8
                outputBuffer.putFloat(off, scratchL[i])
                outputBuffer.putFloat(off + 4, scratchR[i])
            }
        } else {
            for (i in 0 until numFrames) {
                val off = i * 4
                outputBuffer.putShort(off, (scratchL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, (scratchR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
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
                // Force filter rebuild on next block (sample-rate-dependent coefficients).
                appliedSnapshot = null
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
        appliedSnapshot = null
    }

    private fun applyEq(numFrames: Int, preampLinear: Float) {
        if (preampLinear != 1f) {
            for (i in 0 until numFrames) {
                scratchL[i] *= preampLinear
                scratchR[i] *= preampLinear
            }
        }
        for (i in filtersL.indices) {
            filtersL[i].processBlock(scratchL, numFrames)
            filtersR[i].processBlock(scratchR, numFrames)
        }
    }

    private fun rebuildFilters(bands: Array<BandState>) {
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
            if (abs(na0) < 1e-20 || !na0.isFinite()) {
                // Degenerate coefficient — fall back to passthrough instead of emitting NaN/Inf audio.
                b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
                z1 = 0f; z2 = 0f
                return
            }
            val nb0f = (nb0 / na0).toFloat()
            val nb1f = (nb1 / na0).toFloat()
            val nb2f = (nb2 / na0).toFloat()
            val na1f = (na1 / na0).toFloat()
            val na2f = (na2 / na0).toFloat()
            if (!nb0f.isFinite() || !nb1f.isFinite() || !nb2f.isFinite() ||
                !na1f.isFinite() || !na2f.isFinite()) {
                b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            } else {
                b0 = nb0f; b1 = nb1f; b2 = nb2f; a1 = na1f; a2 = na2f
            }
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
