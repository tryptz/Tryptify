package tf.monochrome.android.audio.atmos

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.RendererMode
import tf.monochrome.android.domain.model.RendererProfile
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

    init {
        preferences.rendererProfile
            .onEach { updated ->
                profile = updated
                pushParams()
            }
            .launchIn(scope)
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
        AtmosNative.nativeSetRenderParams(
            p,
            cur.mode.ordinal,
            cur.stereoDownmix.ordinal,
            cur.binauralStrength,
            cur.heightVirtualization,
            cur.lfeGainDb,
            cur.bassManagement,
            cur.crossoverHz,
            cur.drc.ordinal,
            cur.dialogNormalization,
        )
        android.util.Log.i(
            TAG,
            "params -> mode=${cur.mode} downmix=${cur.stereoDownmix} " +
                "strength=${cur.binauralStrength} height=${cur.heightVirtualization} " +
                "lfe=${cur.lfeGainDb}dB bass=${cur.bassManagement}@${cur.crossoverHz}Hz " +
                "drc=${cur.drc} dialnorm=${cur.dialogNormalization}",
        )
    }

    private var pipeline: Long = 0L
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

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
        if (inputAudioFormat.channelCount <= 2 ||
            !AtmosNative.isAvailable ||
            profile.mode == RendererMode.PASSTHROUGH
        ) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
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

        val outBytes = outFrames * FRAME_SAMPLES * 2 * bytesPerSample
        val out = acquireOutput(outBytes)
        if (frameScratch.size < FRAME_SAMPLES * channels) frameScratch = FloatArray(FRAME_SAMPLES * channels)

        for (f in 0 until outFrames) {
            System.arraycopy(bed, f * FRAME_SAMPLES * channels, frameScratch, 0, FRAME_SAMPLES * channels)
            val raw = frameBuffer.poll() ?: emptyFrame
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
                    "frames rendered=$renderedFrames fallback=$fallbackFrames tapQueue=${frameBuffer.size()}")
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
        frameBuffer.clear()
        if (pendingFormat == AudioFormat.NOT_SET) return
        val formatChanged = inputFormat == AudioFormat.NOT_SET ||
            inputFormat.sampleRate != pendingFormat.sampleRate
        inputFormat = pendingFormat
        if (formatChanged) {
            if (pipeline != 0L) AtmosNative.nativePipelineDestroy(pipeline)
            pipeline = AtmosNative.nativePipelineCreate(inputFormat.sampleRate, MAX_OBJECTS)
            pushParams()  // a fresh pipeline starts at defaults — apply the profile
        }
        pendingFormat = AudioFormat.NOT_SET
    }

    override fun reset() {
        flush()
        if (pipeline != 0L) { AtmosNative.nativePipelineDestroy(pipeline); pipeline = 0L }
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        bed = FloatArray(0)
        bedSamples = 0
        renderedFrames = 0L
        fallbackFrames = 0L
    }

    // ── helpers ──────────────────────────────────────────────────────────

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

    // ITU-style 5.1 fold-down for frames without JOC. Channel order follows the
    // decoder's (FL FR FC LFE SL SR …); extra channels beyond 6 are ignored.
    private fun downmixToStereo(frame: FloatArray, channels: Int) {
        val c = channels
        for (i in 0 until FRAME_SAMPLES) {
            val b = i * c
            if (c >= 6) {
                val fc = 0.707f * frame[b + 2]
                stereo[2 * i] = frame[b] + fc + 0.707f * frame[b + 4]
                stereo[2 * i + 1] = frame[b + 1] + fc + 0.707f * frame[b + 5]
            } else {
                stereo[2 * i] = frame[b]
                stereo[2 * i + 1] = if (c > 1) frame[b + 1] else frame[b]
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

    private companion object {
        const val FRAME_SAMPLES = 1536   // 6 blocks * 256 samples per E-AC-3 frame
        const val MAX_OBJECTS = 16
        const val TAG = "AtmosProcessor"
        const val STATS_EVERY = 300L     // ~9.6 s of frames
    }
}
