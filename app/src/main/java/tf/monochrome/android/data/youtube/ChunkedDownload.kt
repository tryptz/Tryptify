package tf.monochrome.android.data.youtube

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Fetches a YouTube stream link (googlevideo) whole, in pieces of at most
 * [pieceBytes].
 *
 * googlevideo throttles a request with no end to about real time, or turns
 * it away, so every request names a closed byte range. The rest is what
 * NewPipe's player sends for the same links: each request numbered
 * (`rn`), as a POST with the two-byte body YouTube's own clients send, and
 * the User-Agent the link's client wants ([YoutubeMapping.userAgentFor]).
 *
 * A piece that fails is asked for again a few times; a link that stops
 * working (403, 410, as it does when it expires) is thrown as [Expired], so
 * the caller can extract a fresh one and carry on from where this stopped:
 * every piece already written stays in the file.
 */
internal class ChunkedDownload(
    private val client: OkHttpClient,
    private val pieceBytes: Long = PIECE_BYTES,
    private val retryDelayMs: Long = 500,
) {
    /** The link no longer works: extract a new one and resume. */
    class Expired(val status: Int) : IOException("stream link refused with HTTP $status")

    /**
     * Appends [url]'s bytes to [into], from its current length on, until
     * [totalBytes] (when the format said how long it is; 0 or less when it
     * did not) or until a piece comes back short. [onProgress] gets the bytes
     * written so far.
     */
    suspend fun fetch(
        url: String,
        totalBytes: Long,
        into: File,
        onProgress: (Long) -> Unit = {},
    ) {
        val userAgent = YoutubeMapping.userAgentFor(url)
        var position = into.length()
        var requestNumber = 0
        FileOutputStream(into, true).use { out ->
            while (totalBytes <= 0 || position < totalBytes) {
                currentCoroutineContext().ensureActive()
                val last = if (totalBytes > 0) minOf(position + pieceBytes, totalBytes) - 1 else position + pieceBytes - 1
                val wanted = last - position + 1
                val piece = pieceWithRetries(url, position, last, requestNumber++, userAgent)
                out.write(piece)
                position += piece.size
                onProgress(position)
                if (piece.size < wanted) break
            }
        }
        if (totalBytes > 0 && position != totalBytes) {
            throw IOException("stream ended at $position of $totalBytes bytes")
        }
    }

    private suspend fun pieceWithRetries(url: String, first: Long, last: Long, requestNumber: Int, userAgent: String): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return piece(url, first, last, requestNumber, userAgent)
            } catch (e: Expired) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= ATTEMPTS) throw e
                delay(retryDelayMs * attempt)
            }
        }
    }

    private fun piece(url: String, first: Long, last: Long, requestNumber: Int, userAgent: String): ByteArray {
        val separator = if ('?' in url) '&' else '?'
        val request = Request.Builder()
            .url("$url${separator}rn=$requestNumber")
            .post(POST_BODY.toRequestBody())
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$first-$last")
            // The bytes are counted against the range; a gzip stream would not be.
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200, 206 -> Unit
                // Asked past the end of a stream whose length was not known.
                416 -> return ByteArray(0)
                403, 410 -> throw Expired(response.code)
                else -> throw IOException("stream piece $first-$last: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("stream piece $first-$last: no body")
            val wanted = (last - first + 1).toInt()
            val buffer = ByteArray(wanted)
            var got = 0
            body.byteStream().use { input ->
                while (got < wanted) {
                    val n = input.read(buffer, got, wanted - got)
                    if (n < 0) break
                    got += n
                }
                // A server that ignored the range would hand over the whole
                // file, from the start, under this piece's offset.
                if (got == wanted && input.read() != -1) {
                    throw IOException("stream piece $first-$last: the server sent more than was asked for")
                }
            }
            return if (got == wanted) buffer else buffer.copyOf(got)
        }
    }

    companion object {
        /**
         * NewPipe's downloader asks googlevideo for 512 KiB blocks, and its
         * player for smaller pieces still; a MiB keeps a four-minute song to
         * four or five requests.
         */
        const val PIECE_BYTES = 1L shl 20

        private const val ATTEMPTS = 3

        /** What YouTube's own clients POST to a stream link. */
        private val POST_BODY = byteArrayOf(0x78, 0)
    }
}
