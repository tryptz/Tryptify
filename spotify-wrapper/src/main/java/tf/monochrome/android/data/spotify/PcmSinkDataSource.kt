package tf.monochrome.android.data.spotify

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import tf.monochrome.android.data.cache.SilentWav

/**
 * Serves `spotify-pcm://track/<base62>` URIs from the PCM that librespot's
 * decoder pushes into [PcmSinkRegistry]. This is the native-audio counterpart
 * of [SilentWavDataSource]: instead of a silent stand-in for a song the Spotify
 * app is playing, the actual Spotify audio flows through ExoPlayer here — the
 * DSP chain, AutoEQ, the mixer and the USB DAC all apply.
 *
 * Underrun handling
 * -----------------
 * When librespot's PCM buffer is momentarily empty (network jitter, seek,
 * transient stall) we get [PcmSink.UNDERRUN] — this must not terminate the
 * track; instead we wait a short while and retry. Only a true [PcmSink.EOS]
 * (end-of-stream) will return `C.RESULT_END_OF_INPUT`.
 */
@UnstableApi
class PcmSinkDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var cancelled = false

    override fun open(dataSpec: DataSpec): Long {
        try {
            val request = SpotifyPcmUri.parse(dataSpec.uri.toString())
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            transferInitializing(dataSpec)
            val dataSize = SilentWav.dataSize(request.durationMs)
            val total = SilentWav.HEADER_SIZE + dataSize
            if (dataSpec.position > total) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            uri = dataSpec.uri
            header = SilentWav.header(dataSize)
            position = dataSpec.position
            val available = total - position
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(dataSpec.length, available)
            }
            opened = true
            cancelled = false
            Log.i(TAG, "DataSource opened ${dataSpec.uri}")
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            Log.e(TAG, "DataSource open failed", e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (cancelled) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // WAV header bytes come first.
        if (position < SilentWav.HEADER_SIZE) {
            val headerRemaining = (SilentWav.HEADER_SIZE - position).toInt()
            val count = minOf(length.toLong(), bytesRemaining, headerRemaining.toLong()).toInt()
            System.arraycopy(header, position.toInt(), buffer, offset, count)
            position += count
            bytesRemaining -= count
            bytesTransferred(count)
            return count
        }

        val sink = PcmSinkRegistry.sink ?: return C.RESULT_END_OF_INPUT

        var count = sink.read(
            target = buffer,
            offset = offset,
            length = minOf(length.toLong(), bytesRemaining).toInt(),
            timeoutMs = READ_TIMEOUT_MS,
        )

        while (count == PcmSink.UNDERRUN && !cancelled) {
            Thread.sleep(UNDERFLOW_WAIT_MS)
            count = sink.read(
                target = buffer,
                offset = offset,
                length = minOf(length.toLong(), bytesRemaining).toInt(),
                timeoutMs = READ_TIMEOUT_MS,
            )
        }

        return when {
            count == PcmSink.EOS -> C.RESULT_END_OF_INPUT
            count <= 0 -> C.RESULT_END_OF_INPUT
            else -> {
                position += count
                bytesRemaining -= count
                bytesTransferred(count)
                count
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        Log.i(TAG, "DataSource close")
        if (!opened) return
        cancelled = true
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Force termination of the stream (e.g., when stopping playback early). */
    fun forceClose() {
        cancelled = true
        PcmSinkRegistry.sink?.signalEos()
        PcmSinkRegistry.sink = null
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PcmSinkDataSource()
    }

    private companion object {
        private const val TAG = "Tryptify/PcmSinkDS"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val UNDERFLOW_WAIT_MS = 50L
    }
}