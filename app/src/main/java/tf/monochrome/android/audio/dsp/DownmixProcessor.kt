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
 * Multichannel → stereo downmix renderer. Sits right after MixBusProcessor
 * in the AudioProcessor chain: the mixer sees the song's own layout (a 9.1.6
 * bed spread one channel group per bus) and this folds what it made to
 * stereo, so everything after it (AutoEQ, Parametric EQ, USB DAC
 * negotiation) keeps its 1/2-channel world view while 3.0–16-channel sources
 * still play. It used to sit first, and the mixer only ever saw stereo.
 *
 * One fixed per-channel gain matrix — the peqdb Downmix Renderer's ADC2
 * direct matrix, no HRTF/virtualization, and deliberately no alternative
 * matrix options:
 *
 *   FL/BL/FLC/SL/TFL/TBL/TSL → [1, 0]      (hard left)
 *   FR/BR/FRC/SR/TFR/TBR/TSR → [0, 1]      (hard right)
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
 * 16-channel sources are assumed to be 9.1.6 in FFmpeg's order,
 * FL FR FC LFE BL BR FLC FRC SL SR TFL TFR TBL TBR TSL TSR (every pair after
 * the LFE folds left/right the same whichever speakers it is). Counts 9–15
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
class DownmixProcessor @Inject constructor(
    // Headphone crossfeed, applied to the fold. MixBusProcessor runs it on a
    // stereo stream, but leaves a wide one alone (a wide stream may be going
    // to Android's spatializer, which places the speakers itself) — and the
    // mixer now runs before this fold, so a folded song would otherwise lose
    // it. The same singleton, so it is one setting; only one of the two runs
    // it for any stream. Null in the unit tests.
    private val crossfeed: tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect?,
    // The mixer's spatial map. While it is on, the fold places every channel
    // where the map has it (ChannelPlacer: HRIRs on headphones, a pan
    // otherwise) instead of running the fixed matrix. Null in the unit tests.
    private val placement: tf.monochrome.android.audio.dsp.spatial.SpatialPlacementStore?,
) : AudioProcessor {

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

    // The folded block as floats, for the crossfeed; grown on demand.
    private var foldL = FloatArray(0)
    private var foldR = FloatArray(0)

    // The native channel placer for the current format (0 = none), and the
    // direct buffers it reads planar input from and writes stereo into.
    private var placer = 0L
    private var placerRate = 0
    private var placerChannels = 0
    private var placerIn: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var placerOut: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    // What was last handed to it, so it is only told about changes.
    private var pushedPlacement: tf.monochrome.android.audio.dsp.spatial.SpatialPlacement? = null
    private var pushedPreampDb = Float.NaN
    private var pushedBinaural: Boolean? = null
    private var pushedRender = -1
    private var pushedTarget: FloatArray? = null

    // The Atmos profile's headphone settings, which the binaural placement
    // shares with the Atmos renderer. Set from PlaybackService.
    @Volatile private var hpStrength = 1f
    @Volatile private var hpHeight = true
    @Volatile private var hpBass = true
    @Volatile private var hpCrossover = 80
    @Volatile private var hpVersion = 0

    fun setHeadphoneRender(strength: Float, heightVirtualization: Boolean, bassManagement: Boolean, crossoverHz: Int) {
        hpStrength = strength
        hpHeight = heightVirtualization
        hpBass = bassManagement
        hpCrossover = crossoverHz
        hpVersion++
    }

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
     * Master trim (dB) baked into the coefficient rows — the preamp that
     * pulls the hot verbatim matrix below clipping (equivalent of the peqdb
     * Downmix Renderer's --master-gain-db). Applies on the fly at the next
     * buffer boundary; costs nothing per sample.
     */
    @Volatile
    private var preampDb: Float = 0f

    fun setPreampDb(db: Float) {
        preampDb = db
    }

    /**
     * Optional LFE path (the peqdb renderer's --lfe-filter-mode): Butterworth
     * 4th-order 125 Hz low-pass on the LFE feed, dry path delay-matched
     * before summing. Applies on the fly at the next buffer boundary. Adds
     * the filter's group delay (~3.3 ms) of output latency while enabled.
     */
    @Volatile
    private var lfeLowpassEnabled: Boolean = false

    fun setLfeLowpass(e: Boolean) {
        lfeLowpassEnabled = e
    }

    // LFE-path state, valid while lfeActive.
    private val lfeFilter = LfeLowPassFilter()
    private var lfeActive = false
    private var lfeIndex = -1
    private var lfeGainL = 0f
    private var lfeGainR = 0f

    // Snapshot of the settings the current coefficient rows were built from.
    // queueInput() compares against the volatiles at each buffer boundary and
    // rebuilds on the fly when the user changes preamp / LFE mode — no
    // reconfigure or seek needed (a small step discontinuity at the buffer
    // edge is the accepted cost of instant A/B). Only the enable toggle still
    // waits for a reconfigure, because it changes the output format itself.
    private var appliedPreampDb = Float.NaN
    private var appliedLfe = false

    /**
     * (Re)build the active coefficient rows from the current settings for
     * [inputFormat]. `resetLfeState` clears the filter/delay history — wanted
     * on flush (seek) and on LFE enable, not on a preamp-only rebuild while
     * the LFE path keeps running.
     */
    private fun rebuildCoefs(resetLfeState: Boolean) {
        val pre = preampDb
        val lfeOn = lfeLowpassEnabled
        val rows = COEF_TABLES[inputFormat.channelCount] ?: PLACE_COEF_TABLES.getValue(inputFormat.channelCount)
        val gain = 10f.pow(pre / 20f)
        coefL = FloatArray(rows.first.size) { rows.first[it] * gain }
        coefR = FloatArray(rows.second.size) { rows.second[it] * gain }
        lfeIndex = (KIND_TABLES[inputFormat.channelCount] ?: PLACE_KIND_TABLES.getValue(inputFormat.channelCount))
            .indexOf(Kind.LFE_CH)
        val nowActive = lfeOn && lfeIndex >= 0
        if (nowActive && (resetLfeState || !lfeActive)) {
            lfeFilter.configure(inputFormat.sampleRate)
        }
        lfeActive = nowActive
        if (lfeActive) {
            lfeGainL = coefL[lfeIndex]
            lfeGainR = coefR[lfeIndex]
            coefL[lfeIndex] = 0f
            coefR[lfeIndex] = 0f
        }
        appliedPreampDb = pre
        appliedLfe = lfeOn
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
        // 5.1.4 and 7.1.4 have no fixed fold here — they go to Android whole —
        // but with the spatial map on they are placed and folded like the rest.
        val placing = placement?.current?.enabled == true
        val foldable = KIND_TABLES.containsKey(inputAudioFormat.channelCount) ||
            (placing && PLACE_KIND_TABLES.containsKey(inputAudioFormat.channelCount))
        if (!enabled || inputAudioFormat.channelCount <= 2 || !foldable) {
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

        // On-the-fly settings: preamp / LFE changes apply at the next buffer
        // boundary instead of waiting for a reconfigure, so A/B-ing from the
        // Atmos page is instant.
        if (preampDb != appliedPreampDb || lfeLowpassEnabled != appliedLfe) {
            rebuildCoefs(resetLfeState = false)
        }

        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val spatial = placement?.current
        if (spatial != null && spatial.enabled && placer != 0L) {
            placeBlock(inputBuffer, numFrames, channels, isFloat, spatial)
            return
        }

        // Fused deinterleave + matrix + interleave via positional get*/put* —
        // no asShortBuffer()/asFloatBuffer() view allocations on the audio
        // thread (same rationale as MixBusProcessor's hot loop).
        val cL = coefL
        val cR = coefR
        if (foldL.size < numFrames) {
            foldL = FloatArray(numFrames)
            foldR = FloatArray(numFrames)
        }
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
            } else {
                for (c in 0 until channels) {
                    val s = inputBuffer.getShort(base + c * 2).toFloat() / 32768f
                    accL += cL[c] * s
                    accR += cR[c] * s
                }
            }
            // LFE low-pass path: the matrix rows carry 0 for the LFE while
            // active, so the fold above is the dry path — delay it and sum
            // the filtered LFE on top at the matrix gain.
            if (lfeActive) {
                val lfeS = if (isFloat) {
                    inputBuffer.getFloat(base + lfeIndex * 4)
                } else {
                    inputBuffer.getShort(base + lfeIndex * 2).toFloat() / 32768f
                }
                val f = lfeFilter.filterLfe(lfeS)
                accL = lfeFilter.delayDryL(accL) + lfeGainL * f
                accR = lfeFilter.delayDryR(accR) + lfeGainR * f
            }
            foldL[i] = accL
            foldR[i] = accR
        }
        inputBuffer.position(startPos + numFrames * frameSize)

        // Now stereo, the crossfeed can place its two speakers.
        crossfeed?.processArrays(foldL, foldR, numFrames)
        writeFold(numFrames, isFloat)
        outputBuffer.position(0)
        outputBuffer.limit(outBytes)
    }

    /** The folded block, [foldL]/[foldR], into [outputBuffer] in the stream's encoding. */
    private fun writeFold(numFrames: Int, isFloat: Boolean) {
        for (i in 0 until numFrames) {
            if (isFloat) {
                val off = i * 8
                outputBuffer.putFloat(off, foldL[i])
                outputBuffer.putFloat(off + 4, foldR[i])
            } else {
                val off = i * 4
                outputBuffer.putShort(off, (foldL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, (foldR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
    }

    /**
     * The spatial map's fold: the block handed to the native placer a chunk
     * at a time, planar, and its stereo read back. Ends like the matrix fold,
     * with the output set up to [numFrames] frames.
     */
    private fun placeBlock(
        input: ByteBuffer,
        numFrames: Int,
        channels: Int,
        isFloat: Boolean,
        spatial: tf.monochrome.android.audio.dsp.spatial.SpatialPlacement,
    ) {
        val binaural = pushPlacement(spatial, channels)
        if (foldL.size < numFrames) {
            foldL = FloatArray(numFrames)
            foldR = FloatArray(numFrames)
        }
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val startPos = input.position()
        var done = 0
        while (done < numFrames) {
            val n = minOf(PLACE_CHUNK, numFrames - done)
            for (i in 0 until n) {
                val base = startPos + (done + i) * frameSize
                for (c in 0 until channels) {
                    val v = if (isFloat) input.getFloat(base + c * 4)
                    else input.getShort(base + c * 2).toFloat() / 32768f
                    placerIn.putFloat((c * PLACE_CHUNK + i) * 4, v)
                }
            }
            tf.monochrome.android.audio.atmos.ChannelPlacerNative.nativeProcess(
                placer, placerIn, PLACE_CHUNK, n, placerOut,
            )
            for (i in 0 until n) {
                foldL[done + i] = placerOut.getFloat(i * 8)
                foldR[done + i] = placerOut.getFloat(i * 8 + 4)
            }
            done += n
        }
        input.position(startPos + numFrames * frameSize)
        // A binaural render already carries each ear's view of every channel;
        // crossfeed on top would blur it. A pan is plain stereo and takes it.
        if (!binaural) crossfeed?.processArrays(foldL, foldR, numFrames)
        writeFold(numFrames, isFloat)
        outputBuffer.position(0)
        outputBuffer.limit(numFrames * bytesPerSample * 2)
    }

    // Scratch for pushPlacement, sized for the widest bed.
    private val pushAz = FloatArray(MAX_INPUT_CHANNELS)
    private val pushEl = FloatArray(MAX_INPUT_CHANNELS)
    private val pushGain = FloatArray(MAX_INPUT_CHANNELS)

    /**
     * Tells the placer what changed since the last block, and returns whether
     * it is rendering binaurally. Only allocates (the layout lists) while the
     * map is being dragged.
     */
    private fun pushPlacement(spatial: tf.monochrome.android.audio.dsp.spatial.SpatialPlacement, channels: Int): Boolean {
        // The HRIR table is measured at 48 kHz; above that its pinna cues
        // would land an octave too high, so a hi-res stream is panned.
        val binaural = spatial.binaural && placerRate <= 48000
        val pre = preampDb
        if (spatial !== pushedPlacement || pre != pushedPreampDb) {
            val speakers = tf.monochrome.android.audio.dsp.spatial.SpatialLayout.speakers(channels)
            val placed = spatial.placementFor(channels)
            val preGain = 10f.pow(pre / 20f)
            for (c in 0 until channels) {
                val p = placed.getOrNull(c)
                pushAz[c] = Math.toRadians((p?.azimuthDeg ?: 0f).toDouble()).toFloat()
                pushEl[c] = Math.toRadians(speakers.getOrNull(c)?.elevationDeg?.toDouble() ?: 0.0).toFloat()
                // The LFE keeps the fold's own level for it.
                val lfe = speakers.getOrNull(c)?.isLfe == true
                pushGain[c] = tf.monochrome.android.audio.dsp.spatial.SpatialLayout.gainFor(p?.distance ?: 1f) *
                    preGain * (if (lfe) LFE_COEF else 1f)
            }
            tf.monochrome.android.audio.atmos.ChannelPlacerNative.nativeSetPlacement(
                placer, pushAz.copyOf(channels), pushEl.copyOf(channels), pushGain.copyOf(channels),
            )
            pushedPlacement = spatial
            pushedPreampDb = pre
        }
        // The headphone target: worked out by the store, handed on here.
        val target = placement?.targetCurve
        if (target != null && target !== pushedTarget) {
            tf.monochrome.android.audio.atmos.ChannelPlacerNative.nativeSetTarget(placer, target)
            pushedTarget = target
        }
        val render = hpVersion
        if (binaural != pushedBinaural || render != pushedRender) {
            tf.monochrome.android.audio.atmos.ChannelPlacerNative.nativeSetMode(
                placer, binaural, hpStrength, hpHeight, hpBass, hpCrossover,
            )
            pushedBinaural = binaural
            pushedRender = render
        }
        return binaural
    }

    /** A placer for the current format, when there is a map to play and a library to play it. */
    private fun ensurePlacer() {
        val fmt = inputFormat
        val ch = fmt.channelCount
        val wanted = placement != null && ch in 3..MAX_INPUT_CHANNELS &&
            tf.monochrome.android.audio.atmos.ChannelPlacerNative.available
        if (!wanted) {
            releasePlacer()
            return
        }
        if (placer != 0L && placerRate == fmt.sampleRate && placerChannels == ch) {
            tf.monochrome.android.audio.atmos.ChannelPlacerNative.nativeReset(placer)
            return
        }
        releasePlacer()
        placer = tf.monochrome.android.audio.atmos.ChannelPlacerNative.create(
            fmt.sampleRate, ch, tf.monochrome.android.audio.dsp.spatial.SpatialLayout.lfeIndex(ch),
        )
        if (placer == 0L) return
        placerRate = fmt.sampleRate
        placerChannels = ch
        placerIn = ByteBuffer.allocateDirect(ch * PLACE_CHUNK * 4).order(ByteOrder.nativeOrder())
        placerOut = ByteBuffer.allocateDirect(PLACE_CHUNK * 8).order(ByteOrder.nativeOrder())
        pushedPlacement = null
        pushedPreampDb = Float.NaN
        pushedBinaural = null
        pushedRender = -1
        pushedTarget = null
    }

    private fun releasePlacer() {
        if (placer != 0L) tf.monochrome.android.audio.atmos.ChannelPlacerNative.nativeDestroy(placer)
        placer = 0L
        placerRate = 0
        placerChannels = 0
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
            // Seek/reconfigure: rebuild rows and clear LFE history so no
            // pre-seek audio leaks out of the filter or the dry delay.
            rebuildCoefs(resetLfeState = true)
            // And the placer's convolution history, or a new one for a new format.
            ensurePlacer()
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        coefL = FloatArray(0)
        coefR = FloatArray(0)
        lfeActive = false
        lfeIndex = -1
        lfeFilter.reset()
        releasePlacer()
    }

    companion object {
        const val MAX_INPUT_CHANNELS = 16

        /** −3 dB: the FC (and 6.1 BC) contribution to each side. */
        private const val CENTER_COEF = 0.70710678f

        /** LFE contribution to BOTH sides of the fold (~+7.1 dB). */
        private const val LFE_COEF = 2.26464431f

        /** Frames per trip to the native placer. */
        private const val PLACE_CHUNK = 1024

        /** Position class of one input channel; the matrix derives from it. */
        private enum class Kind { L_FRONT, R_FRONT, CENTER, LFE_CH, L_SURR, R_SURR, C_SURR }

        // Channel classes per input count. Assumed orders (FLAC / FFmpeg /
        // Android canonical, which agree for 3–8):
        //   3:  FL FR FC
        //   4:  FL FR BL BR            (quad)
        //   5:  FL FR FC BL BR
        //   6:  FL FR FC LFE BL BR     (5.1; 5.1-side folds identically)
        //   7:  FL FR FC LFE BC SL SR  (6.1)
        //   8:  FL FR FC LFE BL BR SL SR (7.1)
        //   16: FL FR FC LFE BL BR FLC FRC SL SR TFL TFR TBL TBR TSL TSR (9.1.6, FFmpeg order)
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
        private fun gains(k: Kind): Pair<Float, Float> = when (k) {
            Kind.L_FRONT, Kind.L_SURR -> 1f to 0f
            Kind.R_FRONT, Kind.R_SURR -> 0f to 1f
            Kind.CENTER, Kind.C_SURR -> CENTER_COEF to CENTER_COEF
            Kind.LFE_CH -> LFE_COEF to LFE_COEF
        }

        // Computed once at class load; the audio thread only indexes.
        private val COEF_TABLES: Map<Int, Pair<FloatArray, FloatArray>> =
            KIND_TABLES.mapValues { (_, kinds) ->
                Pair(
                    FloatArray(kinds.size) { gains(kinds[it]).first },
                    FloatArray(kinds.size) { gains(kinds[it]).second },
                )
            }

        // 5.1.4 and 7.1.4 (Android's order), folded only while the spatial
        // map is on: without it they go to Android whole, as they always did.
        // The same classes, so the map's off switch mid-song folds them the
        // way the matrix folds everything else.
        //   10: FL FR FC LFE BL BR TFL TFR TBL TBR
        //   12: FL FR FC LFE BL BR SL SR TFL TFR TBL TBR
        private val PLACE_KIND_TABLES: Map<Int, Array<Kind>> = mapOf(
            10 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR, Kind.L_FRONT, Kind.R_FRONT,
                Kind.L_SURR, Kind.R_SURR,
            ),
            12 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
                Kind.L_FRONT, Kind.R_FRONT, Kind.L_SURR, Kind.R_SURR,
            ),
        )
        private val PLACE_COEF_TABLES: Map<Int, Pair<FloatArray, FloatArray>> =
            PLACE_KIND_TABLES.mapValues { (_, kinds) ->
                Pair(
                    FloatArray(kinds.size) { gains(kinds[it]).first },
                    FloatArray(kinds.size) { gains(kinds[it]).second },
                )
            }
    }
}
