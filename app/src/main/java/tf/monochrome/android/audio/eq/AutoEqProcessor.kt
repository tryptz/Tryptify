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
import kotlin.math.pow

/**
 * Standalone 10-band parametric EQ AudioProcessor for AutoEQ headphone correction.
 * Completely independent of the mixer/DSP engine — sits in ExoPlayer's pipeline
 * as its own processor, always active when EQ is enabled.
 *
 * Uses decramped biquads (peaking, low shelf, high shelf): Vicanek matched-Z
 * peaking and tournament-matched shelves, so the response lands on the analog
 * prototypes the correction curves are designed against — see EqDspKernels.
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

    // UI-thread writes grouped into one immutable snapshot published atomically so the
    // audio thread always sees a consistent (enabled, preamp, bands) triple.
    private data class Snapshot(
        val enabled: Boolean,
        val preampLinear: Float,
        val bandsL: Array<BandState>,
        val bandsR: Array<BandState>
    )

    private val stateRef = AtomicReference(Snapshot(false, 1f, emptyArray(), emptyArray()))
    private var appliedSnapshot: Snapshot? = null

    // Per-band biquad filter state (audio thread only, rebuilt when bands change)
    private var filtersL = arrayOf<EqBiquad>()
    private var filtersR = arrayOf<EqBiquad>()
    private var sampleRate = 44100.0

    /** Same curve on both ears. */
    fun applyBands(bands: List<EqBand>, preamp: Float, enabled: Boolean) =
        applyBands(bands, bands, preamp, enabled)

    /**
     * Independent curves per channel, for per-ear headphone calibration.
     *
     * The two lists may differ in length — a left calibration with 10 filters
     * and a right with 8 is entirely normal — so the filter chains are built
     * and run separately rather than index-locked to each other.
     */
    fun applyBands(
        bandsL: List<EqBand>,
        bandsR: List<EqBand>,
        preamp: Float,
        enabled: Boolean,
    ) {
        fun List<EqBand>.toStates() = map { band ->
            BandState(
                freq = band.freq,
                gain = band.gain,
                q = band.q,
                type = band.type,
                enabled = band.enabled
            )
        }.toTypedArray()

        val snap = Snapshot(
            enabled = enabled,
            preampLinear = if (preamp == 0f) 1f else 10f.pow(preamp / 20f),
            bandsL = bandsL.toStates(),
            bandsR = bandsR.toStates()
        )
        stateRef.set(snap)
    }

    // ── AudioProcessor implementation ────────────────────────────────────

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

        // Ensure scratch arrays
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

        // Apply EQ if enabled
        val snap = stateRef.get()
        if (snap.enabled) {
            if (snap !== appliedSnapshot) {
                filtersL = buildChain(snap.bandsL)
                filtersR = buildChain(snap.bandsR)
                appliedSnapshot = snap
            }
            // Hard bypass when the chain is flat: with no active filters and
            // unity preamp the samples are left untouched.
            val hasWork = filtersL.isNotEmpty() || filtersR.isNotEmpty() ||
                snap.preampLinear != 1f
            if (hasWork) {
                applyEq(scratchL, scratchR, numFrames, snap.preampLinear)
            }
        }

        // Interleave output (always stereo)
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
                // Force filter rebuild on next block (sample-rate-dependent
                // coefficients).
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

    // ── DSP internals ───────────────────────────────────────────────────

    private fun applyEq(bufL: FloatArray, bufR: FloatArray, numFrames: Int, preampLinear: Float) {
        if (preampLinear != 1f) {
            for (i in 0 until numFrames) {
                bufL[i] *= preampLinear
                bufR[i] *= preampLinear
            }
        }
        // Biquad bands. Indexed per channel, NOT off a shared range: the two
        // chains hold different filter counts whenever the ears are calibrated
        // separately, and driving both from filtersL.indices would walk off the
        // end of the shorter one on the audio thread.
        for (i in filtersL.indices) filtersL[i].processBlock(bufL, numFrames)
        for (i in filtersR.indices) filtersR[i].processBlock(bufR, numFrames)
    }

    private fun buildChain(bands: Array<BandState>): Array<EqBiquad> {
        val active = bands.filter { it.enabled && it.gain != 0f }
        val chain = Array(active.size) { EqBiquad() }
        // Decramped (matched) coefficients throughout: peaking bands use
        // Vicanek matched-Z, shelves the tournament-matched design — the top
        // octave lands on the analog prototype with no oversampling machinery.
        for ((i, band) in active.withIndex()) {
            val type = when (band.type) {
                FilterType.LOWSHELF -> EqBiquadType.LOW_SHELF
                FilterType.HIGHSHELF -> EqBiquadType.HIGH_SHELF
                else -> EqBiquadType.PEAKING
            }
            chain[i].configure(
                type, sampleRate, band.freq.toDouble(), band.q.toDouble(),
                band.gain.toDouble(), matched = true
            )
        }
        return chain
    }

    private class BandState(
        val freq: Float, val gain: Float, val q: Float,
        val type: FilterType, val enabled: Boolean
    )

}
