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
    suspend fun lookup(query: LyricsQuery, convertToRomaji: Boolean = false): Lyrics? {
        if (query.title.isBlank() || query.artist.isBlank()) return null

        // Qobuz appends the edition/version to the track and album titles with
        // an em dash ("Song — Radio Edit"), and catalogues often carry trailing
        // parenthetical tags ("Song (Remastered)"). LRCLib only has the base
        // title, so we try the title as given first (matches TIDAL / clean
        // metadata) then the fully cleaned one. The album is cleaned the same
        // way for the exact-match lookup.
        val album = query.album
        val cleanAlbum = album?.let { stripDecorations(it) }?.takeIf { it.isNotBlank() } ?: album
        val durationSeconds = query.durationSeconds
        // LRCLib files each song under one artist; a credit list never matches.
        val searchArtist = LyricsMatch.primaryArtist(query.artist)
        val titleCandidates = LyricsMatch.titleVariants(query.title).let { listOf(it.first(), it.last()) }.distinct()

        var untimedFallback: LrcLibRecord? = null
        for (candidate in titleCandidates) {
            // /api/get enforces its own ±2 s runtime match, so a hit is this cut.
            if (!cleanAlbum.isNullOrBlank() && durationSeconds != null) {
                tryGet(candidate, query.artist, cleanAlbum, durationSeconds)
                    ?.let { record -> parseLyricsRecord(record, convertToRomaji)?.let { return it } }
            }
            val results = trySearch(candidate, searchArtist).filterNot { it.instrumental }
            val timed = LyricsMatch.pick(
                query,
                results.filter { !it.syncedLyrics.isNullOrBlank() },
                title = { it.trackName },
                artist = { it.artistName },
                durationMs = { it.durationMs() },
            )
            if (timed != null) parseLyricsRecord(timed, convertToRomaji)?.let { return it }
            // Same song, other cut: its words are right, its timings are not.
            if (untimedFallback == null) {
                untimedFallback = LyricsMatch.pick(
                    query.copy(durationMs = null),
                    results.filter { !it.plainLyrics.isNullOrBlank() || !it.syncedLyrics.isNullOrBlank() },
                    title = { it.trackName },
                    artist = { it.artistName },
                    durationMs = { null },
                )
            }
        }
        return untimedFallback?.let { parseUntimed(it, convertToRomaji) }
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

    private suspend fun trySearch(title: String, artist: String): List<LrcLibRecord> {
        val url = buildString {
            append("$BASE_URL/api/search?")
            append("track_name=").append(title.urlEncode())
            append("&artist_name=").append(artist.urlEncode())
        }
        return fetchJson<List<LrcLibRecord>>(url).orEmpty()
    }

    /** The record's words without its timings, from whichever field it has. */
    private fun parseUntimed(record: LrcLibRecord, convertToRomaji: Boolean): Lyrics? {
        val text = record.plainLyrics?.takeIf { it.isNotBlank() }
            ?: record.syncedLyrics?.lines()?.joinToString("\n") { it.replace(LRC_TAG, "").trim() }
            ?: return null
        val converted = if (convertToRomaji) RomajiConverter.convert(text) else text
        val lines = converted.split('\n').map { LyricLine(0L, it) }
        return if (lines.any { it.text.isNotBlank() }) Lyrics(lines = lines, isSynced = false) else null
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
        private val LRC_TAG = Regex("""\[\d+:\d+(?:\.\d+)?]""")
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
) {
    fun durationMs(): Long? = duration?.let { (it * 1000).toLong() }
}
