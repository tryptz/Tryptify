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
import kotlin.math.pow

/**
 * Multichannel → stereo downmix renderer. Sits FIRST in the AudioProcessor
 * chain so everything downstream (MixBusProcessor → native stereo engine,
 * AutoEQ, Parametric EQ, USB DAC negotiation) keeps its 1/2-channel world
 * view while 3.0–16-channel sources still play.
 *
 * Two fold matrices, selected by [setSurroundEncode] (driven by the Atmos
 * page's Downmix Matrix choice; no HRTF/virtualization either way).
 *
 * Lo/Ro — the fixed per-channel gain matrix (default):
 *
 *   FL/BL/BLC/SL/TFL/TSL/TBL → [1, 0]      (hard left)
 *   FR/BR/BRC/SR/TFR/TSR/TBR → [0, 1]      (hard right)
 *   FC (and BC in 6.1)       → [0.70710678, 0.70710678]
 *   LFE                      → [2.26464431, 2.26464431]
 *
 * Lt/Rt — the surround renderer: a Pro Logic II-style passive matrix encode.
 * Fronts/FC/LFE fold as in Lo/Ro; surround-class channels go in anti-phase
 * with cross-feed (own side −0.8165 / opposite −0.5774 into Lt, mirrored
 * positive into Rt; 6.1's BC at ∓0.70710678) so a Pro Logic decoder can
 * re-expand the stereo fold back to surround. Top-front pairs count as
 * fronts; side/back tops as surrounds.
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

    /**
     * Matrix choice: false = Lo/Ro fixed matrix (default), true = Lt/Rt
     * surround encode. Follows the Atmos page's Downmix Matrix chips; applied
     * in flush(), so it takes effect on the next reconfigure/seek like
     * [setEnabled].
     */
    @Volatile
    private var surroundEncode: Boolean = false

    fun setSurroundEncode(e: Boolean) {
        surroundEncode = e
    }

    /**
     * Master trim (dB) baked into the coefficient rows at flush time — the
     * preamp that pulls the hot verbatim matrix below clipping (equivalent
     * of the peqdb Downmix Renderer's --master-gain-db). Applied on the next
     * reconfigure/seek like the other toggles; costs nothing per sample.
     */
    @Volatile
    private var preampDb: Float = 0f

    fun setPreampDb(db: Float) {
        preampDb = db
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
            !KIND_TABLES.containsKey(inputAudioFormat.channelCount)) {
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
            val tables = if (surroundEncode) LT_RT_TABLES else LO_RO_TABLES
            val rows = tables.getValue(inputFormat.channelCount)
            // Copy before scaling — the table arrays are shared class-level
            // constants and must never be mutated.
            val gain = 10f.pow(preampDb / 20f)
            coefL = FloatArray(rows.first.size) { rows.first[it] * gain }
            coefR = FloatArray(rows.second.size) { rows.second[it] * gain }
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

        // Pro Logic II passive-encode surround gains: own side / cross-feed.
        private const val LTRT_SURR_MAIN = 0.8165f
        private const val LTRT_SURR_CROSS = 0.5774f

        /** Position class of one input channel; both matrices derive from it. */
        private enum class Kind { L_FRONT, R_FRONT, CENTER, LFE_CH, L_SURR, R_SURR, C_SURR }

        // Channel classes per input count. Assumed orders (FLAC / FFmpeg /
        // Android canonical, which agree for 3–8):
        //   3:  FL FR FC
        //   4:  FL FR BL BR            (quad)
        //   5:  FL FR FC BL BR
        //   6:  FL FR FC LFE BL BR     (5.1; 5.1-side folds identically)
        //   7:  FL FR FC LFE BC SL SR  (6.1)
        //   8:  FL FR FC LFE BL BR SL SR (7.1)
        //   16: FL FR FC LFE BL BR BLC BRC SL SR TFL TFR TSL TSR TBL TBR (9.1.6)
        // Top-front (TFL/TFR) count as fronts; side/back tops as surrounds.
        private val KIND_TABLES: Map<Int, Array<Kind>> = mapOf(
            3 to arrayOf(Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER),
            4 to arrayOf(Kind.L_FRONT, Kind.R_FRONT, Kind.L_SURR, Kind.R_SURR),
            5 to arrayOf(Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.L_SURR, Kind.R_SURR),
            6 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR,
            ),
            7 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.C_SURR, Kind.L_SURR, Kind.R_SURR,
            ),
            8 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
            ),
            16 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
                Kind.L_SURR, Kind.R_SURR, Kind.L_FRONT, Kind.R_FRONT,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
            ),
        )

        // Verbatim (un-normalized) [L,R] gains for one channel class.
        private fun loRo(k: Kind): Pair<Float, Float> = when (k) {
            Kind.L_FRONT, Kind.L_SURR -> 1f to 0f
            Kind.R_FRONT, Kind.R_SURR -> 0f to 1f
            Kind.CENTER, Kind.C_SURR -> CENTER_COEF to CENTER_COEF
            Kind.LFE_CH -> LFE_COEF to LFE_COEF
        }

        private fun ltRt(k: Kind): Pair<Float, Float> = when (k) {
            Kind.L_FRONT -> 1f to 0f
            Kind.R_FRONT -> 0f to 1f
            Kind.CENTER -> CENTER_COEF to CENTER_COEF
            Kind.LFE_CH -> LFE_COEF to LFE_COEF
            Kind.L_SURR -> -LTRT_SURR_MAIN to LTRT_SURR_CROSS
            Kind.R_SURR -> -LTRT_SURR_CROSS to LTRT_SURR_MAIN
            Kind.C_SURR -> -CENTER_COEF to CENTER_COEF
        }

        private fun buildTables(
            gains: (Kind) -> Pair<Float, Float>,
        ): Map<Int, Pair<FloatArray, FloatArray>> = KIND_TABLES.mapValues { (_, kinds) ->
            Pair(
                FloatArray(kinds.size) { gains(kinds[it]).first },
                FloatArray(kinds.size) { gains(kinds[it]).second },
            )
        }

        // Both matrices computed once at class load; the audio thread only
        // indexes into the selected pair.
        private val LO_RO_TABLES = buildTables(::loRo)
        private val LT_RT_TABLES = buildTables(::ltRt)
    }
}
