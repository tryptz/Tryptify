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
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LRCLib (https://lrclib.net) — open, no-auth lyrics API. Used as a fallback
 * when the primary TIDAL `/lyrics` endpoint returns 404, which happens
 * frequently for older / niche / non-Western tracks.
 *
 * Two endpoints used:
 *   1. /api/get?track_name=&artist_name=&album_name=&duration= — exact match.
 *      Requires all four params; LRCLib 404s if any disagrees.
 *   2. /api/search?track_name=&artist_name= — fuzzy match, returns array.
 *      Used when album/duration aren't known.
 *
 * Response carries either `syncedLyrics` (LRC `[mm:ss.cs]text` per line) or
 * `plainLyrics` (newline-delimited unsynced text). We prefer the synced
 * variant; if absent, fall back to plain.
 */
@Singleton
class LrcLibClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun lookup(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        convertToRomaji: Boolean = false,
    ): Lyrics? {
        if (title.isBlank() || artist.isBlank()) return null

        // Qobuz appends the edition/version to the track and album titles with
        // an em dash ("Song — Radio Edit"), and catalogues often carry trailing
        // parenthetical tags ("Song (Remastered)"). LRCLib only has the base
        // title, so we try the title as given first (matches TIDAL / clean
        // metadata) then progressively cleaned variants. The album is cleaned
        // the same way for the exact-match lookup.
        val cleanAlbum = album?.let { stripDecorations(it) }?.takeIf { it.isNotBlank() } ?: album
        val titleCandidates = linkedSetOf(
            title.trim(),
            stripVersion(title),
            stripDecorations(title),
        ).filter { it.isNotBlank() }

        for (candidate in titleCandidates) {
            // Prefer /api/get when all the exact-match params are available;
            // fall back to /api/search for fuzzy matches.
            val exact = if (!cleanAlbum.isNullOrBlank() && durationSeconds != null && durationSeconds > 0) {
                tryGet(candidate, artist, cleanAlbum, durationSeconds)
            } else null
            val item = exact ?: trySearch(candidate, artist)
            if (item != null) return parseLyricsRecord(item, convertToRomaji)
        }
        return null
    }

    /** "Song — Radio Edit" → "Song" (Qobuz's em-dash version join). */
    private fun stripVersion(raw: String): String = raw.substringBefore(" — ").trim()

    /** Also drops a trailing parenthetical/bracket tag: "Song (Radio Edit)" → "Song". */
    private fun stripDecorations(raw: String): String =
        stripVersion(raw).replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]\\s*$"), "").trim()

    private suspend fun tryGet(title: String, artist: String, album: String, durationSeconds: Int): LrcLibRecord? {
        val url = buildString {
            append("$BASE_URL/api/get?")
            append("track_name=").append(title.urlEncode())
            append("&artist_name=").append(artist.urlEncode())
            append("&album_name=").append(album.urlEncode())
            append("&duration=").append(durationSeconds)
        }
        return fetchJson<LrcLibRecord>(url)
    }

    private suspend fun trySearch(title: String, artist: String): LrcLibRecord? {
        val url = buildString {
            append("$BASE_URL/api/search?")
            append("track_name=").append(title.urlEncode())
            append("&artist_name=").append(artist.urlEncode())
        }
        return fetchJson<List<LrcLibRecord>>(url)
            ?.firstOrNull { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
    }

    private suspend inline fun <reified T> fetchJson(url: String): T? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val resp = httpClient.get(url) {
                    header("User-Agent", "MonoTrypT/1.0 (https://github.com/tryptz/monotrypt.android)")
                }
                if (!resp.status.isSuccess()) return@runCatching null
                json.decodeFromString<T>(resp.bodyAsText())
            }.getOrNull()
        }
    }

    private fun parseLyricsRecord(record: LrcLibRecord, convertToRomaji: Boolean): Lyrics? {
        // Synced first — LRC `[mm:ss.cs]text` lines map cleanly to LyricLine.
        record.syncedLyrics?.takeIf { it.isNotBlank() }?.let { synced ->
            val lines = mutableListOf<LyricLine>()
            val regex = Regex("\\[(\\d+):(\\d+\\.\\d+)](.*)")
            synced.split('\n').forEach { rawLine ->
                regex.find(rawLine)?.let { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0
                    val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
                    val timeMs = (minutes * 60 * 1000) + (seconds * 1000).toLong()
                    var text = match.groupValues[3].trim()
                    if (convertToRomaji) text = RomajiConverter.convert(text)
                    if (text.isNotBlank()) lines.add(LyricLine(timeMs, text))
                }
            }
            if (lines.isNotEmpty()) return Lyrics(lines = lines, isSynced = true)
        }
        record.plainLyrics?.takeIf { it.isNotBlank() }?.let { plain ->
            val text = if (convertToRomaji) RomajiConverter.convert(plain) else plain
            val lines = text.split('\n').map { LyricLine(0L, it) }
            return Lyrics(lines = lines, isSynced = false)
        }
        return null
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val BASE_URL = "https://lrclib.net"
        // lrclib.net regularly takes 7-9s to answer a search/get, so a tight
        // budget here silently dropped every result (→ "No lyrics available",
        // most visibly for Qobuz tracks, which depend on LRCLib). Give it real
        // headroom; the fetch is async behind a "Loading lyrics…" state.
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

@Serializable
private data class LrcLibRecord(
    val id: Long? = null,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("albumName") val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)
