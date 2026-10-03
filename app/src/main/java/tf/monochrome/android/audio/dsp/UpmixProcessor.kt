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
 * Stereo → 9.1.6 ahead of the mixer ([StereoUpmixer] does the work), for the
 * mixer's Atmos upmix. Sits just before [MixBusProcessor], so the mixer sees
 * the upmix as it would a 9.1.6 bed — one channel group per bus, nine buses
 * — and [DownmixProcessor] folds or places it after.
 *
 * On ([setEnabled]) it takes a stereo stream and hands on 16 channels; for
 * anything else, and while off, it is inactive (NOT_SET), so mono, wide
 * sources and Atmos streams pass as they always have.
 *
 * Turning it ON changes the stream's width, which Media3 only takes at a
 * pipeline configure, so it starts with the next track. Turning it OFF is
 * immediate: the upmixer crossfades to a plain front-pair copy, which the
 * fold returns as the original stereo, and the processor steps out at the
 * next configure.
 */
@Singleton
@OptIn(UnstableApi::class)
class UpmixProcessor @Inject constructor() : AudioProcessor {

    @Volatile
    private var enabled = false

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var scratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var upmixer: StereoUpmixer? = null
    private var upmixerRate = 0
    private var left = FloatArray(0)
    private var right = FloatArray(0)
    private var out = FloatArray(0)

    fun setEnabled(on: Boolean) {
        enabled = on
        upmixer?.enabled = on
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!enabled || inputAudioFormat.channelCount != 2) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return AudioFormat(inputAudioFormat.sampleRate, StereoUpmixer.CHANNELS, inputAudioFormat.encoding)
    }

    // pendingFormat alone, like DownmixProcessor: Media3 checks that an active
    // processor did not answer NOT_SET, so "configured for a 5.1 song after a
    // stereo one" must read as inactive at once.
    override fun isActive(): Boolean = pendingFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val up = upmixer ?: return
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * 2
        val frames = inputBuffer.remaining() / frameSize
        if (frames <= 0) return

        if (left.size < frames) {
            left = FloatArray(frames)
            right = FloatArray(frames)
            out = FloatArray(frames * StereoUpmixer.CHANNELS)
        }
        // Positional reads, no view buffers: nothing allocated per block.
        val start = inputBuffer.position()
        for (i in 0 until frames) {
            val base = start + i * frameSize
            if (isFloat) {
                left[i] = inputBuffer.getFloat(base)
                right[i] = inputBuffer.getFloat(base + 4)
            } else {
                left[i] = inputBuffer.getShort(base) / 32768f
                right[i] = inputBuffer.getShort(base + 2) / 32768f
            }
        }
        inputBuffer.position(start + frames * frameSize)

        up.process(left, right, frames, out)

        val samples = frames * StereoUpmixer.CHANNELS
        val outBytes = samples * bytesPerSample
        // One buffer, reused: getOutput hands it on and the pipeline has read
        // it before the next block comes in (BaseAudioProcessor does the same).
        if (scratch.capacity() < outBytes) {
            scratch = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        outputBuffer = scratch
        if (isFloat) {
            for (s in 0 until samples) outputBuffer.putFloat(s * 4, out[s])
        } else {
            for (s in 0 until samples) {
                outputBuffer.putShort(s * 2, (out[s] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
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
        // Seeks flush without a configure: keep the configured format.
        inputFormat = pendingFormat
        if (inputFormat == AudioFormat.NOT_SET) return
        val rate = inputFormat.sampleRate
        val up = upmixer
        if (up != null && upmixerRate == rate) {
            up.enabled = enabled
            up.reset()
        } else {
            // reset() after the switch, so the crossfade starts where the switch is.
            upmixer = StereoUpmixer(rate).also { it.enabled = enabled; it.reset() }
            upmixerRate = rate
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        upmixer = null
        upmixerRate = 0
    }
}
