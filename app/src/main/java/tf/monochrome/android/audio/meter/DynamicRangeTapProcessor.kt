package tf.monochrome.android.audio.meter

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
 * Passive tap that feeds [DynamicRangeNative], next to the tempo tap at the
 * head of each chain: it hears the track as decoded, before the mixer, the
 * effects, the volume and the speed change.
 *
 * Audio passes through untouched. Only while the meter is on (a local track
 * with no value yet) is anything copied out to it, a chunk at a time into one
 * reused array, so the audio thread allocates nothing per buffer.
 */
@Singleton
@OptIn(UnstableApi::class)
class DynamicRangeTapProcessor @Inject constructor() : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var scratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var chunk = FloatArray(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputFormat.channelCount
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val frames = if (channels > 0) inputBuffer.remaining() / frameSize else 0
        if (frames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val start = inputBuffer.position()
        val byteCount = frames * frameSize

        if (DynamicRangeNative.active && chunk.isNotEmpty()) push(inputBuffer, start, frames, channels, isFloat)

        // Pass through, into one reused buffer (getOutput hands it on and the
        // pipeline reads it before the next block).
        if (scratch.capacity() < byteCount) {
            scratch = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        val savedLimit = inputBuffer.limit()
        inputBuffer.limit(start + byteCount)
        scratch.put(inputBuffer)
        inputBuffer.limit(savedLimit)
        scratch.flip()
        outputBuffer = scratch
    }

    private fun push(buffer: ByteBuffer, startPos: Int, frames: Int, channels: Int, isFloat: Boolean) {
        val chunkFrames = chunk.size / channels
        val bytesPerSample = if (isFloat) 4 else 2
        val rate = inputFormat.sampleRate
        var frame = 0
        while (frame < frames) {
            val n = minOf(chunkFrames, frames - frame)
            val count = n * channels
            var pos = startPos + frame * channels * bytesPerSample
            if (isFloat) {
                for (i in 0 until count) { chunk[i] = buffer.getFloat(pos); pos += 4 }
            } else {
                for (i in 0 until count) { chunk[i] = buffer.getShort(pos) / 32768f; pos += 2 }
            }
            DynamicRangeNative.nativePush(chunk, n, channels, rate)
            frame += n
        }
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
        if (pendingFormat != AudioFormat.NOT_SET) {
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
        }
        // The chunk array for this channel count, made here rather than per
        // buffer. More channels than the meter takes: nothing is pushed.
        val channels = inputFormat.channelCount
        val size = if (channels in 1..MAX_CHANNELS) CHUNK_FRAMES * channels else 0
        if (chunk.size != size) chunk = FloatArray(size)
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        chunk = FloatArray(0)
    }

    private companion object {
        /** Frames per call into the meter: about 21 ms at 48 kHz. */
        const val CHUNK_FRAMES = 1024

        /** cpp's DynamicRangeMeter::kMaxChannels. */
        const val MAX_CHANNELS = 24
    }
}
