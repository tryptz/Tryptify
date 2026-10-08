package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.Lyrics
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * lrc.red (BiniLyrics) — a free, no-auth catalogue of Apple-style TTML, much
 * of it syllable-timed, searched by text and keyed by ISRC.
 *
 * Two calls:
 *  1. `/api/v1?q=` — candidates with title, artist, album, runtime (seconds),
 *     ISRC and `timing_type` (`word` / `line` / none).
 *  2. the winner's `lyricsUrl` — the TTML itself, read by [TtmlLyricsParser].
 *
 * The search ranks remixes, covers and karaoke versions beside the original,
 * so candidates go through [LyricsMatch], word-timed ones first.
 */
@Singleton
class LrcRedLyricsClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun lookup(query: LyricsQuery, convertToRomaji: Boolean = false): Lyrics? {
        if (query.title.isBlank() || query.artist.isBlank()) return null
        val artist = LyricsMatch.primaryArtist(query.artist)
        val variants = LyricsMatch.titleVariants(query.title).let { listOf(it.first(), it.last()) }.distinct()
        for (title in variants) {
            val results = search("$title $artist")
            if (results.isEmpty()) continue
            val chosen = TIMING_ORDER.firstNotNullOfOrNull { timing ->
                LyricsMatch.pick(
                    query,
                    results.filter { it.timingType == timing && isOwnUrl(it.lyricsUrl) },
                    title = { it.trackName },
                    artist = { it.artistName },
                    durationMs = { r -> r.duration?.let { (it * 1000).toLong() } },
                )
            } ?: continue
            val ttml = fetchText(chosen.lyricsUrl ?: continue) ?: continue
            return TtmlLyricsParser.parse(ttml, convertToRomaji)
        }
        return null
    }

    private suspend fun search(q: String): List<LrcRedResult> {
        val body = fetchText("$BASE_URL/api/v1?q=${q.urlEncode()}") ?: return emptyList()
        return runCatching { json.decodeFromString<LrcRedSearch>(body).results.orEmpty() }.getOrDefault(emptyList())
    }

    /** The TTML link comes from the server's answer, so only ever follow one back to lrc.red. */
    private fun isOwnUrl(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && (uri.host == HOST || uri.host.endsWith(".$HOST"))
    }.getOrDefault(false)

    private suspend fun fetchText(url: String): String? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        runCatching {
            val resp = httpClient.get(url) { header("User-Agent", USER_AGENT) }
            if (resp.status.isSuccess()) resp.bodyAsText() else null
        }.getOrNull()
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val HOST = "lrc.red"
        private const val BASE_URL = "https://$HOST"
        private const val REQUEST_TIMEOUT_MS = 8_000L
        private const val USER_AGENT = "Tryptify/1.0 (https://github.com/tryptz/Tryptify)"

        /** Word timing first; line timing only when no word-timed file is this recording. */
        private val TIMING_ORDER = listOf("word", "line")
    }
}

@Serializable
private data class LrcRedSearch(val results: List<LrcRedResult>? = null)

@Serializable
private data class LrcRedResult(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    val duration: Double? = null,
    val isrc: String? = null,
    @SerialName("lyricsUrl") val lyricsUrl: String? = null,
    @SerialName("timing_type") val timingType: String? = null,
)
