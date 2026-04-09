package tf.monochrome.android.player.atmos

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import tf.monochrome.android.audio.dsp.MixBusProcessor

/**
 * Custom Media3 Renderer that natively decodes E-AC-3 (Dolby Digital Plus JOC)
 * using the full Atmos engine, preserving 3D object metadata and outputting
 * up to 7.1.4 (12 channels) or binaural stereo.
 *
 * Inserted into ExoPlayer's renderer list BEFORE the default MediaCodec renderer
 * so it intercepts E-AC-3 JOC content first.
 */
@UnstableApi
class AtmosAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val mixBusProcessor: MixBusProcessor
) : DecoderAudioRenderer<AtmosAudioDecoder>(eventHandler, eventListener, audioSink) {

    private var currentDecoder: AtmosAudioDecoder? = null

    override fun getName(): String = "AtmosAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        val mimeType = format.sampleMimeType
        if (MimeTypes.AUDIO_E_AC3 == mimeType || MimeTypes.AUDIO_E_AC3_JOC == mimeType) {
            return if (sinkSupportsFormat(format)) {
                androidx.media3.exoplayer.RendererCapabilities.create(androidx.media3.common.C.FORMAT_HANDLED)
            } else {
                androidx.media3.exoplayer.RendererCapabilities.create(androidx.media3.common.C.FORMAT_HANDLED)
            }
        }
        return androidx.media3.exoplayer.RendererCapabilities.create(androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE)
    }

    override fun createDecoder(
        format: Format, 
        cryptoConfig: androidx.media3.decoder.CryptoConfig?
    ): AtmosAudioDecoder {
        val decoder = AtmosAudioDecoder(
            numInputBuffers = 16,
            numOutputBuffers = 16,
            initialInputBufferSize = format.maxInputSize,
            mixBusProcessor = mixBusProcessor
        )
        currentDecoder = decoder
        return decoder
    }

    override fun getOutputFormat(decoder: AtmosAudioDecoder): Format {
        val channelCount = decoder.getOutputChannelCount()
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(channelCount)
            .setSampleRate(48000)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build()
    }
}
