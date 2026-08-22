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
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var handle = 0L
    private var maxBlock = 0

    /** Direct scratch, sized once per format rather than per block. */
    private var nativeIn: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var nativeOut: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    @Volatile private var semitones: Float = 0f

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

    /**
     * Round-trip latency in frames, or 0 when the processor is not engaged.
     * Whoever changes the pitch should glide dependent state over this window.
     */
    fun latencyFrames(): Int {
        val h = handle
        return if (h != 0L && isActive) StretchNative.nativeLatencyFrames(h) else 0
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

    override fun isActive(): Boolean =
        StretchNative.isAvailable &&
            (pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET) &&
            abs(semitones) >= SEMITONE_DEADZONE

    override fun queueInput(inputBuffer: ByteBuffer) {
        val h = handle
        if (h == 0L) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
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
                val bytes = maxBlock * inputFormat.channelCount * 4
                nativeIn = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                nativeOut = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            }
        } else if (handle != 0L) {
            StretchNative.nativeReset(handle)
        }
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
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

    private fun ensureOutput(bytes: Int) {
        if (outputBuffer.capacity() < bytes) {
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
    }

    private companion object {
        const val MIN_SEMITONES = -24f
        const val MAX_SEMITONES = 24f
        const val SEMITONE_DEADZONE = 0.01f
    }
}
