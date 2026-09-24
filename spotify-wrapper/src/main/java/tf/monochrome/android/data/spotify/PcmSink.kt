package tf.monochrome.android.data.spotify

import android.util.Log
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat
import xyz.gianlu.librespot.player.mixing.output.SinkOutput

/**
 * librespot's audio output. The player is configured with
 * `setOutputClass(PcmSink)`, so librespot instantiates this by reflection (it
 * needs the public no-arg constructor — see the keep rule in
 * `consumer-rules.pro`) and its `AudioSink` thread calls [write] with every
 * decoded chunk. Everything goes straight into [PcmSinkRegistry.pipe], which
 * [PcmSinkDataSource] reads for ExoPlayer — and from there the DSP chain.
 *
 * What librespot's calls mean here, read from its `AudioSink`/`PlayerSession`:
 * - [write] reuses one 4 KiB buffer every call; the pipe copies it.
 * - [stop] is called whenever librespot pauses. It must *not* drop audio: the
 *   ring holds the next samples ExoPlayer will read.
 * - [flush] is called on a seek; the buffered audio is from before the seek.
 */
class PcmSink : SinkOutput {

    override fun start(format: OutputAudioFormat): Boolean {
        val supported = format.sampleRate.toInt() == PcmSinkRegistry.SAMPLE_RATE &&
            format.channels == PcmSinkRegistry.CHANNELS &&
            format.sampleSizeInBits == 16 &&
            !format.isBigEndian
        PcmSinkRegistry.formatSupported = supported
        if (!supported) {
            Log.e(TAG, "Unsupported librespot output format: ${format.sampleRate} Hz, " +
                "${format.sampleSizeInBits}-bit, ${format.channels} ch, bigEndian=${format.isBigEndian}")
        }
        return true
    }

    override fun write(buffer: ByteArray, offset: Int, len: Int) {
        PcmSinkRegistry.pipe.write(buffer, offset, len)
    }

    override fun flush() {
        PcmSinkRegistry.pipe.clear()
    }

    override fun stop() = Unit

    override fun drain() = Unit

    override fun release() = Unit

    override fun close() = Unit

    private companion object {
        const val TAG = "SpotifyPcmSink"
    }
}
