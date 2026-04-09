package tf.monochrome.android.visualizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class ProjectMAudioTapProcessor(
    private val audioBus: ProjectMAudioBus
) : TeeAudioProcessor.AudioBufferSink {

    private var sampleRateHz: Int = 44_100
    private var channelCount: Int = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) return

        val copy = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val floatSamples = when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sampleCount = copy.remaining() / 2
                FloatArray(sampleCount).also { out ->
                    for (index in 0 until sampleCount) {
                        out[index] = (copy.short / 32768f).coerceIn(-1f, 1f)
                    }
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                val sampleCount = copy.remaining() / 4
                FloatArray(sampleCount).also { out ->
                    for (index in 0 until sampleCount) {
                        out[index] = copy.float.coerceIn(-1f, 1f)
                    }
                }
            }

            C.ENCODING_PCM_8BIT -> {
                val sampleCount = copy.remaining()
                FloatArray(sampleCount).also { out ->
                    for (index in 0 until sampleCount) {
                        out[index] = ((copy.get().toInt() and 0xFF) - 128) / 128f
                    }
                }
            }

            else -> return
        }

        audioBus.publish(
            samples = floatSamples,
            channelCount = channelCount.coerceAtLeast(1),
            sampleRate = sampleRateHz.coerceAtLeast(1)
        )
    }
}
