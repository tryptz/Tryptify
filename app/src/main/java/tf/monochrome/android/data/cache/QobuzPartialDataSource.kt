package tf.monochrome.android.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Plays a Qobuz track out of the cache file while that file is still being
 * downloaded.
 *
 * ExoPlayer opens a `qobuz://<trackId>?quality=<NAME>` URI; this joins (or
 * starts) the download for it via [QobuzStreamCacheManager.openPartial] and
 * then reads the part file, blocking only when it catches up with the writer.
 * Blocking inside [read] is the normal contract for a DataSource — a network
 * one does exactly the same — and ExoPlayer's loader thread is built for it.
 *
 * Seeking forward into a region that hasn't arrived yet waits for the download
 * to reach it, because the cache fills strictly front-to-back. In practice the
 * file lands far faster than real time, so this only bites on a slow
 * connection, and it's still no worse than the old behaviour of waiting for the
 * entire file before a single sample played.
 */
@UnstableApi
class QobuzPartialDataSource(
    private val cacheManager: QobuzStreamCacheManager,
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var stream: PartialStream? = null
    private var handle: RandomAccessFile? = null
    private var position: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val request = QobuzStreamUri.parse(dataSpec.uri.toString())
            ?: throw IOException("Not a Qobuz stream URI: ${dataSpec.uri}")

        // openPartial only awaits the response headers, so this returns as soon
        // as the download is under way rather than when it finishes.
        val partial = runBlocking { cacheManager.openPartial(request.trackId, request.quality) }
            ?: throw IOException("Qobuz stream unavailable for track ${request.trackId}")
        stream = partial

        position = dataSpec.position
        // Wait for the starting offset to exist before opening a handle on it.
        if (position > 0) partial.awaitAvailable(position - 1)

        handle = RandomAccessFile(partial.fileToRead(), "r").apply { seek(position) }

        val total = partial.totalBytes
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != PartialStream.UNKNOWN_LENGTH -> (total - position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val partial = stream ?: throw IOException("Read before open")
        val file = handle ?: throw IOException("Read before open")

        // Block until the writer is ahead of us — or has stopped for good.
        val available = try {
            partial.awaitAvailable(position)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted waiting for Qobuz stream", e)
        }
        if (available <= position) return C.RESULT_END_OF_INPUT

        var readable = minOf(length.toLong(), available - position)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            readable = minOf(readable, bytesRemaining)
        }

        val read = file.read(buffer, offset, readable.toInt())
        if (read == -1) return C.RESULT_END_OF_INPUT

        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        try {
            handle?.close()
        } catch (e: IOException) {
            throw e
        } finally {
            handle = null
            stream = null
            dataSpec = null
            position = 0
            bytesRemaining = C.LENGTH_UNSET.toLong()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /**
     * Read from the part file while the download runs, and from the installed
     * file once it's done — a stream handed back from cache was never a part
     * file at all.
     */
    private fun PartialStream.fileToRead() = if (isComplete) (completedFile ?: file) else file

    class Factory(
        private val cacheManager: QobuzStreamCacheManager,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = QobuzPartialDataSource(cacheManager)
    }

    companion object {
        fun isQobuzUri(uri: String): Boolean = QobuzStreamUri.matches(uri)
    }
}
