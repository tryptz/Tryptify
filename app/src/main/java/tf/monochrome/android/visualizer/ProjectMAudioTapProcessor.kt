package tf.monochrome.android.visualizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

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
        // So the bus can size its ring now, at a track's start, while someone
        // is listening, instead of on the first buffer.
        audioBus.prepare(sampleRateHz.coerceAtLeast(1), channelCount.coerceAtLeast(1))
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) return
        // No visualizer engine or waveform overlay listening → skip the PCM→float
        // conversion entirely. handleBuffer is called on the audio render thread
        // for every frame of every track, so even modest per-frame work matters.
        if (!audioBus.hasSubscribers()) {
            audioBus.idle()
            return
        }
        // Converted by the bus straight into its ring, with the same scaling
        // and clamping this did into an array of its own for every buffer.
        audioBus.publishPcm(
            buffer = buffer,
            encoding = encoding,
            channelCount = channelCount.coerceAtLeast(1),
            sampleRate = sampleRateHz.coerceAtLeast(1)
        )
    }
}
