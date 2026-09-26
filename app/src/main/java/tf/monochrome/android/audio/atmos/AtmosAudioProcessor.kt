package tf.monochrome.android.audio.atmos

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.ChannelLayout
import tf.monochrome.android.domain.model.RendererMode
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.StereoDownmixMode
import tf.monochrome.android.domain.model.speakers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3 [AudioProcessor] that renders Dolby Atmos to binaural stereo. It sits at
 * the FRONT of the DefaultAudioSink processor chain, so it receives the decoded
 * multichannel bed PCM (from NextLib's FfmpegAudioRenderer) before any downmix.
 * Per E-AC-3 frame (1536 samples) it pulls the matching raw frame that the sample
 * tap ([AtmosTapMediaSourceFactory]) preserved and calls
 * [AtmosNative.nativeProcessFrame], which reconstructs the objects (JOC) and
 * renders them to binaural stereo via the native pipeline. Frames without JOC
 * (plain multichannel) fall back to an ITU stereo downmix, so audio never drops.
 *
 * Active only for >2-channel input; stereo/mono passes straight through
 * (configure returns NOT_SET), leaving non-Atmos playback untouched.
 *
 * Speaker output: when the profile's speaker render is on and the effective
 * layout is multichannel ([RendererProfile.speakerLayout] — manual, or the
 * connected HDMI/USB device's channel count via [deviceChannelCount]), the
 * objects are rendered to that layout instead ([AtmosNative.nativeProcessFrameSpeakers])
 * and the processor outputs [ChannelLayout.sinkChannelCount] channels in
 * Android mask order; [activeLayout] tells the AudioTrack provider which mask
 * to put on the track.
 */
@Singleton
@OptIn(UnstableApi::class)
class AtmosAudioProcessor @Inject constructor(
    private val frameBuffer: AtmosFrameBuffer,
    preferences: PreferencesManager,
) : AudioProcessor {

    // The user's settings from the Atmos Renderer Configuration screen. Kept in a
    // volatile snapshot so the audio thread never touches DataStore, and pushed
    // into the native pipeline whenever it changes.
    @Volatile private var profile: RendererProfile = RendererProfile.DEFAULT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Custom HRTF (a user .sofa copied into app storage; its path is the
    // profile's hrtfProfileId). The bytes are read here on the Default
    // dispatcher — never on the audio thread — and applied to the native
    // pipeline via nativeLoadSofa. null path = the baked default HRTF.
    @Volatile private var sofaBytes: ByteArray? = null
    private var loadedSofaPath: String? = null
    // False when the live pipeline does not yet reflect [sofaBytes] — set on a
    // SOFA change or a fresh pipeline, cleared once (re)applied.
    @Volatile private var sofaApplied = false

    /**
     * Last custom-SOFA apply outcome, for the renderer UI: path → accepted.
     * Null while no custom SOFA is selected (built-in HRTF) or before the
     * first apply. Rejections were previously logcat-only, so the screen
     * claimed "custom" while the render actually ran the baked KEMAR set.
     */
    private val _sofaStatus = MutableStateFlow<Pair<String, Boolean>?>(null)
    val sofaStatus: StateFlow<Pair<String, Boolean>?> = _sofaStatus.asStateFlow()

    init {
        preferences.rendererProfile
            .onEach { updated ->
                profile = updated
                refreshSofa(updated.hrtfProfileId)
                pushParams()
            }
            .launchIn(scope)
    }

    /** Reads the selected .sofa off the audio thread; caches its bytes. */
    private fun refreshSofa(path: String?) {
        if (path == loadedSofaPath) return
        loadedSofaPath = path
        sofaBytes = path?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }
        sofaApplied = false
        _sofaStatus.value = when {
            path == null -> null                    // built-in HRTF selected
            sofaBytes == null -> path to false      // unreadable file = rejected
            else -> null                            // pending until applied
        }
    }

    /** Loads the cached SOFA into [p] (or reverts to baked); once per change. */
    private fun applySofa(p: Long) {
        if (p == 0L || sofaApplied) return
        val bytes = sofaBytes
        if (bytes != null) {
            val ok = AtmosNative.nativeLoadSofa(p, bytes) == 1
            android.util.Log.i(TAG, "custom HRTF ${if (ok) "loaded" else "REJECTED"} (${bytes.size}B)")
            loadedSofaPath?.let { _sofaStatus.value = it to ok }
        } else {
            AtmosNative.nativeClearSofa(p)
        }
        sofaApplied = true
    }

    /** Sends the current profile to the native renderer (no-op without a pipeline). */
    private fun pushParams() {
        val p = pipeline
        val cur = profile
        if (p == 0L) {
            // Worth logging: a profile change while no pipeline exists (nothing
            // playing, or PASSTHROUGH) silently does nothing until flush().
            android.util.Log.d(TAG, "profile update ignored — no pipeline (mode=${cur.mode})")
            return
        }
        // The downmix pushed to native is DERIVED from the HRTF mode, never
        // picked directly: an active HRTF (built-in or SOFA) means the
        // binaural render runs; HRTF off means the ONE fixed fold matrix
        // runs (per the peqdb Downmix Renderer spec — there is no matrix
        // choice) — the pipeline declines the frame (process() returns -1)
        // and the fixed-matrix fold-down handles it, with headphone shaping
        // left entirely to the built-in AutoEQ chain. Strength 0 alone is
        // NOT a plain fold — the native dry path is an ITD-only render that
        // still places objects with per-ear arrival-time delays — hence the
        // hard demotion.
        val strength = if (cur.hrtfEnabled) cur.binauralStrength else 0f
        val downmix = if (cur.hrtfEnabled) {
            StereoDownmixMode.BINAURAL
        } else {
            StereoDownmixMode.LO_RO
        }
        // The speaker render replaces the stereo paths entirely, so the "HRTF
        // off = passthrough" lockstep must not switch it off.
        val mode = if (activeLayout.isMultichannel && cur.mode == RendererMode.PASSTHROUGH) {
            RendererMode.OBJECT_RENDER
        } else {
            cur.mode
        }
        AtmosNative.nativeSetRenderParams(
            p,
            mode.ordinal,
            downmix.ordinal,
            strength,
            cur.heightVirtualization,
            cur.lfeGainDb,
            cur.bassManagement,
            cur.crossoverHz,
            cur.drc.ordinal,
            cur.dialogNormalization,
        )
        applySofa(p)
        android.util.Log.i(
            TAG,
            "params -> mode=$mode layout=${activeLayout.label} downmix=$downmix " +
                "strength=$strength height=${cur.heightVirtualization} " +
                "lfe=${cur.lfeGainDb}dB bass=${cur.bassManagement}@${cur.crossoverHz}Hz " +
                "drc=${cur.drc} dialnorm=${cur.dialogNormalization} " +
                "hrtf=${when {
                    !cur.hrtfEnabled -> "off (AutoEQ only)"
                    sofaBytes != null -> "custom"
                    else -> "built-in"
                }}",
        )
    }

    /**
     * Largest channel count the current HDMI/USB output reports, or null for
     * none / built-in outputs. Pushed by PlaybackService from an
     * AudioDeviceCallback; read in configure().
     */
    @Volatile var deviceChannelCount: Int? = null

    /** The layout the processor currently outputs; STEREO = binaural/fold path. */
    @Volatile var activeLayout: ChannelLayout = ChannelLayout.STEREO
        private set
    private var pendingLayout: ChannelLayout = ChannelLayout.STEREO
    private var speakerOut = FloatArray(0)      // one frame, layout speaker order
    private var sinkFrame = FloatArray(0)       // one frame, sink slot order (padded)
    private var speakerSlots = IntArray(0)

    private var pipeline: Long = 0L
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Optional LFE low-pass for the fallback fold (profile.lfeLowpass):
    // configured lazily to the stream rate on first use, reset on flush.
    private val lfeFold = tf.monochrome.android.audio.dsp.LfeLowPassFilter()
    private var lfeFoldRate = 0

    // Interleaved bed accumulation (grows to a few frames, reused).
    private var bed = FloatArray(0)
    private var bedSamples = 0
    private var frameScratch = FloatArray(0)                    // one frame, interleaved
    private val stereo = FloatArray(2 * FRAME_SAMPLES)          // native render output
    private val emptyFrame = ByteArray(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Only multichannel (an Atmos bed) is handled; ≤2ch is not Atmos. The
        // profile's PASSTHROUGH mode ("Direct") also drops us out of the
        // pipeline entirely, so the user's choice is honoured bit-perfectly.
        val speakers = profile.speakerLayout(deviceChannelCount).let {
            // Before API 32 Media3 rejects a 24-channel sink (no mask for it),
            // so 9.1.x falls back to the largest layout that still fits.
            if (android.os.Build.VERSION.SDK_INT < 32 && it.sinkChannelCount == 24) ChannelLayout.ATMOS_7_1_4 else it
        }
        if (inputAudioFormat.channelCount <= 2 ||
            !AtmosNative.isAvailable ||
            (profile.mode == RendererMode.PASSTHROUGH && !speakers.isMultichannel)
        ) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            pendingLayout = ChannelLayout.STEREO
            activeLayout = ChannelLayout.STEREO
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        // Takes effect at flush(), like the format: the previous stream may
        // still be draining through queueInput until then. DefaultAudioSink
        // flushes the processors before it builds the new AudioTrack, so the
        // track provider sees the new layout in time.
        pendingLayout = speakers
        val outChannels = if (speakers.isMultichannel) speakers.sinkChannelCount else 2
        return AudioFormat(inputAudioFormat.sampleRate, outChannels, inputAudioFormat.encoding)
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputFormat == AudioFormat.NOT_SET) return
        val channels = inputFormat.channelCount
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        val inFrames = inputBuffer.remaining() / frameBytes
        if (inFrames == 0) { outputBuffer = AudioProcessor.EMPTY_BUFFER; return }

        // Append input to the interleaved bed accumulation as float.
        ensureBed((bedSamples + inFrames) * channels)
        val start = inputBuffer.position()
        var w = bedSamples * channels
        if (isFloat) {
            for (i in 0 until inFrames * channels) bed[w++] = inputBuffer.getFloat(start + i * 4)
        } else {
            for (i in 0 until inFrames * channels) bed[w++] = inputBuffer.getShort(start + i * 2) / 32768f
        }
        inputBuffer.position(start + inFrames * frameBytes)
        bedSamples += inFrames

        val outFrames = bedSamples / FRAME_SAMPLES
        if (outFrames == 0) { outputBuffer = AudioProcessor.EMPTY_BUFFER; return }

        if (activeLayout.isMultichannel) {
            queueSpeakerFrames(outFrames, channels, isFloat, bytesPerSample)
            return
        }

        val outBytes = outFrames * FRAME_SAMPLES * 2 * bytesPerSample
        val out = acquireOutput(outBytes)
        if (frameScratch.size < FRAME_SAMPLES * channels) frameScratch = FloatArray(FRAME_SAMPLES * channels)

        for (f in 0 until outFrames) {
            System.arraycopy(bed, f * FRAME_SAMPLES * channels, frameScratch, 0, FRAME_SAMPLES * channels)
            val raw = nextRawFrame()
            val rc = if (pipeline != 0L) {
                AtmosNative.nativeProcessFrame(pipeline, raw, frameScratch, channels, FRAME_SAMPLES, stereo)
            } else -1
            if (rc == 1) {
                if (renderedFrames == 0L) {
                    android.util.Log.i(TAG, "Atmos render ACTIVE — objects binauralized (${channels}ch bed)")
                }
                renderedFrames++
            } else {
                downmixToStereo(frameScratch, channels)  // non-Atmos / no pipeline / no raw frame
                fallbackFrames++
            }
            if ((renderedFrames + fallbackFrames) % STATS_EVERY == 0L) {
                android.util.Log.d(
                    TAG,
                    "frames rendered=$renderedFrames fallback=$fallbackFrames " +
                        "tapQueue=${frameBuffer.size()} gotFrame=$gotFrameCount " +
                        "noFrame=$noFrameCount lastFrameBytes=$lastFrameBytes " +
                        "anchorUs=$anchorUs")
            }
            writeStereoFrame(out, isFloat)
        }
        // Absolute puts don't move position; set the window explicitly.
        out.limit(outWritePos)
        out.position(0)
        outputBuffer = out

        // Keep the sub-frame remainder for the next call.
        val consumed = outFrames * FRAME_SAMPLES
        val remain = bedSamples - consumed
        if (remain > 0) System.arraycopy(bed, consumed * channels, bed, 0, remain * channels)
        bedSamples = remain
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
        bedSamples = 0
        lfeFold.reset()  // no pre-seek audio in the LFE filter / dry delay
        frameBuffer.clear()
        anchorUs = Long.MIN_VALUE
        anchorSamples = 0L
        lastRaw = null
        if (pendingFormat == AudioFormat.NOT_SET) {
            // A seek within the same format flushes without reconfiguring:
            // clear the native time history so no pre-seek audio leaks out of
            // the latency-matching delay or the convolution tails.
            if (pipeline != 0L) AtmosNative.nativePipelineFlush(pipeline)
            return
        }
        val formatChanged = inputFormat == AudioFormat.NOT_SET ||
            inputFormat.sampleRate != pendingFormat.sampleRate
        inputFormat = pendingFormat
        val layout = pendingLayout
        activeLayout = layout
        if (formatChanged) {
            if (pipeline != 0L) AtmosNative.nativePipelineDestroy(pipeline)
            pipeline = AtmosNative.nativePipelineCreate(inputFormat.sampleRate, MAX_OBJECTS)
            sofaApplied = false  // a fresh pipeline has the baked HRTF; re-apply any custom one
        } else if (pipeline != 0L) {
            AtmosNative.nativePipelineFlush(pipeline)
        }
        if (layout.isMultichannel) {
            // Off the per-frame path: sizes the native renderer and our buffers once.
            if (pipeline != 0L) AtmosNative.nativeSetOutputLayout(pipeline, layout.nativeId)
            speakerOut = FloatArray(layout.channelCount * FRAME_SAMPLES)
            sinkFrame = FloatArray(layout.sinkChannelCount * FRAME_SAMPLES)
            speakerSlots = layout.speakerSlots()
        }
        pushParams()  // (re)apply the profile: fresh pipeline, or the mode the layout needs
        pendingFormat = AudioFormat.NOT_SET
    }

    override fun reset() {
        flush()
        if (pipeline != 0L) { AtmosNative.nativePipelineDestroy(pipeline); pipeline = 0L }
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        pendingLayout = ChannelLayout.STEREO
        activeLayout = ChannelLayout.STEREO
        bed = FloatArray(0)
        bedSamples = 0
        renderedFrames = 0L
        fallbackFrames = 0L
    }

    // ── helpers ──────────────────────────────────────────────────────────

    // Time-keyed frame association state. The raw frames are matched to the
    // decoded PCM by presentation time, not by arrival order: an anchor is taken
    // from the first tapped frame after a flush, and each 1536-sample output
    // chunk advances an anchored clock that keys frameForTime. Ordering-based
    // poll() assumed a strict 1:1 tap↔decode correspondence, which silently
    // misaligns metadata if the decoder ever drops or merges an access unit.
    private var anchorUs = Long.MIN_VALUE  // presentation time of output sample 0
    private var anchorSamples = 0L         // samples output since the anchor
    private var lastRaw: ByteArray? = null

    /**
     * The raw E-AC-3 frame covering the next [FRAME_SAMPLES] output samples, or
     * the empty frame when none applies — the native metadata hold then renders
     * with the last-seen JOC/OAMD, which is the DD+ "hold until update" rule.
     */
    private fun nextRawFrame(): ByteArray {
        val rate = inputFormat.sampleRate.toLong()
        if (anchorUs == Long.MIN_VALUE) {
            val t = frameBuffer.peekTimeUs()
            if (t == Long.MIN_VALUE) { noFrameCount++; return emptyFrame }  // tap not started yet
            anchorUs = t
            anchorSamples = 0L
        }
        val expectedUs = anchorUs + anchorSamples * 1_000_000L / rate
        anchorSamples += FRAME_SAMPLES
        // Query at the chunk's midpoint so container timestamp jitter in either
        // direction still resolves to the covering frame.
        val slackUs = FRAME_SAMPLES * 1_000_000L / (2 * rate)
        val entry = frameBuffer.frameForTime(expectedUs + slackUs)
        if (entry == null) {
            // Every buffered frame is newer than the anchored clock (the ring
            // overflowed, or the stream jumped). Drop the anchor; the next chunk
            // re-anchors on whatever the tap holds then.
            anchorUs = Long.MIN_VALUE
            noFrameCount++
            return emptyFrame
        }
        // A gapless track change never flushes the sink, but restarts the tapped
        // timestamps — re-anchor the clock on the entry instead of drifting.
        if (entry.timeUs - expectedUs > RESYNC_US || expectedUs - entry.timeUs > RESYNC_US) {
            anchorUs = entry.timeUs
            anchorSamples = FRAME_SAMPLES.toLong()
        }
        gotFrameCount++
        lastFrameBytes = entry.bytes.size
        // The same frame can cover consecutive queries around a boundary; pass
        // the empty frame for the repeat so the EMDF isn't re-parsed (the native
        // hold keeps rendering with it).
        if (entry.bytes === lastRaw) return emptyFrame
        lastRaw = entry.bytes
        return entry.bytes
    }

    // ── speaker output ───────────────────────────────────────────────────

    /** Renders [outFrames] frames to [activeLayout] and publishes the output. */
    private fun queueSpeakerFrames(outFrames: Int, channels: Int, isFloat: Boolean, bytesPerSample: Int) {
        val layout = activeLayout
        val sinkChannels = layout.sinkChannelCount
        val out = acquireOutput(outFrames * FRAME_SAMPLES * sinkChannels * bytesPerSample)
        if (frameScratch.size < FRAME_SAMPLES * channels) frameScratch = FloatArray(FRAME_SAMPLES * channels)
        for (f in 0 until outFrames) {
            System.arraycopy(bed, f * FRAME_SAMPLES * channels, frameScratch, 0, FRAME_SAMPLES * channels)
            val raw = nextRawFrame()
            val rc = if (pipeline != 0L) {
                AtmosNative.nativeProcessFrameSpeakers(
                    pipeline, raw, frameScratch, channels, FRAME_SAMPLES, speakerOut, layout.channelCount,
                )
            } else -1
            if (rc == 1) {
                if (renderedFrames == 0L) {
                    android.util.Log.i(TAG, "Atmos render ACTIVE — objects to ${layout.label} speakers (${channels}ch bed)")
                }
                renderedFrames++
            } else {
                bedToSpeakers(frameScratch, channels, layout)  // no pipeline: play the bed on the layout
                fallbackFrames++
            }
            toSinkOrder(layout)
            writeFrame(out, isFloat, sinkFrame, sinkChannels * FRAME_SAMPLES)
        }
        out.limit(outWritePos)
        out.position(0)
        outputBuffer = out
        val consumed = outFrames * FRAME_SAMPLES
        val remain = bedSamples - consumed
        if (remain > 0) System.arraycopy(bed, consumed * channels, bed, 0, remain * channels)
        bedSamples = remain
    }

    /** Scatters [speakerOut] (speaker order) into the padded sink frame. */
    private fun toSinkOrder(layout: ChannelLayout) {
        val speakers = layout.channelCount
        val sink = layout.sinkChannelCount
        if (speakers == sink) {
            System.arraycopy(speakerOut, 0, sinkFrame, 0, speakers * FRAME_SAMPLES)
            return
        }
        java.util.Arrays.fill(sinkFrame, 0, sink * FRAME_SAMPLES, 0f)
        for (i in 0 until FRAME_SAMPLES) {
            val src = i * speakers
            val dst = i * sink
            for (c in 0 until speakers) sinkFrame[dst + speakerSlots[c]] = speakerOut[src + c]
        }
    }

    /**
     * Pipeline-less fallback: the decoded bed (FL FR FC LFE SL SR BL BR) placed
     * on the layout's matching speakers — 7.1 rears onto a 5.x layout's
     * surrounds, a 5.1 bed's surrounds onto a 7.x layout's sides.
     */
    private fun bedToSpeakers(frame: FloatArray, channels: Int, layout: ChannelLayout) {
        val speakers = layout.channelCount
        java.util.Arrays.fill(speakerOut, 0, speakers * FRAME_SAMPLES, 0f)
        val fiveX = layout.speakers().getOrNull(4)?.label == "Ls"
        // Speaker-order index for each decoder bed channel.
        val target = intArrayOf(0, 1, 2, 3, if (fiveX) 4 else 6, if (fiveX) 5 else 7, 4, 5)
        for (i in 0 until FRAME_SAMPLES) {
            val b = i * channels
            val o = i * speakers
            for (c in 0 until minOf(channels, 8)) {
                speakerOut[o + target[c]] += frame[b + c]
            }
        }
    }

    private fun writeFrame(out: ByteBuffer, isFloat: Boolean, src: FloatArray, count: Int) {
        if (isFloat) {
            for (i in 0 until count) { out.putFloat(outWritePos, src[i]); outWritePos += 4 }
        } else {
            for (i in 0 until count) {
                val s = (src[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                out.putShort(outWritePos, s); outWritePos += 2
            }
        }
    }

    private var outputScratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outWritePos = 0

    private fun acquireOutput(bytes: Int): ByteBuffer {
        if (outputScratch.capacity() < bytes) {
            outputScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputScratch.clear()
        }
        outWritePos = 0
        return outputScratch
    }

    private fun writeStereoFrame(out: ByteBuffer, isFloat: Boolean) {
        if (isFloat) {
            for (i in 0 until 2 * FRAME_SAMPLES) { out.putFloat(outWritePos, stereo[i]); outWritePos += 4 }
        } else {
            for (i in 0 until 2 * FRAME_SAMPLES) {
                val s = (stereo[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                out.putShort(outWritePos, s); outWritePos += 2
            }
        }
    }

    // Fold-down for frames the pipeline declines (no JOC / HRTF off /
    // passthrough): the ONE fixed matrix (matches native
    // AtmosPipeline::render_downmix and the peqdb Downmix Renderer's ADC2
    // direct matrix) — sides/backs hard-panned at unity, FC at 0.70710678 to
    // both, LFE at 2.26464431 to both. Channel order follows the decoder's
    // (FL FR FC LFE SL SR BL BR).
    private fun downmixToStereo(frame: FloatArray, channels: Int) {
        val c = channels
        val cur = profile
        // Downmix preamp (--master-gain-db equivalent): one linear gain on
        // the fold output, computed once per 1536-sample frame.
        val preamp = Math.pow(10.0, cur.downmixPreampDb / 20.0).toFloat()
        // Optional LFE path: low-pass the LFE feed and delay the dry fold to
        // match. Lazily (re)configured — the toggle can flip mid-stream.
        val lfeLp = cur.lfeLowpass && c >= 6
        if (lfeLp && lfeFoldRate != inputFormat.sampleRate) {
            lfeFoldRate = inputFormat.sampleRate
            lfeFold.configure(lfeFoldRate)
        }
        for (i in 0 until FRAME_SAMPLES) {
            val b = i * c
            if (c >= 6) {
                val fc = 0.70710678f * frame[b + 2]
                val lfeIn = frame[b + 3]
                // Surround sums per side (SL+BL / SR+BR when 7.1).
                var sl = frame[b + 4]
                var sr = frame[b + 5]
                if (c >= 8) {
                    sl += frame[b + 6]
                    sr += frame[b + 7]
                }
                var l = frame[b] + fc + sl
                var r = frame[b + 1] + fc + sr
                if (lfeLp) {
                    val f = 2.26464431f * lfeFold.filterLfe(lfeIn)
                    l = lfeFold.delayDryL(l) + f
                    r = lfeFold.delayDryR(r) + f
                } else {
                    l += 2.26464431f * lfeIn
                    r += 2.26464431f * lfeIn
                }
                stereo[2 * i] = l * preamp
                stereo[2 * i + 1] = r * preamp
            } else {
                stereo[2 * i] = frame[b] * preamp
                stereo[2 * i + 1] = (if (c > 1) frame[b + 1] else frame[b]) * preamp
            }
        }
    }

    private fun ensureBed(floats: Int) {
        if (bed.size < floats) {
            val grown = FloatArray(floats)
            System.arraycopy(bed, 0, grown, 0, bedSamples * (inputFormat.channelCount.coerceAtLeast(1)))
            bed = grown
        }
    }

    // Diagnostics: how many frames actually rendered as Atmos vs fell back to a
    // downmix. The first successful render logs once; totals log periodically, so
    // a logcat during playback shows immediately whether the tap + pipeline are
    // working end to end.
    private var renderedFrames = 0L
    private var fallbackFrames = 0L
    // Split the fallback cause: no raw frame available (tap/consumer mismatch)
    // vs. a frame that simply carries no JOC.
    private var gotFrameCount = 0L
    private var noFrameCount = 0L
    private var lastFrameBytes = 0

    private companion object {
        const val FRAME_SAMPLES = 1536   // 6 blocks * 256 samples per E-AC-3 frame
        const val MAX_OBJECTS = 16
        const val TAG = "AtmosProcessor"
        const val STATS_EVERY = 300L     // ~9.6 s of frames
        // Anchored-clock vs tapped-timestamp divergence that forces a re-anchor.
        // Jitter is sub-millisecond; only a real discontinuity crosses this.
        const val RESYNC_US = 250_000L
    }
}
