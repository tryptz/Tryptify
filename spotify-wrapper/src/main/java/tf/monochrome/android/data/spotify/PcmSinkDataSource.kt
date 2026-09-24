package tf.monochrome.android.data.spotify

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import tf.monochrome.android.data.cache.SpotifyShadowUri

/**
 * Serves `spotify://track/<base62>` URIs from the PCM that librespot's
 * decoder pushes into [PcmSink] (see [PcmSinkRegistry]). This is the
 * native-audio counterpart of [SilentWavDataSource]: instead of a silent
 * stand-in for a song the Spotify app is playing, the actual Spotify audio
 * flows through ExoPlayer here — the DSP chain, AutoEQ, the mixer and the
 * USB DAC all apply.
 *
 * The stream is unbounded (librespot pushes chunks as it decodes), so
 * ExoPlayer treats it as a live source; duration and seeking are handled
 * with the track's known duration at the MediaItem level.
 */
@UnstableApi
class PcmSinkDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.lastPathSegment ?: ""
        if (!SpotifyShadowUri.isTrackId(id)) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val sink = PcmSinkRegistry.sink ?: return C.RESULT_END_OF_INPUT
        val chunk = sink.read(READ_TIMEOUT_MS) ?: return C.RESULT_END_OF_INPUT
        val count = minOf(chunk.size, length)
        System.arraycopy(chunk, 0, buffer, offset, count)
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PcmSinkDataSource()
    }

    private companion object {
        const val READ_TIMEOUT_MS = 5_000L
    }
}
