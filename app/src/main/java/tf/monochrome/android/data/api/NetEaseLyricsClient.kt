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
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NetEase Cloud Music (music.163.com) — free, no-auth lyrics source used as a
 * word-level fallback when TIDAL has no synced lyrics for a track. NetEase's
 * web endpoints are unofficial (undocumented but stable and widely relied on
 * by open-source lyrics tools), so every call is defensive: a bad response,
 * timeout, or format change degrades to "no result" rather than throwing.
 *
 * Two calls:
 *  1. `/api/search/get` — free-text search, returns candidate song ids, which
 *     [LyricsMatch] narrows to this recording.
 *  2. `/api/song/lyric?yv=-1` — the winning candidate's lyric payload, which
 *     carries `yrc` (word-level "verbatim" karaoke timing — NetEase's own
 *     enhanced format) alongside the plain `lrc` (line-level).
 */
@Singleton
class NetEaseLyricsClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun lookup(query: LyricsQuery, convertToRomaji: Boolean = false): Lyrics? {
        val songId = findSongId(query) ?: return null
        return lyricsFor(songId, convertToRomaji)
    }

    /**
     * NetEase's id for [query]'s song, or null. Public because the AMLL TTML
     * database is keyed by it, so one search serves both sources.
     */
    suspend fun findSongId(query: LyricsQuery): Long? {
        if (query.title.isBlank() || query.artist.isBlank()) return null
        val artist = LyricsMatch.primaryArtist(query.artist)
        // The title as given, then fully cleaned; the middle spellings rarely
        // find anything the cleaned one does not, and each costs a round trip.
        val variants = LyricsMatch.titleVariants(query.title).let { listOf(it.first(), it.last()) }.distinct()
        for (title in variants) {
            val candidates = search("$title $artist")
            val chosen = LyricsMatch.pick(
                query,
                candidates,
                title = { it.name },
                artist = { song -> song.allArtists().joinToString(", ") { it.name.orEmpty() } },
                durationMs = { it.runtimeMs() },
            )
            if (chosen?.id != null) return chosen.id
        }
        return null
    }

    suspend fun lyricsFor(songId: Long, convertToRomaji: Boolean): Lyrics? {
        val payload = fetchLyricPayload(songId) ?: return null
        return parsePayload(payload, convertToRomaji)
    }

    /**
     * `/api/search/get/web`, which this used to call, now answers with an
     * encrypted hex string where the JSON was, so every lookup silently came
     * back empty. `/api/search/get` still sends the same JSON; the PC cloud
     * search is the fallback, with its own field names (`ar`, `dt`).
     */
    private suspend fun search(keywords: String): List<NetEaseSong> {
        val q = keywords.urlEncode()
        val primary = fetchJson<NetEaseSearchEnvelope>("$BASE_URL/api/search/get?s=$q&type=1&offset=0&limit=10")
            ?.result?.songs.orEmpty()
        if (primary.isNotEmpty()) return primary
        return fetchJson<NetEaseSearchEnvelope>("$BASE_URL/api/cloudsearch/pc?s=$q&type=1&offset=0&limit=10")
            ?.result?.songs.orEmpty()
    }

    private suspend fun fetchLyricPayload(songId: Long): NetEaseLyricEnvelope? {
        val url = "$BASE_URL/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1&yv=-1"
        return fetchJson<NetEaseLyricEnvelope>(url)
    }

    /** Prefers word-level `yrc`; falls back to line-level `lrc`. */
    private fun parsePayload(payload: NetEaseLyricEnvelope, convertToRomaji: Boolean): Lyrics? {
        // Instrumentals get a one-line "纯音乐，请欣赏" ("pure music, enjoy")
        // placeholder rather than an empty payload.
        val lrcText = payload.lrc?.lyric.orEmpty()
        if (lrcText.contains(INSTRUMENTAL_MARK) && lrcText.lines().count { LRC_LINE_REGEX.containsMatchIn(it) } <= 2) {
            return null
        }
        payload.yrc?.lyric?.takeIf { it.isNotBlank() }?.let { yrc ->
            val lines = parseYrc(yrc, convertToRomaji)
            if (lines.isNotEmpty()) return Lyrics(lines = lines, isSynced = true)
        }
        payload.lrc?.lyric?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = parseLrc(lrc, convertToRomaji)
            if (lines.isNotEmpty()) return Lyrics(lines = lines, isSynced = true)
        }
        return null
    }

    private suspend inline fun <reified T> fetchJson(url: String): T? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val resp = httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Referer", "https://music.163.com/")
                }
                if (!resp.status.isSuccess()) return@runCatching null
                json.decodeFromString<T>(resp.bodyAsText())
            }.getOrNull()
        }
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val BASE_URL = "https://music.163.com"
        private const val INSTRUMENTAL_MARK = "纯音乐"
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private val YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val YRC_WORD_REGEX = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
        private val LRC_LINE_REGEX = Regex("""\[(\d+):(\d+\.\d+)](.*)""")

        /**
         * NetEase's word-level "yrc" format: each line is
         * `[lineStartMs,lineDurationMs]` followed by one or more
         * `(wordStartMs,wordDurationMs,0)word` runs concatenated in sequence
         * (word text may include a trailing space, which is what separates
         * words visually). Metadata lines ({"t":...} JSON, used for
         * translator credits) are skipped — they don't match the line regex.
         */
        fun parseYrc(raw: String, convertToRomaji: Boolean): List<LyricLine> {
            val lines = mutableListOf<LyricLine>()
            raw.split('\n').forEach { rawLine ->
                val lineMatch = YRC_LINE_REGEX.find(rawLine.trim()) ?: return@forEach
                val lineStartMs = lineMatch.groupValues[1].toLongOrNull() ?: return@forEach
                val content = lineMatch.groupValues[3]

                val words = YRC_WORD_REGEX.findAll(content).mapNotNull { wordMatch ->
                    val startMs = wordMatch.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val durationMs = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                    var text = wordMatch.groupValues[3]
                    if (convertToRomaji) text = RomajiConverter.convert(text)
                    LyricWord(startMs = startMs, endMs = startMs + durationMs, text = text)
                }.filter { it.text.isNotBlank() }.toList()

                if (words.isEmpty()) return@forEach
                val text = words.joinToString("") { it.text }.trim()
                if (text.isBlank()) return@forEach
                lines.add(LyricLine(timeMs = lineStartMs, text = text, words = words))
            }
            return lines
        }

        /** Plain `[mm:ss.cs]text` line-level LRC — same shape as LRCLib's. */
        fun parseLrc(raw: String, convertToRomaji: Boolean): List<LyricLine> {
            val lines = mutableListOf<LyricLine>()
            raw.split('\n').forEach { rawLine ->
                val match = LRC_LINE_REGEX.find(rawLine) ?: return@forEach
                val minutes = match.groupValues[1].toLongOrNull() ?: 0
                val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
                val timeMs = (minutes * 60 * 1000) + (seconds * 1000).toLong()
                var text = match.groupValues[3].trim()
                if (text.isBlank()) return@forEach
                if (convertToRomaji) text = RomajiConverter.convert(text)
                lines.add(LyricLine(timeMs, text))
            }
            return lines
        }
    }
}

@Serializable
private data class NetEaseSearchEnvelope(
    val result: NetEaseSearchResult? = null,
    val code: Int? = null,
)

@Serializable
private data class NetEaseSearchResult(
    val songs: List<NetEaseSong>? = null,
)

/** Both search endpoints' shapes: `artists`/`duration` (search/get) and `ar`/`dt` (cloudsearch). */
@Serializable
private data class NetEaseSong(
    val id: Long? = null,
    val name: String? = null,
    val artists: List<NetEaseArtist>? = null,
    val ar: List<NetEaseArtist>? = null,
    val duration: Long? = null,
    val dt: Long? = null,
) {
    fun allArtists(): List<NetEaseArtist> = artists ?: ar.orEmpty()
    fun runtimeMs(): Long? = duration ?: dt
}

@Serializable
private data class NetEaseArtist(
    val name: String? = null,
)

@Serializable
private data class NetEaseLyricEnvelope(
    val lrc: NetEaseLyricField? = null,
    val yrc: NetEaseLyricField? = null,
    val code: Int? = null,
)

@Serializable
private data class NetEaseLyricField(
    @SerialName("lyric") val lyric: String? = null,
)
