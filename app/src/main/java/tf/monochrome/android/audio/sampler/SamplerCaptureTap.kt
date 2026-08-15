// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.domain.patterns.CaptureSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A passive tap that copies PCM into [SampleCaptureEngine] and forwards the
 * buffer untouched.
 *
 * Two of these sit in the sink's processor array at different points, which is
 * what gives the Sampler screen its CLEAN / PROCESSED choice:
 *
 * ```
 *   decoder → [CleanCaptureTap] → mix bus → EQ → Oxford → [ProcessedCaptureTap] → out
 * ```
 *
 * Both are always in the chain and both are cheap: when nothing is armed the
 * only work per buffer is one volatile read and the copy every processor in
 * the chain already pays. Modelled on
 * [tf.monochrome.android.audio.eq.SpectrumAnalyzerTap], which solves the same
 * problem for the EQ visualiser.
 */
@OptIn(UnstableApi::class)
abstract class SamplerCaptureTap(
    private val engine: SampleCaptureEngine,
    private val source: CaptureSource,
) : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var passthrough: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var scratchL = FloatArray(0)
    private var scratchR = FloatArray(0)

    /** The rate the tap last saw, so the Sampler screen can arm at it. */
    @Volatile
    var observedSampleRate: Int = 48000
        private set

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        if (channels < 1) {
            passthrough = AudioProcessor.EMPTY_BUFFER
            return
        }
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val frames = inputBuffer.remaining() / frameSize
        if (frames <= 0) {
            passthrough = AudioProcessor.EMPTY_BUFFER
            return
        }
        val byteCount = frames * frameSize
        val start = inputBuffer.position()

        if (engine.isArmed && channels <= 2) {
            if (scratchL.size < frames) {
                // Only reached while armed, and only when a larger buffer than
                // any seen so far arrives — in practice once, at the start of a
                // capture. Steady state allocates nothing on the audio thread.
                scratchL = FloatArray(frames)
                scratchR = FloatArray(frames)
            }
            // Positional reads rather than asFloatBuffer() / asShortBuffer():
            // those allocate a view wrapper per call, and at ~47 buffers a
            // second that is enough young-gen churn to stall the renderer past
            // its deadline. Same reasoning as MixBusProcessor.
            if (channels == 1) {
                if (isFloat) {
                    for (i in 0 until frames) {
                        val s = inputBuffer.getFloat(start + i * 4)
                        scratchL[i] = s
                        scratchR[i] = s
                    }
                } else {
                    for (i in 0 until frames) {
                        val s = inputBuffer.getShort(start + i * 2).toFloat() / 32768f
                        scratchL[i] = s
                        scratchR[i] = s
                    }
                }
            } else {
                if (isFloat) {
                    for (i in 0 until frames) {
                        val off = start + i * 8
                        scratchL[i] = inputBuffer.getFloat(off)
                        scratchR[i] = inputBuffer.getFloat(off + 4)
                    }
                } else {
                    for (i in 0 until frames) {
                        val off = start + i * 4
                        scratchL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                        scratchR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                    }
                }
            }
            engine.write(source, scratchL, scratchR, frames)
        }

        // Forward the audio on unchanged. Copied into this processor's own
        // buffer rather than handed on by reference — the pipeline hands the
        // same ByteBuffer down the chain, and returning it as our output while
        // also consuming it leaves the next processor with nothing to read.
        // This is the same shape ChannelDetectorProcessor uses.
        if (passthrough.capacity() < byteCount) {
            passthrough = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        } else {
            passthrough.clear()
        }
        val savedLimit = inputBuffer.limit()
        inputBuffer.limit(start + byteCount)
        passthrough.put(inputBuffer)
        inputBuffer.limit(savedLimit)
        passthrough.flip()
    }

    override fun getOutput(): ByteBuffer {
        val buffer = passthrough
        passthrough = AudioProcessor.EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && passthrough === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        passthrough = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat == AudioFormat.NOT_SET) return
        inputFormat = pendingFormat
        pendingFormat = AudioFormat.NOT_SET
        observedSampleRate = inputFormat.sampleRate
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        pendingFormat = AudioFormat.NOT_SET
    }
}

/** Decoder output, before any Tryptify processing. */
@Singleton
class CleanCaptureTap @Inject constructor(
    engine: SampleCaptureEngine,
) : SamplerCaptureTap(engine, CaptureSource.CLEAN)

/** After the full DSP chain — mix bus, AutoEQ, parametric EQ, Oxford, crossfeed. */
@Singleton
class ProcessedCaptureTap @Inject constructor(
    engine: SampleCaptureEngine,
) : SamplerCaptureTap(engine, CaptureSource.PROCESSED)
