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

    // Written from librespot's output thread only; read there too. Counts
    // what was produced since the last start/flush, for the logs.
    private var bytesSinceMark = 0L
    private var loggedFirstWrite = false

    override fun start(format: OutputAudioFormat): Boolean {
        val supported = format.sampleRate.toInt() == PcmSinkRegistry.SAMPLE_RATE &&
            format.channels == PcmSinkRegistry.CHANNELS &&
            format.sampleSizeInBits == 16 &&
            !format.isBigEndian
        PcmSinkRegistry.formatSupported = supported
        Log.i(TAG, "start: ${format.sampleRate.toInt()} Hz, ${format.sampleSizeInBits}-bit, " +
            "${format.channels} ch, bigEndian=${format.isBigEndian} → supported=$supported")
        mark()
        if (!supported) {
            Log.e(TAG, "Unsupported librespot output format: ${format.sampleRate} Hz, " +
                "${format.sampleSizeInBits}-bit, ${format.channels} ch, bigEndian=${format.isBigEndian}")
        }
        return true
    }

    override fun write(buffer: ByteArray, offset: Int, len: Int) {
        if (!loggedFirstWrite) {
            loggedFirstWrite = true
            Log.i(TAG, "first decoded audio written ($len bytes)")
        }
        bytesSinceMark += len
        PcmSinkRegistry.pipe.write(buffer, offset, len)
    }

    override fun flush() {
        Log.d(TAG, "flush (seek): dropping buffered audio; ${describe(bytesSinceMark)} written since last mark")
        mark()
        PcmSinkRegistry.pipe.clear()
    }

    override fun stop() {
        // librespot pauses by calling this. Nothing to drop — see the class doc.
        Log.d(TAG, "stop (librespot paused) after ${describe(bytesSinceMark)}")
    }

    private fun mark() {
        bytesSinceMark = 0
        loggedFirstWrite = false
    }

    private fun describe(bytes: Long): String {
        val ms = bytes * 1000 / (PcmSinkRegistry.SAMPLE_RATE.toLong() * PcmSinkRegistry.BYTES_PER_FRAME)
        return "$bytes bytes (${ms} ms of audio)"
    }

    override fun drain() = Unit

    override fun release() = Unit

    override fun close() = Unit

    private companion object {
        const val TAG = "SpotifyPcmSink"
    }
}
