package tf.monochrome.android.player.atmos

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import tf.monochrome.android.audio.dsp.MixBusProcessor
import java.nio.ByteBuffer

/**
 * Output mode for the Atmos renderer pipeline.
 * Maps 1:1 to the native AtmosOutputMode enum.
 */
enum class AtmosOutputMode(val nativeValue: Int) {
    SPEAKERS_7_1_4(0),  // 12-channel discrete speaker output
    BINAURAL(1),        // Stereo binaural (HRTF convolution)
    STEREO_DOWNMIX(2)   // ITU-R BS.775 stereo downmix
}

@UnstableApi
class AtmosAudioDecoder(
    numInputBuffers: Int,
    numOutputBuffers: Int,
    initialInputBufferSize: Int,
    private val mixBusProcessor: MixBusProcessor
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AtmosDecoderException>(
    Array(numInputBuffers) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT) },
    Array(numOutputBuffers) { SimpleDecoderOutputBuffer { } }
) {
    private var nativeDecoderContext: Long = 0
    private var outputMode: AtmosOutputMode = AtmosOutputMode.BINAURAL

    init {
        nativeDecoderContext = initNativeDecoder()
    }

    override fun getName(): String = "AtmosAudioDecoder"

    /**
     * Switch between 7.1.4 speaker output, binaural HRTF, and stereo downmix.
     */
    fun setOutputMode(mode: AtmosOutputMode) {
        outputMode = mode
        if (nativeDecoderContext != 0L) {
            nativeSetOutputMode(nativeDecoderContext, mode.nativeValue)
        }
    }

    /**
     * Get the number of output channels for the current mode.
     * 12 for 7.1.4, 2 for binaural/stereo.
     */
    fun getOutputChannelCount(): Int {
        if (nativeDecoderContext == 0L) return 2
        return nativeGetOutputChannelCount(nativeDecoderContext)
    }

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    }

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer {
        return SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }
    }

    override fun createUnexpectedDecodeException(error: Throwable): AtmosDecoderException {
        return AtmosDecoderException("Unexpected decode error", error)
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean
    ): AtmosDecoderException? {
        val inputData = inputBuffer.data ?: return null
        val inputSize = inputData.limit()
        
        // Buffer size depends on output mode:
        //   7.1.4:    1536 samples × 12 channels × 4 bytes = 73,728 bytes
        //   Binaural: 1536 samples × 2 channels  × 4 bytes = 12,288 bytes
        val channelCount = getOutputChannelCount()
        val outSize = 1536 * channelCount * 4
        outputBuffer.init(inputBuffer.timeUs, outSize)
        val outputData = outputBuffer.data
        if (outputData == null) {
            return AtmosDecoderException("Failed to allocate output buffer")
        }

        val result = nativeDecode(nativeDecoderContext, inputData, inputSize, outputData)
        if (result < 0) {
            return AtmosDecoderException("Native decode failed with code: $result")
        }

        outputData.position(0)
        outputData.limit(result)
        return null
    }

    override fun release() {
        super.release()
        if (nativeDecoderContext != 0L) {
            releaseNativeDecoder(nativeDecoderContext)
            nativeDecoderContext = 0
        }
    }

    // JNI bindings
    private external fun initNativeDecoder(): Long
    private external fun nativeDecode(context: Long, input: ByteBuffer, inputSize: Int, output: ByteBuffer): Int
    private external fun releaseNativeDecoder(context: Long)
    private external fun nativeSetOutputMode(context: Long, mode: Int)
    private external fun nativeGetOutputChannelCount(context: Long): Int

    companion object {
        init {
            System.loadLibrary("monochrome-dsp")
        }
    }
}
