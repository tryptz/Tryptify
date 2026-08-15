// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

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
 * Sums the pattern looper into the music, ahead of the DSP chain.
 *
 * Placed immediately before [tf.monochrome.android.audio.dsp.MixBusProcessor]
 * in the sink's processor array, so a pattern rendered here goes through the
 * mix bus, AutoEQ, the parametric EQ and the whole Oxford post-chain exactly
 * as the track does. Putting it after them would have been simpler and wrong:
 * the loop would arrive beside the user's processing rather than through it.
 *
 * When the sampler is idle — which is nearly always — this is a straight
 * buffer forward with no float conversion at all, so the cost of having it in
 * the chain is one volatile read per buffer.
 *
 * Multichannel content is passed through untouched. The looper is stereo and
 * the mix bus downstream only accepts one or two channels anyway, so there is
 * nothing sensible to sum a stereo pattern into on a 5.1 bed.
 */
@Singleton
@OptIn(UnstableApi::class)
class PatternMixProcessor @Inject constructor(
    private val bridge: NativeSamplerBridge,
    private val router: SamplerRenderRouter,
) : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Grown on demand, never shrunk, so the steady state allocates nothing.
    private var mixL = FloatArray(0)
    private var mixR = FloatArray(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        // Same guard the mix bus carries: both buffers can be the pipeline's
        // shared EMPTY_BUFFER singleton during init and at end of stream, and
        // put(self) throws in a place Media3 does not catch.
        if (size == 0 || inputBuffer === outputBuffer) return

        val channels = inputFormat.channelCount
        val idle = !bridge.isReady || !router.engineNeeded
        if (idle || channels < 1 || channels > 2) {
            forward(inputBuffer, size)
            return
        }

        router.noteChainRender()

        val float = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val frames = size / (bytesPerSample * channels)
        if (frames <= 0) {
            forward(inputBuffer, size)
            return
        }

        if (mixL.size < frames) {
            mixL = FloatArray(frames)
            mixR = FloatArray(frames)
        }

        // Deinterleave with positional reads rather than asFloatBuffer() /
        // asShortBuffer() views — those allocate a wrapper on every call, and
        // at ~47 buffers a second that is enough young-gen churn to stall the
        // renderer past its deadline. Same reasoning as MixBusProcessor.
        val start = inputBuffer.position()
        if (channels == 1) {
            if (float) {
                for (i in 0 until frames) {
                    val s = inputBuffer.getFloat(start + i * 4)
                    mixL[i] = s
                    mixR[i] = s
                }
            } else {
                for (i in 0 until frames) {
                    val s = inputBuffer.getShort(start + i * 2).toFloat() / 32768f
                    mixL[i] = s
                    mixR[i] = s
                }
            }
        } else {
            if (float) {
                for (i in 0 until frames) {
                    val off = start + i * 8
                    mixL[i] = inputBuffer.getFloat(off)
                    mixR[i] = inputBuffer.getFloat(off + 4)
                }
            } else {
                for (i in 0 until frames) {
                    val off = start + i * 4
                    mixL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                    mixR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(start + frames * bytesPerSample * channels)

        // clearFirst = false: the music is already in these arrays and the
        // pattern lands on top of it.
        bridge.render(mixL, mixR, frames, false)

        val outFrameSize = bytesPerSample * channels
        val outBytes = frames * outFrameSize
        ensureOutput(outBytes)

        if (channels == 1) {
            // A mono sink gets the pattern folded down rather than dropped —
            // silently losing one side of a stereo hit would be worse.
            if (float) {
                for (i in 0 until frames) outputBuffer.putFloat(i * 4, (mixL[i] + mixR[i]) * 0.5f)
            } else {
                for (i in 0 until frames) {
                    outputBuffer.putShort(i * 2, toPcm16((mixL[i] + mixR[i]) * 0.5f))
                }
            }
        } else {
            if (float) {
                for (i in 0 until frames) {
                    val off = i * 8
                    outputBuffer.putFloat(off, mixL[i])
                    outputBuffer.putFloat(off + 4, mixR[i])
                }
            } else {
                for (i in 0 until frames) {
                    val off = i * 4
                    outputBuffer.putShort(off, toPcm16(mixL[i]))
                    outputBuffer.putShort(off + 2, toPcm16(mixR[i]))
                }
            }
        }
        outputBuffer.position(0)
        outputBuffer.limit(outBytes)
    }

    private fun forward(inputBuffer: ByteBuffer, size: Int) {
        ensureOutput(size)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun ensureOutput(bytes: Int) {
        if (outputBuffer.capacity() < bytes) {
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
    }

    private fun toPcm16(value: Float): Short =
        (value * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat == AudioFormat.NOT_SET) return
        val rateChanged = inputFormat.sampleRate != pendingFormat.sampleRate
        inputFormat = pendingFormat
        pendingFormat = AudioFormat.NOT_SET
        // The engine derives its step length from the output rate, so a
        // 44.1k → 48k track transition has to reach it or the loop changes
        // tempo with the track.
        if (rateChanged) bridge.prepare(inputFormat.sampleRate)
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        pendingFormat = AudioFormat.NOT_SET
    }
}
