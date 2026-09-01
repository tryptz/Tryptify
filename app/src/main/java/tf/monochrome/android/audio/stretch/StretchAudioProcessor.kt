package tf.monochrome.android.audio.stretch

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Transposition that leaves the tempo alone, via signalsmith-stretch.
 *
 * The other two ways this app can move pitch both couple it to something else:
 * resampling drags the tempo with it, and Sonic's WSOLA only stretches time.
 * This is the one that decouples them, at the cost of being a phase vocoder —
 * so its accuracy is set by how finely it resolves a partial rather than by
 * arithmetic. The native side picks a 0.35 s analysis block for that reason:
 * measured worst-case error 0.179 Hz over semitones -12..+12 and fundamentals
 * 82 Hz..3.5 kHz, against 1.348 Hz at the library's default 0.12 s block.
 *
 * That block buys its precision with latency — about 350 ms round trip, which
 * [latencyFrames] reports so the AutoEQ pre-warp can glide over the same window
 * instead of re-warping before the pitch change is audible.
 *
 * Frame count in equals frame count out; only the spectrum moves.
 */
@Singleton
@OptIn(UnstableApi::class)
class StretchAudioProcessor @Inject constructor() : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    /**
     * The buffer handed downstream, or EMPTY when nothing is pending.
     *
     * An alias for [buffer] rather than storage of its own -- see [ensureOutput]
     * for why the two are separate.
     */
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /**
     * The one output buffer, kept across blocks.
     *
     * [getOutput] has to leave [outputBuffer] empty -- that is how the sink is
     * told there is nothing more this round -- so it cannot also be where the
     * memory lives. It used to be, and the result was that every single
     * `ensureOutput` found capacity 0, took the allocate branch, and did an
     * off-heap allocation plus a Cleaner registration on the audio thread. That
     * cost was survivable while the processor was only in the chain with pitch
     * engaged; making [isActive] unconditional put it on every callback of every
     * track. Media3's own BaseAudioProcessor keeps the split for the same
     * reason, and relies on the same contract: the sink drains what it was
     * given before asking for more.
     */
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var handle = 0L
    private var maxBlock = 0

    /** Direct scratch, sized once per format rather than per block. */
    private var nativeIn: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var nativeOut: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    @Volatile private var semitones: Float = 0f

    /**
     * Whether the vocoder is actually transposing, as opposed to merely being
     * in the chain. Distinct from [isActive] on purpose -- see there.
     */
    private val engaged: Boolean get() = abs(semitones) >= SEMITONE_DEADZONE

    /** Engagement as the audio thread last saw it. Audio thread only. */
    private var wasEngaged = false

    /** Semitones to transpose by; 0 makes the processor inactive. */
    fun setSemitones(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(MIN_SEMITONES, MAX_SEMITONES)
        if (clamped == semitones) return
        semitones = clamped
        val h = handle
        if (h != 0L) StretchNative.nativeSetSemitones(h, clamped)
    }

    fun getSemitones(): Float = semitones

    @Volatile private var engine: PitchEngine = PitchEngine.VOCODER
    @Volatile private var quality: PitchQuality = PitchQuality.BALANCED

    /**
     * Chooses the transposition algorithm. Takes effect on the next block; the
     * engine coming in is reset, so it does not open with history from whenever
     * it was last selected.
     */
    fun setEngine(engine: PitchEngine, quality: PitchQuality) {
        if (engine == this.engine && quality == this.quality) return
        this.engine = engine
        this.quality = quality
        val h = handle
        if (h != 0L) StretchNative.nativeSetEngine(h, engine.nativeId, quality.nativeId)
    }

    fun getEngine(): PitchEngine = engine

    fun getQuality(): PitchQuality = quality

    /**
     * Round-trip latency in frames, or 0 when the processor is not engaged.
     * Whoever changes the pitch should glide dependent state over this window.
     */
    fun latencyFrames(): Int {
        val h = handle
        return if (h != 0L && engaged) StretchNative.nativeLatencyFrames(h) else 0
    }

    // ── AudioProcessor ───────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    /**
     * Active whenever it *could* transpose, not only while it is.
     *
     * Media3 fixes the set of processors it will run when the pipeline is built
     * -- `AudioProcessingPipeline` keeps the ones whose `isActive` is true at
     * `configure` and again at `flush`, and consults it at no other time. Gating
     * this on the current pitch therefore left the processor out of the chain
     * for every track that started at zero semitones, which is all of them: the
     * pitch buttons then set a field nothing was reading, and the only audible
     * change was the AutoEQ pre-warp that rides alongside it, because that
     * processor is always in the chain. Pitch appeared to shift the EQ and not
     * the music.
     *
     * So membership is decided by what this can do, and [queueInput] passes
     * audio through untouched while there is nothing to do. That is the same
     * shape AutoEqProcessor uses for a flat curve, and it costs one copy per
     * block at zero pitch in exchange for the buttons working the moment they
     * are pressed.
     */
    override fun isActive(): Boolean =
        StretchNative.isAvailable &&
            (pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val h = handle
        if (h == 0L || !engaged) {
            // Nothing to do, so hand the audio on exactly as it arrived. An
            // effect that cannot or need not run has to be inaudible, never
            // silent: returning an empty buffer here would mute playback for
            // every track sitting at zero pitch.
            wasEngaged = false
            passThrough(inputBuffer)
            return
        }
        if (!wasEngaged) {
            // The engine still holds whatever it had buffered when the pitch
            // last returned to zero. Without this, turning pitch back on plays
            // a few hundred milliseconds of much older audio first.
            //
            // It can decline: a reconfigure holds the engine, and this is the
            // playback thread, which does not wait for anything. Staying
            // un-engaged means passing this block through and asking again on
            // the next one, a few milliseconds later.
            if (!StretchNative.nativeReset(h)) {
                passThrough(inputBuffer)
                return
            }
            wasEngaged = true
        }
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * channels
        val totalFrames = inputBuffer.remaining() / frameSize
        if (totalFrames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        ensureOutput(totalFrames * frameSize)
        var done = 0
        val start = inputBuffer.position()
        while (done < totalFrames) {
            val n = minOf(maxBlock, totalFrames - done)
            // Interleaved float into the direct staging buffer.
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until n * channels) {
                    nativeIn.putFloat(
                        i * 4,
                        inputBuffer.getFloat(start + (done * channels + i) * 4),
                    )
                }
            } else {
                for (i in 0 until n * channels) {
                    val s = inputBuffer.getShort(start + (done * channels + i) * 2)
                    nativeIn.putFloat(i * 4, s.toFloat() / 32768f)
                }
            }

            StretchNative.nativeProcess(h, nativeIn, nativeOut, n)

            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until n * channels) {
                    outputBuffer.putFloat(
                        (done * channels + i) * 4,
                        nativeOut.getFloat(i * 4),
                    )
                }
            } else {
                for (i in 0 until n * channels) {
                    val v = nativeOut.getFloat(i * 4)
                    outputBuffer.putShort(
                        (done * channels + i) * 2,
                        (v * 32768f).toInt().coerceIn(-32768, 32767).toShort(),
                    )
                }
            }
            done += n
        }
        inputBuffer.position(start + totalFrames * frameSize)
        outputBuffer.position(0)
        outputBuffer.limit(totalFrames * frameSize)
    }

    /** Copies the input to the output verbatim, sample for sample. */
    private fun passThrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        ensureOutput(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        wasEngaged = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
            releaseEngine()
        }
        if (StretchNative.isAvailable && inputFormat != AudioFormat.NOT_SET && handle == 0L) {
            handle = StretchNative.nativeCreate(
                inputFormat.channelCount, inputFormat.sampleRate,
            )
            if (handle != 0L) {
                maxBlock = StretchNative.nativeMaxBlockFrames().coerceAtLeast(1)
                StretchNative.nativeSetSemitones(handle, semitones)
                StretchNative.nativeSetEngine(handle, engine.nativeId, quality.nativeId)
                val bytes = maxBlock * inputFormat.channelCount * 4
                nativeIn = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                nativeOut = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            }
        } else if (handle != 0L) {
            // Result ignored on purpose: `wasEngaged` is false either way, so
            // queueInput asks again on the next engaged block.
            StretchNative.nativeReset(handle)
        }
    }

    override fun reset() {
        releaseOutput()
        inputEnded = false
        wasEngaged = false
        releaseEngine()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
    }

    private fun releaseEngine() {
        if (handle != 0L) {
            StretchNative.nativeDestroy(handle)
            handle = 0L
        }
        nativeIn = AudioProcessor.EMPTY_BUFFER
        nativeOut = AudioProcessor.EMPTY_BUFFER
    }

    private fun releaseOutput() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        buffer = AudioProcessor.EMPTY_BUFFER
    }

    private fun ensureOutput(bytes: Int) {
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
    }

    private companion object {
        const val MIN_SEMITONES = -24f
        const val MAX_SEMITONES = 24f
        const val SEMITONE_DEADZONE = 0.01f
    }
}
