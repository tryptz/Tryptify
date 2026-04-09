package tf.monochrome.android.audio.dsp

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExoPlayer AudioProcessor that accepts multichannel PCM input (5.1 / 7.1 / 7.1.4)
 * from Apple Music Dolby Atmos (or any multichannel source decoded by MediaCodec)
 * and renders it to binaural stereo via HRTF convolution of the full 7.1.4 speaker layout.
 *
 * Placed BEFORE MixBusProcessor in the ExoPlayer audio processor chain:
 *   MediaCodec → [SpatialAudioProcessor] → MixBusProcessor → AutoEQ → AudioSink
 *
 * When the input is already stereo (2ch), this processor passes through unchanged.
 * When the input is multichannel (>2ch), it renders to binaural stereo output.
 */
@Singleton
@OptIn(UnstableApi::class)
class SpatialAudioProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioProcessor {

    private var rendererPtr: Long = 0L
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // Scratch arrays
    private var scratchInput = FloatArray(0)   // Interleaved multichannel
    private var scratchOutL = FloatArray(0)     // Stereo left
    private var scratchOutR = FloatArray(0)     // Stereo right

    // JNI
    private external fun nativeCreate(assetManager: AssetManager, sampleRate: Int, maxBlockSize: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeProcess(
        ptr: Long,
        input: FloatArray, inputChannels: Int,
        outputL: FloatArray, outputR: FloatArray,
        numFrames: Int
    )
    private external fun nativeSetEnabled(ptr: Long, enabled: Boolean)
    private external fun nativeSetRoomMode(ptr: Long, mode: Int)

    companion object {
        init {
            System.loadLibrary("monochrome_dsp")
        }
        const val MAX_BLOCK_SIZE = 4096
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        val ptr = rendererPtr
        if (ptr != 0L) nativeSetEnabled(ptr, enabled)
    }

    /** Room reverb mode: 0=OFF, 1=NEAR, 2=MID, 3=FAR */
    fun setRoomMode(mode: Int) {
        val ptr = rendererPtr
        if (ptr != 0L) nativeSetRoomMode(ptr, mode)
    }

    // ── AudioProcessor implementation ────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // Accept float PCM with any channel count up to 12
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 12) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        pendingFormat = inputAudioFormat

        // If multichannel (>2), output stereo. If ≤2, pass through unchanged.
        return if (inputAudioFormat.channelCount > 2) {
            AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
        } else {
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val inputChannels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val numFrames = inputBuffer.remaining() / frameSize

        if (numFrames <= 0) return

        // Stereo or mono passthrough (no spatial processing needed)
        if (inputChannels <= 2 || rendererPtr == 0L) {
            val size = inputBuffer.remaining()
            if (outputBuffer.capacity() < size) {
                outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        // ── Multichannel input: deinterleave to float, process, re-interleave ──

        val totalSamples = numFrames * inputChannels
        if (scratchInput.size < totalSamples) {
            scratchInput = FloatArray(totalSamples)
        }
        if (scratchOutL.size < numFrames) {
            scratchOutL = FloatArray(numFrames)
            scratchOutR = FloatArray(numFrames)
        }

        // Read multichannel input into flat float array
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val fb = inputBuffer.asFloatBuffer()
            for (i in 0 until totalSamples) {
                scratchInput[i] = fb.get()
            }
        } else {
            val sb = inputBuffer.asShortBuffer()
            for (i in 0 until totalSamples) {
                scratchInput[i] = sb.get().toFloat() / 32768f
            }
        }
        inputBuffer.position(inputBuffer.position() + numFrames * frameSize)

        // Process: multichannel → binaural stereo via native C++
        nativeProcess(rendererPtr, scratchInput, inputChannels, scratchOutL, scratchOutR, numFrames)

        // Write stereo output
        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        if (encoding == C.ENCODING_PCM_FLOAT) {
            val fb = outputBuffer.asFloatBuffer()
            for (i in 0 until numFrames) {
                fb.put(scratchOutL[i])
                fb.put(scratchOutR[i])
            }
        } else {
            val sb = outputBuffer.asShortBuffer()
            for (i in 0 until numFrames) {
                sb.put((scratchOutL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                sb.put((scratchOutR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
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

        if (pendingFormat == AudioFormat.NOT_SET) return

        val formatChanged = inputFormat == AudioFormat.NOT_SET
            || inputFormat.sampleRate != pendingFormat.sampleRate
            || inputFormat.channelCount != pendingFormat.channelCount

        if (formatChanged) {
            if (rendererPtr != 0L) {
                nativeDestroy(rendererPtr)
            }
            inputFormat = pendingFormat
            rendererPtr = nativeCreate(context.assets, inputFormat.sampleRate, MAX_BLOCK_SIZE)
            nativeSetEnabled(rendererPtr, _enabled.value)
        }
        pendingFormat = AudioFormat.NOT_SET
    }

    override fun reset() {
        flush()
        if (rendererPtr != 0L) {
            nativeDestroy(rendererPtr)
            rendererPtr = 0L
        }
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
    }
}
