package tf.monochrome.android.audio.resample

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Time-stretching (speed with pitch preserved) at whatever resolution the
 * stream has: 16-bit or float in, the same out, at any rate and channel count.
 *
 * Media3's SonicAudioProcessor does the same job for 16-bit only, which is why
 * the int branch of DefaultAudioSink keeps using it. This one is for the two
 * paths that run the DSP themselves — the hi-res float path into the Android
 * output, and the exclusive USB path — neither of which could change speed
 * with pitch preserved before.
 *
 * Unlike Media3's, a speed or pitch change applies to the running stream
 * rather than on the next flush: those paths are never flushed for a speed
 * change, and waiting for one would leave the change unheard until a seek.
 *
 * Once engaged it stays in the chain as a pass-through at 1.00x, rather than
 * dropping out the moment speed returns to unity: leaving would discard the
 * few milliseconds Sonic is holding, a click on every trip back to normal
 * speed. At unity Sonic copies its input straight through, and a 16-bit sample
 * survives the float round trip unchanged, so staying costs one copy and no
 * bits.
 *
 * Gain is unchanged: the overlap-add crossfades are complementary. Latency is
 * zero at 1.00x and at most two maximum pitch periods away from it — 2 × rate
 * / 65 Hz, about 31 ms at any rate — which is Sonic's own figure.
 */
@OptIn(UnstableApi::class)
class FloatSonicAudioProcessor : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var format = AudioFormat.NOT_SET
    private var sonic: FloatSonic? = null
    private var speed = 1f
    private var pitch = 1f
    private var engaged = false
    private var inputEnded = false

    private var scratch = FloatArray(0)
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    fun setSpeed(value: Float) {
        speed = value
        if (!isUnity(value)) engaged = true
        sonic?.setSpeed(value)
    }

    fun setPitch(value: Float) {
        pitch = value
        if (!isUnity(value)) engaged = true
        sonic?.setPitch(value)
    }

    fun getSpeed(): Float = speed

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET && (engaged || !isUnity(speed) || !isUnity(pitch))

    override fun queueInput(inputBuffer: ByteBuffer) {
        val s = sonic ?: return
        if (!inputBuffer.hasRemaining()) return
        val channels = format.channelCount
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = channels * (if (isFloat) 4 else 2)
        val frames = inputBuffer.remaining() / bytesPerFrame
        if (frames == 0) return
        val samples = frames * channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        // Absolute reads, not asFloatBuffer()/slice(): those allocate a view
        // per call, and this runs for every buffer. Media3 hands processors
        // native-order buffers; one that is not gets a view, as before.
        val src = if (inputBuffer.order() == ByteOrder.nativeOrder()) {
            inputBuffer
        } else {
            inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        }
        val start = inputBuffer.position()
        if (isFloat) {
            for (i in 0 until samples) scratch[i] = src.getFloat(start + i * 4)
        } else {
            for (i in 0 until samples) scratch[i] = src.getShort(start + i * 2) / 32768f
        }
        inputBuffer.position(start + frames * bytesPerFrame)
        s.queueInput(scratch, 0, frames)
    }

    override fun queueEndOfStream() {
        sonic?.queueEndOfStream()
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val s = sonic
        if (s != null && s.outputFrameCountAvailable > 0) {
            val channels = format.channelCount
            val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
            val frames = s.outputFrameCountAvailable
            val samples = frames * channels
            val bytes = samples * (if (isFloat) 4 else 2)
            if (scratch.size < samples) scratch = FloatArray(samples)
            s.getOutput(scratch, frames)
            if (buffer.capacity() < bytes) {
                buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            } else {
                buffer.clear()
            }
            if (isFloat) {
                for (i in 0 until samples) buffer.putFloat(i * 4, scratch[i])
            } else {
                for (i in 0 until samples) buffer.putShort(i * 2, toPcm16(scratch[i]))
            }
            buffer.limit(bytes)
            outputBuffer = buffer
        }
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && (sonic?.outputFrameCountAvailable ?: 0) == 0 && !outputBuffer.hasRemaining()

    override fun flush() {
        if (pendingFormat != AudioFormat.NOT_SET) {
            val current = sonic
            if (current == null || pendingFormat != format) {
                format = pendingFormat
                sonic = FloatSonic(format.sampleRate, format.channelCount, speed, pitch, format.sampleRate)
            } else {
                current.flush()
            }
        }
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        format = AudioFormat.NOT_SET
        sonic = null
        speed = 1f
        pitch = 1f
        engaged = false
        scratch = FloatArray(0)
        buffer = AudioProcessor.EMPTY_BUFFER
    }

    private companion object {
        /** Sonic's own threshold for treating a factor as unity. */
        const val CLOSE_THRESHOLD = 1e-4f

        fun isUnity(value: Float): Boolean = abs(value - 1f) < CLOSE_THRESHOLD

        fun toPcm16(sample: Float): Short =
            (sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
