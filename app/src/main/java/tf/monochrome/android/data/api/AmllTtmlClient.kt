package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.domain.model.Lyrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AMLL TTML database (https://github.com/amll-dev/amll-ttml-db) — tens of
 * thousands of word-timed lyrics, timed by hand and reviewed, in Apple-style
 * TTML. Strongest for Chinese and Japanese music; it covers some Western
 * songs too.
 *
 * Files are named by NetEase song id, which [NetEaseLyricsClient.findSongId]
 * has already found and checked against this recording, so a lookup is one
 * static file read: a 404 is simply "not in the database".
 */
@Singleton
class AmllTtmlClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun lookup(netEaseSongId: Long, convertToRomaji: Boolean = false): Lyrics? {
        val ttml = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val resp = httpClient.get("$BASE_URL/ncm-lyrics/$netEaseSongId.ttml") {
                    header("User-Agent", USER_AGENT)
                }
                if (resp.status.isSuccess()) resp.bodyAsText() else null
            }.getOrNull()
        } ?: return null
        return TtmlLyricsParser.parse(ttml, convertToRomaji)
    }

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main"
        private const val REQUEST_TIMEOUT_MS = 6_000L
        private const val USER_AGENT = "Tryptify/1.0 (https://github.com/tryptz/Tryptify)"
    }
}
