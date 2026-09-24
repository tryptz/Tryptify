package tf.monochrome.android.audio.dsp

import androidx.media3.common.C
import java.nio.ByteBuffer

/**
 * One block of interleaved PCM as a float array per channel, for the stages
 * that process channel by channel: the two EQs and the mixer.
 *
 * Any width up to [ChannelLayout.MAX_CHANNELS]. Mono is read as two identical
 * channels, because every one of those stages has always output stereo for a
 * mono source; everything wider keeps its width.
 *
 * Index-based reads and writes throughout — `asFloatBuffer()` and friends
 * allocate a view per call, which on the audio thread is a GC pause waiting
 * to happen. The arrays themselves are reallocated only when the channel
 * count changes or a block is longer than any before it.
 */
internal class PlanarBlock {

    var channels: Array<FloatArray> = emptyArray()
        private set

    /** Channels held — the input's, or 2 for a mono input. */
    var channelCount = 0
        private set

    /**
     * Reads every whole frame remaining in [input] and advances its position
     * past them. Returns the frame count.
     */
    fun read(input: ByteBuffer, inputChannels: Int, encoding: Int): Int {
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val frames = input.remaining() / frameSize
        if (frames <= 0) return 0
        val out = if (inputChannels == 1) 2 else inputChannels
        ensure(out, frames)
        val start = input.position()
        if (inputChannels == 1) {
            val a = channels[0]
            val b = channels[1]
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until frames) {
                    val s = input.getFloat(start + i * 4)
                    a[i] = s; b[i] = s
                }
            } else {
                for (i in 0 until frames) {
                    val s = input.getShort(start + i * 2).toFloat() / 32768f
                    a[i] = s; b[i] = s
                }
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until frames) {
                val off = start + i * frameSize
                for (c in 0 until inputChannels) channels[c][i] = input.getFloat(off + c * 4)
            }
        } else {
            for (i in 0 until frames) {
                val off = start + i * frameSize
                for (c in 0 until inputChannels) {
                    channels[c][i] = input.getShort(off + c * 2).toFloat() / 32768f
                }
            }
        }
        input.position(start + frames * frameSize)
        return frames
    }

    /**
     * Writes [frames] frames of every held channel, interleaved, from index 0
     * of [output] with absolute puts; the caller sets position and limit.
     * 16-bit output truncates and clamps, as these stages always have.
     */
    fun write(output: ByteBuffer, frames: Int, encoding: Int) {
        val n = channelCount
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val frameSize = 4 * n
            for (i in 0 until frames) {
                val off = i * frameSize
                for (c in 0 until n) output.putFloat(off + c * 4, channels[c][i])
            }
        } else {
            val frameSize = 2 * n
            for (i in 0 until frames) {
                val off = i * frameSize
                for (c in 0 until n) {
                    output.putShort(
                        off + c * 2,
                        (channels[c][i] * 32768f).toInt().coerceIn(-32768, 32767).toShort(),
                    )
                }
            }
        }
    }

    /** Bytes [write] fills for [frames] frames. */
    fun outputBytes(frames: Int, encoding: Int): Int =
        frames * channelCount * (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2)

    private fun ensure(count: Int, frames: Int) {
        if (channelCount != count || channels.isEmpty() || channels[0].size < frames) {
            val capacity = maxOf(frames, if (channels.isEmpty()) 0 else channels[0].size)
            channels = Array(count) { FloatArray(capacity) }
            channelCount = count
        }
    }
}
