package tf.monochrome.android.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec

/**
 * Serves `spotify-shadow://` URIs as a silent WAV of the Spotify track's
 * length, synthesised on the fly — nothing is written to disk or fetched.
 * See [SpotifyShadowUri] for why a Spotify track needs a silent stand-in.
 *
 * Honours [DataSpec.position] so ExoPlayer's WAV seeking (which maps a time to
 * a byte offset and reopens there) lands exactly where the scrubber asked.
 */
@UnstableApi
class SilentWavDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var header: ByteArray = ByteArray(0)
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val request = SpotifyShadowUri.parse(dataSpec.uri.toString())
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
        val available = total - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available)
        } else {
            available
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), bytesRemaining).toInt()
        SilentWav.fill(header, position, buffer, offset, count)
        position += count
        bytesRemaining -= count
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
        override fun createDataSource(): DataSource = SilentWavDataSource()
    }
}
