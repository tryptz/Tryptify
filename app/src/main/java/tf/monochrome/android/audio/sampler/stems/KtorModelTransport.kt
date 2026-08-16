// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [ModelTransport].
 *
 * Deliberately the least interesting file in the feature. Everything worth
 * getting right about installing a model — resuming, verifying, refusing a
 * corrupt one — lives in [StemModelManager] where it can be tested without a
 * network. This part only has to fetch bytes and be honest about the range.
 *
 * The one thing it must not do is quietly serve a whole file when a range was
 * asked for. A server that ignores `Range` answers 200 rather than 206, and
 * appending a fresh copy to a partial download produces a file of the wrong
 * length — or, if the lengths happened to line up, of the wrong contents. So a
 * ranged request that does not come back as 206 is an error here rather than
 * data. [StemModelManager] would catch the damage anyway when the hash failed,
 * but failing at the point the server misbehaved says what actually went wrong.
 *
 * `prepareGet` rather than `get` because a model is hundreds of megabytes and a
 * buffered `get` would put all of it on the heap before a single byte reached
 * the disk.
 */
@Singleton
class KtorModelTransport @Inject constructor(
    private val client: HttpClient,
) : ModelTransport {

    override suspend fun fetchText(url: String): String = client.get(url).bodyAsText()

    override suspend fun download(
        url: String,
        offset: Long,
        sink: OutputStream,
        onBytes: (Long) -> Unit,
    ): Long {
        var written = 0L
        client.prepareGet(url) {
            if (offset > 0L) header("Range", "bytes=$offset-")
        }.execute { response ->
            val status = response.status.value
            if (offset > 0L && status != 206) {
                error("Server ignored the range request ($status); resuming would corrupt the file")
            }
            if (offset == 0L && status !in 200..299) {
                error("Download failed: $status")
            }

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(1 shl 16)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                written += read
                onBytes(written)
            }
        }
        return written
    }
}
