package tf.monochrome.android.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multichannel → stereo downmix renderer. Sits FIRST in the AudioProcessor
 * chain so everything downstream (MixBusProcessor → native stereo engine,
 * AutoEQ, Parametric EQ, USB DAC negotiation) keeps its 1/2-channel world
 * view while 3.0–7.1 sources still play.
 *
 * Fold-down follows ITU-R BS.775 Lo/Ro (a plain stereo fold, no Lt/Rt
 * matrix-surround encode, no HRTF/virtualization):
 *
 *   Lo = FL + 0.7071·C + 0.7071·SL      Ro = FR + 0.7071·C + 0.7071·SR
 *
 * with the LFE discarded ([LFE_COEF] = 0) per the BS.775 / AC-3 default —
 * it carries no unique program content and folding it in risks bass
 * overload. Each output row is then normalized so its coefficients sum to
 * 1.0. That costs ~7.7 dB of level on 5.1 versus unity-FL ITU rows, but it
 * is clip-proof by construction and preserves relative imaging — the
 * alternative (unity FL + clamp) audibly pumps on hot masters and can't be
 * repaired downstream because the inter-processor format is PCM16.
 *
 * Channel-order assumption: FLAC spec order, FFmpeg native order, and
 * Android's canonical CHANNEL_OUT_* order all agree for 3–8 channels
 * (6 ch = FL FR FC LFE BL BR), so a single per-channel-count table is used.
 * Media3's AudioFormat carries no layout, only a count; sources with an
 * exotic layout at the same count would fold with wrong positions (imaging
 * off), never crash.
 *
 * Mono/stereo input leaves the processor inactive (configure returns
 * [AudioFormat.NOT_SET]) — mono upmix stays MixBusProcessor's job. When
 * [setEnabled] is false ("passthrough" user setting) the processor is
 * inactive for every format and multichannel PCM flows untouched to
 * AudioTrack (the stereo-only processors downstream deactivate themselves
 * for >2 ch); the platform then downmixes or outputs natively. No dither
 * on the PCM16 path: MixBusProcessor immediately re-enters the float
 * domain and dithers its own PCM16 output.
 */
@Singleton
@OptIn(UnstableApi::class)
class DownmixProcessor @Inject constructor() : AudioProcessor {

    // pendingFormat == NOT_SET ⇔ inactive. IMPORTANT: unlike
    // MixBusProcessor, isActive() must NOT also consider a lingering
    // inputFormat — Media3's AudioProcessingPipeline.configure() does
    // checkState(returnedFormat != NOT_SET) whenever isActive() is true,
    // so "configured for stereo after a 5.1 track" has to read as
    // inactive immediately.
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Active coefficient rows, length == inputFormat.channelCount,
    // normalization baked in. Selected in flush().
    private var coefL = FloatArray(0)
    private var coefR = FloatArray(0)

    /**
     * User setting: fold multichannel to stereo (true, default) or pass it
     * through untouched (false). Read on the audio thread in configure();
     * takes effect on the next pipeline reconfigure (track change / seek /
     * format change), same as the other DSP toggles.
     */
    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(e: Boolean) {
        enabled = e
    }

    // ── AudioProcessor implementation ────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount < 1 ||
            inputAudioFormat.channelCount > MAX_INPUT_CHANNELS) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!enabled || inputAudioFormat.channelCount <= 2) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun isActive(): Boolean = pendingFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        if (channels < 3) return
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) return

        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        // Fused deinterleave + matrix + interleave via positional get*/put* —
        // no asShortBuffer()/asFloatBuffer() view allocations on the audio
        // thread (same rationale as MixBusProcessor's hot loop).
        val cL = coefL
        val cR = coefR
        val startPos = inputBuffer.position()
        for (i in 0 until numFrames) {
            val base = startPos + i * frameSize
            var accL = 0f
            var accR = 0f
            if (isFloat) {
                for (c in 0 until channels) {
                    val s = inputBuffer.getFloat(base + c * 4)
                    accL += cL[c] * s
                    accR += cR[c] * s
                }
                val off = i * 8
                outputBuffer.putFloat(off, accL)
                outputBuffer.putFloat(off + 4, accR)
            } else {
                for (c in 0 until channels) {
                    val s = inputBuffer.getShort(base + c * 2).toFloat() / 32768f
                    accL += cL[c] * s
                    accR += cR[c] * s
                }
                val off = i * 4
                outputBuffer.putShort(off, (accL * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, (accR * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)
        outputBuffer.position(0)
        outputBuffer.limit(outBytes)
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        // Keep pendingFormat set: seeks flush() without a configure(), and
        // both Media3's pipeline and AudioProcessorChain flush right after
        // configure — the active format must survive.
        inputFormat = pendingFormat
        if (inputFormat != AudioFormat.NOT_SET) {
            val rows = COEF_TABLES[inputFormat.channelCount - 3]
            coefL = rows.first
            coefR = rows.second
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        coefL = FloatArray(0)
        coefR = FloatArray(0)
    }

    companion object {
        const val MAX_INPUT_CHANNELS = 8

        /** −3 dB, the ITU-R BS.775 gain for center and surround channels. */
        private const val MINUS_3_DB = 0.70710678f

        /**
         * LFE contribution to the fold. 0 per the BS.775 / AC-3 default;
         * bump to e.g. 0.5f (−6 dB) if LFE fold-in is ever wanted — rows
         * are re-normalized automatically.
         */
        private const val LFE_COEF = 0f

        // Raw ITU-R BS.775 Lo/Ro L-rows per input channel count (index =
        // channelCount − 3). R mirrors L↔R (and BC feeds both sides).
        // Assumed orders (FLAC / FFmpeg / Android canonical, which agree):
        //   3: FL FR FC
        //   4: FL FR BL BR            (quad)
        //   5: FL FR FC BL BR
        //   6: FL FR FC LFE BL BR     (5.1; 5.1-side folds identically)
        //   7: FL FR FC LFE BC SL SR  (6.1)
        //   8: FL FR FC LFE BL BR SL SR (7.1)
        private val RAW_L_ROWS = arrayOf(
            floatArrayOf(1f, 0f, MINUS_3_DB),
            floatArrayOf(1f, 0f, MINUS_3_DB, 0f),
            floatArrayOf(1f, 0f, MINUS_3_DB, MINUS_3_DB, 0f),
            floatArrayOf(1f, 0f, MINUS_3_DB, LFE_COEF, MINUS_3_DB, 0f),
            floatArrayOf(1f, 0f, MINUS_3_DB, LFE_COEF, MINUS_3_DB, MINUS_3_DB, 0f),
            floatArrayOf(1f, 0f, MINUS_3_DB, LFE_COEF, MINUS_3_DB, 0f, MINUS_3_DB, 0f),
        )

        // Which input channel is the L-side source that maps to the R-side
        // one at the same "position class", per channel count. Rather than
        // hand-maintaining mirrored tables, derive the R row by swapping
        // each stereo pair; center-class channels (FC, LFE, BC) stay put.
        private val STEREO_PAIRS = arrayOf(
            arrayOf(0 to 1),                     // 3 ch: FL↔FR
            arrayOf(0 to 1, 2 to 3),             // 4 ch: FL↔FR, BL↔BR
            arrayOf(0 to 1, 3 to 4),             // 5 ch: FL↔FR, BL↔BR
            arrayOf(0 to 1, 4 to 5),             // 6 ch: FL↔FR, BL↔BR
            arrayOf(0 to 1, 5 to 6),             // 7 ch: FL↔FR, SL↔SR
            arrayOf(0 to 1, 4 to 5, 6 to 7),     // 8 ch: FL↔FR, BL↔BR, SL↔SR
        )

        // Normalized (row sum == 1.0 → mathematically clip-proof) L/R rows,
        // computed once at class load so the audio thread only indexes.
        private val COEF_TABLES: Array<Pair<FloatArray, FloatArray>> =
            Array(RAW_L_ROWS.size) { idx ->
                val l = RAW_L_ROWS[idx]
                val r = FloatArray(l.size)
                l.copyInto(r)
                for ((a, b) in STEREO_PAIRS[idx]) {
                    r[a] = l[b]
                    r[b] = l[a]
                }
                val sumL = l.sum()
                val sumR = r.sum()
                Pair(
                    FloatArray(l.size) { l[it] / sumL },
                    FloatArray(r.size) { r[it] / sumR },
                )
            }
    }
}
