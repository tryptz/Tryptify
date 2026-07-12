package tf.monochrome.android.data.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import javax.inject.Inject
import javax.inject.Singleton

data class AudioSnippet(
    val bytes: ByteArray,
    val mimeType: String
)

@Singleton
class AudioSnippetFetcher @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val MAX_BYTES = 512 * 1024 // 512KB cap
    }

    suspend fun fetchSnippet(streamUrl: String): AudioSnippet {
        // Streaming request (prepareGet) with a hard read cap: the Range
        // header is only a hint — a server that ignores it returns the whole
        // file, and a non-streaming get() would buffer all of it into memory
        // (SavedCall) before the truncation ever ran. Here at most MAX_BYTES
        // ever exist on the heap and the connection is dropped at the cap.
        return httpClient.prepareGet(streamUrl) {
            header("Range", "bytes=0-${MAX_BYTES - 1}")
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val out = java.io.ByteArrayOutputStream(MAX_BYTES)
            val buffer = ByteArray(16 * 1024)
            while (!channel.isClosedForRead && out.size() < MAX_BYTES) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                out.write(buffer, 0, minOf(read, MAX_BYTES - out.size()))
            }
            val mimeType = response.contentType()?.toString()?.split(";")?.firstOrNull()?.trim()
                ?: inferMimeType(streamUrl)
            AudioSnippet(bytes = out.toByteArray(), mimeType = mimeType)
        }
    }

    private fun inferMimeType(url: String): String {
        return when {
            url.contains(".flac", ignoreCase = true) -> "audio/flac"
            url.contains(".mp4", ignoreCase = true) || url.contains(".m4a", ignoreCase = true) -> "audio/mp4"
            url.contains(".ogg", ignoreCase = true) -> "audio/ogg"
            url.contains(".wav", ignoreCase = true) -> "audio/wav"
            else -> "audio/mpeg"
        }
    }
}
