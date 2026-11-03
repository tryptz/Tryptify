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
    /** What [getOutput] hands on: [buffer], or EMPTY once it has been taken. */
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /**
     * The output's memory, kept across blocks. [getOutput] has to leave
     * [outputBuffer] empty, so the memory cannot live there: when it did,
     * every block found a capacity of 0 and allocated a new direct buffer.
     */
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

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

    // One chain per channel, all tuned to the same curve (audio thread only).
    private var chains: Array<Array<BiquadFilter>> = emptyArray()

    /**
     * Whether the filters' memory is from the audio just before this block:
     * false while the EQ is off or flat, since they ran on none of it then.
     * Audio thread only.
     */
    private var filtersCurrent = false
    private val block = tf.monochrome.android.audio.dsp.PlanarBlock()
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
        // The same curve again (the player re-applies its settings when it
        // becomes ready, after every seek and rebuffer) leaves the published
        // snapshot alone, so the audio thread has nothing to retune.
        if (snap.sameAs(stateRef.get())) return
        stateRef.set(snap)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Up to 16 channels, every one through the same curve. Wider than
        // that goes inactive rather than failing playback; clear both
        // trackers so isActive() reads false at once (Media3's pipeline
        // checkState()s active processors against NOT_SET).
        if (inputAudioFormat.channelCount > tf.monochrome.android.audio.dsp.ChannelLayout.MAX_CHANNELS) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount < 1) {
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
        val frameBytes = inputChannels * (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2)
        if (inputBuffer.remaining() < frameBytes) return
        // The width the filters run at: a mono stream comes out as stereo.
        val channelCount = if (inputChannels == 1) 2 else inputChannels

        val snap = stateRef.get()
        var hasWork = false
        if (snap.enabled) {
            if (snap !== appliedSnapshot || chains.size != channelCount) {
                retuneFilters(snap.bands, channelCount)
                appliedSnapshot = snap
            }
            hasWork = chains[0].isNotEmpty() || snap.preampLinear != 1f
        }
        filtersCurrent = hasWork

        // Off, or on with nothing to do, and nothing to widen: copy the block
        // as it came. The planar round trip it skips is exact for both
        // encodings (a 16-bit sample divided and multiplied by 32768 lands on
        // itself, a float is stored as read), so this is the same output for
        // less work. A mono stream still takes the planar path, which makes
        // it stereo.
        if (!hasWork && inputChannels != 1) {
            copyFrames(inputBuffer, inputBuffer.remaining() / frameBytes * frameBytes)
            return
        }

        val frames = block.read(inputBuffer, inputChannels, encoding)
        if (frames <= 0) return
        if (hasWork) applyEq(frames, snap.preampLinear)

        val outBytes = block.outputBytes(frames, encoding)
        val out = ensureOutput(outBytes)
        block.write(out, frames, encoding)
        out.position(0)
        out.limit(outBytes)
    }

    /** Copies [bytes] of [input] to the output unchanged, advancing it past them. */
    private fun copyFrames(input: ByteBuffer, bytes: Int) {
        val out = ensureOutput(bytes)
        val savedLimit = input.limit()
        input.limit(input.position() + bytes)
        out.put(input)
        input.limit(savedLimit)
        out.flip()
    }

    /** [buffer], at least [bytes] long and cleared, as the pending output. */
    private fun ensureOutput(bytes: Int): ByteBuffer {
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
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
        // The filters keep their memory through a flush of the same format.
        // Media3 drains and flushes the whole chain at every gapless track
        // change and speed change too, not only on a seek, and a boosted
        // band starting again from silence there thumped on audio that runs
        // straight on. AutoEqProcessor keeps its state the same way.
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
                filtersCurrent = false
                for (chain in chains) for (filter in chain) filter.clearState()
            }
            pendingFormat = AudioFormat.NOT_SET
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        chains = emptyArray()
        appliedSnapshot = null
    }

    private fun applyEq(numFrames: Int, preampLinear: Float) {
        val channels = block.channels
        for (c in 0 until block.channelCount) {
            val buf = channels[c]
            if (preampLinear != 1f) {
                for (i in 0 until numFrames) buf[i] *= preampLinear
            }
            val chain = chains[c]
            for (i in chain.indices) chain[i].processBlock(buf, numFrames)
        }
    }

    /**
     * Tunes the chains to [bands].
     *
     * Filters are made new only when the channel count or the number of active
     * bands changes, and those start from silence. Otherwise each one is
     * retuned in place with its memory kept, as AutoEqProcessor does: a fresh
     * filter restarting mid-song is a click, and a new snapshot used to mean
     * exactly that, every time the curve was touched. Memory from before the
     * EQ was last off or flat is not kept: the filters heard none of the audio
     * since, so they start from silence as they always did.
     */
    private fun retuneFilters(bands: Array<BandState>, channelCount: Int) {
        var activeCount = 0
        for (band in bands) if (band.isActive) activeCount++
        val rebuild = chains.size != channelCount || chains[0].size != activeCount
        if (rebuild) chains = Array(channelCount) { Array(activeCount) { BiquadFilter() } }
        val keepState = !rebuild && filtersCurrent
        var i = 0
        for (band in bands) {
            if (!band.isActive) continue
            val type = when (band.type) {
                FilterType.LOWSHELF -> BiquadType.LOW_SHELF
                FilterType.HIGHSHELF -> BiquadType.HIGH_SHELF
                else -> BiquadType.PEAKING
            }
            for (chain in chains) {
                chain[i].configure(
                    type, sampleRate, band.freq.toDouble(), band.q.toDouble(), band.gain.toDouble(),
                    keepState = keepState,
                )
            }
            i++
        }
    }

    private enum class BiquadType { PEAKING, LOW_SHELF, HIGH_SHELF }

    private class BandState(
        val freq: Float, val gain: Float, val q: Float,
        val type: FilterType, val enabled: Boolean
    ) {
        /** Disabled, or at zero gain, a band contributes nothing but a wasted biquad. */
        val isActive: Boolean get() = enabled && gain != 0f

        fun sameAs(other: BandState): Boolean =
            freq == other.freq && gain == other.gain && q == other.q &&
                type == other.type && enabled == other.enabled
    }

    private fun Snapshot.sameAs(other: Snapshot): Boolean {
        if (enabled != other.enabled || preampLinear != other.preampLinear) return false
        if (bands.size != other.bands.size) return false
        for (i in bands.indices) if (!bands[i].sameAs(other.bands[i])) return false
        return true
    }

    private class BiquadFilter {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        /**
         * Designs the coefficients. A new filter, or one whose design failed,
         * starts from silence; [keepState] retunes a running one in place.
         */
        fun configure(
            type: BiquadType, sr: Double, freq: Double, q: Double, gainDb: Double,
            keepState: Boolean = false,
        ) {
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
                z1 = 0f; z2 = 0f
                return
            }
            b0 = nb0f; b1 = nb1f; b2 = nb2f; a1 = na1f; a2 = na2f
            if (!keepState) {
                z1 = 0f; z2 = 0f
            }
        }

        fun clearState() {
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
