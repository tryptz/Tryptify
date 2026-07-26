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
 * view while 3.0–16-channel sources still play.
 *
 * Fold-down uses a fixed per-channel gain matrix (a plain stereo fold, no
 * Lt/Rt matrix-surround encode, no HRTF/virtualization):
 *
 *   FL/BL/BLC/SL/TFL/TSL/TBL → [1, 0]      (hard left)
 *   FR/BR/BRC/SR/TFR/TSR/TBR → [0, 1]      (hard right)
 *   FC (and BC in 6.1)       → [0.70710678, 0.70710678]
 *   LFE                      → [2.26464431, 2.26464431]
 *
 * The rows are used verbatim — no re-normalization — so absolute channel
 * levels are preserved exactly as specified. That means a hot multichannel
 * master CAN exceed full scale after the fold (a full-scale 5.1 frame sums
 * to ~4.97 on each side): the PCM16 path clamps at the rails, and the float
 * path relies on downstream headroom.
 *
 * Channel-order assumption: FLAC spec order, FFmpeg native order, and
 * Android's canonical CHANNEL_OUT_* order all agree for 3–8 channels
 * (6 ch = FL FR FC LFE BL BR), so a single per-channel-count table is used.
 * 16-channel sources are assumed to be 9.1.6, laid out as
 * FL FR FC LFE BL BR BLC BRC SL SR TFL TFR TSL TSR TBL TBR. Counts 9–15
 * have no well-known layout and pass through untouched. Media3's
 * AudioFormat carries no layout, only a count; sources with an exotic
 * layout at the same count would fold with wrong positions (imaging off),
 * never crash.
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
        if (!enabled || inputAudioFormat.channelCount <= 2 ||
            !COEF_TABLES.containsKey(inputAudioFormat.channelCount)) {
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
            val rows = COEF_TABLES.getValue(inputFormat.channelCount)
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
        const val MAX_INPUT_CHANNELS = 16

        /** −3 dB: the FC (and 6.1 BC) contribution to each side. */
        private const val CENTER_COEF = 0.70710678f

        /** LFE contribution to BOTH sides of the fold (~+7.1 dB). */
        private const val LFE_COEF = 2.26464431f

        /** L/R-class channels are hard-panned to their own side at unity. */
        private const val SIDE_COEF = 1f

        // L-rows of the fixed fold matrix, keyed by input channel count.
        // R mirrors L↔R (FC/LFE/BC feed both sides). Assumed orders
        // (FLAC / FFmpeg / Android canonical, which agree for 3–8):
        //   3:  FL FR FC
        //   4:  FL FR BL BR            (quad)
        //   5:  FL FR FC BL BR
        //   6:  FL FR FC LFE BL BR     (5.1; 5.1-side folds identically)
        //   7:  FL FR FC LFE BC SL SR  (6.1)
        //   8:  FL FR FC LFE BL BR SL SR (7.1)
        //   16: FL FR FC LFE BL BR BLC BRC SL SR TFL TFR TSL TSR TBL TBR (9.1.6)
        private val RAW_L_ROWS = mapOf(
            3 to floatArrayOf(SIDE_COEF, 0f, CENTER_COEF),
            4 to floatArrayOf(SIDE_COEF, 0f, SIDE_COEF, 0f),
            5 to floatArrayOf(SIDE_COEF, 0f, CENTER_COEF, SIDE_COEF, 0f),
            6 to floatArrayOf(SIDE_COEF, 0f, CENTER_COEF, LFE_COEF, SIDE_COEF, 0f),
            7 to floatArrayOf(SIDE_COEF, 0f, CENTER_COEF, LFE_COEF, CENTER_COEF, SIDE_COEF, 0f),
            8 to floatArrayOf(SIDE_COEF, 0f, CENTER_COEF, LFE_COEF, SIDE_COEF, 0f, SIDE_COEF, 0f),
            16 to floatArrayOf(
                SIDE_COEF, 0f, CENTER_COEF, LFE_COEF, SIDE_COEF, 0f, SIDE_COEF, 0f,
                SIDE_COEF, 0f, SIDE_COEF, 0f, SIDE_COEF, 0f, SIDE_COEF, 0f,
            ),
        )

        // Which input channel is the L-side source that maps to the R-side
        // one at the same "position class", per channel count. Rather than
        // hand-maintaining mirrored tables, derive the R row by swapping
        // each stereo pair; center-class channels (FC, LFE, BC) stay put.
        private val STEREO_PAIRS = mapOf(
            3 to arrayOf(0 to 1),                    // FL↔FR
            4 to arrayOf(0 to 1, 2 to 3),            // FL↔FR, BL↔BR
            5 to arrayOf(0 to 1, 3 to 4),            // FL↔FR, BL↔BR
            6 to arrayOf(0 to 1, 4 to 5),            // FL↔FR, BL↔BR
            7 to arrayOf(0 to 1, 5 to 6),            // FL↔FR, SL↔SR
            8 to arrayOf(0 to 1, 4 to 5, 6 to 7),    // FL↔FR, BL↔BR, SL↔SR
            16 to arrayOf(                           // + BLC↔BRC, TFL↔TFR,
                0 to 1, 4 to 5, 6 to 7, 8 to 9,      //   TSL↔TSR, TBL↔TBR
                10 to 11, 12 to 13, 14 to 15,
            ),
        )

        // Verbatim (un-normalized) L/R rows, computed once at class load so
        // the audio thread only indexes.
        private val COEF_TABLES: Map<Int, Pair<FloatArray, FloatArray>> =
            RAW_L_ROWS.mapValues { (count, l) ->
                val r = FloatArray(l.size)
                l.copyInto(r)
                for ((a, b) in STEREO_PAIRS.getValue(count)) {
                    r[a] = l[b]
                    r[b] = l[a]
                }
                Pair(l, r)
            }
    }
}
