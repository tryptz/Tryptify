package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LyricsPlus ("KPoe", https://github.com/ibratabian17/lyricsplus) — the open
 * lyrics backend behind the YouLy+ extension. It scrapes Apple Music
 * (syllable-timed TTML), QQ Music (word-timed QRC), its own community
 * submissions and Musixmatch, and serves them as one JSON shape, free and
 * without auth.
 *
 * It is run by volunteers on donated servers, so this is polite about it:
 * one mirror and source at a time, each request names the app in
 * `X-Client-Package` as the API docs ask, and a mirror that fails or times
 * out is skipped for the rest of the lookup.
 *
 * The mirrors do not agree on the `source` parameter (checked against both,
 * 2026-10): binimum serves Apple whatever is asked and 404s on names it does
 * not know, and every mirror 404s on a comma list. So each mirror gets its
 * own plan of single sources, best timing first.
 */
@Singleton
class KpoeLyricsClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun lookup(query: LyricsQuery, convertToRomaji: Boolean = false): Lyrics? {
        if (query.title.isBlank() || query.artist.isBlank()) return null
        // QQ times Chinese and Japanese far more often than Apple does, and an
        // Apple miss is slow, so ask QQ first for those.
        val qqFirst = LyricsText.hasUnspacedScript(query.title + query.artist)
        var lineTimed: Lyrics? = null
        // A song LyricsPlus does not have costs a timeout per source, and the
        // resolver waits for every source when nobody has anything — so a
        // miss here was what kept an instrumental on "Loading lyrics…".
        val wordTimed = withTimeoutOrNull(LOOKUP_BUDGET_MS) {
            for (mirror in MIRRORS) {
                val sources = if (qqFirst) mirror.sources.sortedByDescending { it == "qq" } else mirror.sources
                for (source in sources) {
                    val outcome = fetch(mirror.baseUrl, query, source)
                    if (outcome is Outcome.Down) break
                    val response = (outcome as? Outcome.Found)?.response ?: continue
                    if (!matches(response, query)) continue
                    val lyrics = parse(response, convertToRomaji) ?: continue
                    if (LyricsText.isWordTimed(lyrics)) return@withTimeoutOrNull lyrics
                    if (lineTimed == null) lineTimed = lyrics
                }
            }
            null
        }
        return wordTimed ?: lineTimed
    }

    private sealed interface Outcome {
        data class Found(val response: KpoeResponse) : Outcome
        data object Missing : Outcome
        data object Down : Outcome
    }

    private suspend fun fetch(baseUrl: String, query: LyricsQuery, source: String?): Outcome {
        val url = buildString {
            append(baseUrl).append("/v2/lyrics/get?")
            append("title=").append(query.title.urlEncode())
            append("&artist=").append(query.artist.urlEncode())
            query.album?.takeIf { it.isNotBlank() }?.let { append("&album=").append(it.urlEncode()) }
            query.durationSeconds?.let { append("&duration=").append(it) }
            source?.let { append("&source=").append(it) }
        }
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            try {
                val resp = httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("X-Client-Package", CLIENT_PACKAGE)
                }
                when (resp.status) {
                    HttpStatusCode.OK -> Outcome.Found(json.decodeFromString<KpoeResponse>(resp.bodyAsText()))
                    HttpStatusCode.NotFound -> Outcome.Missing
                    else -> Outcome.Down
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                Outcome.Down
            }
        } ?: Outcome.Down // timed out: the mirror is struggling, and its other sources would too
    }

    /**
     * The server picks the song itself; when it says which one, hold it to
     * the same rules as every other source. Musixmatch answers carry no
     * title, and those are trusted to the server's own matching.
     */
    private fun matches(response: KpoeResponse, query: LyricsQuery): Boolean {
        val meta = response.metadata ?: return true
        val title = meta.title
        if (!title.isNullOrBlank()) {
            if (!LyricsMatch.titleMatches(query.title, title)) return false
            if (LyricsMatch.isOtherVersion(query.title, title)) return false
        }
        val artist = meta.artist
        if (!artist.isNullOrBlank() && !LyricsMatch.artistMatches(query.artist, artist)) {
            // The server matched on our artist and runtime already; an exact
            // title with the artist in another script (Jay Chou / 周杰倫) is
            // still this song. Anything looser is not trusted.
            val exactTitle = !title.isNullOrBlank() && LyricsMatch.baseTitle(title) == LyricsMatch.baseTitle(query.title)
            return exactTitle && query.durationSeconds != null
        }
        return true
    }

    private fun parse(response: KpoeResponse, convertToRomaji: Boolean): Lyrics? {
        val items = dropCreditPreamble(response.lyrics.orEmpty(), response.metadata?.agents)
        if (items.isEmpty()) return null
        return when (response.type) {
            "Word" -> {
                val lines = items.mapNotNull { item ->
                    val syllables = item.syllabus.orEmpty().map {
                        TimedSyllable(it.time.toLong(), (it.time + it.duration).toLong(), it.text.orEmpty())
                    }
                    val words = LyricsText.groupSyllables(syllables, convertToRomaji)
                    if (words.isNotEmpty()) LyricsText.lineOf(item.timeMs, words) else lineOnly(item, convertToRomaji)
                }
                Lyrics(lines = lines.sortedBy { it.timeMs }, isSynced = true).takeIf { lines.isNotEmpty() }
            }
            "Line" -> {
                val lines = items.mapNotNull { lineOnly(it, convertToRomaji) }
                Lyrics(lines = lines.sortedBy { it.timeMs }, isSynced = true).takeIf { lines.isNotEmpty() }
            }
            else -> {
                val lines = items.mapNotNull { item ->
                    item.text?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { LyricLine(0L, if (convertToRomaji) RomajiConverter.convert(it) else it) }
                }
                Lyrics(lines = lines, isSynced = false).takeIf { lines.isNotEmpty() }
            }
        }
    }

    /**
     * QQ files open with timed credit lines ("词：周杰伦", "混音工程：…"), and
     * LyricsPlus turns each role into a singer: the line reads "周杰伦", sung
     * by an agent named "作词". Each such line is the first one credited to an
     * agent named for a role; the last of them shares its agent with the whole
     * song after it, which is why it is the first appearance that counts.
     * Real duet agents ("v1", "Vocal 1", a singer's name) are never roles.
     */
    private fun dropCreditPreamble(items: List<KpoeLine>, agents: JsonElement?): List<KpoeLine> {
        // Keyed "voice1" while lines name the alias "v1"; index both.
        val names = mutableMapOf<String, String>()
        (agents as? JsonObject ?: return items).forEach { (key, agent) ->
            val obj = agent as? JsonObject ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            names[key] = name
            obj["alias"]?.jsonPrimitive?.contentOrNull?.let { names[it] = name }
        }
        val seen = mutableSetOf<String>()
        var drop = 0
        for (item in items) {
            val singer = item.singer() ?: break
            if (!seen.add(singer) || !LyricsText.isCreditRole(names[singer].orEmpty())) break
            drop++
        }
        return items.drop(drop)
    }

    private fun lineOnly(item: KpoeLine, convertToRomaji: Boolean): LyricLine? {
        var text = item.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (convertToRomaji) text = RomajiConverter.convert(text)
        return LyricLine(timeMs = item.timeMs, text = text)
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private class Mirror(val baseUrl: String, val sources: List<String?>)

    companion object {
        private val MIRRORS = listOf(
            // Apple's syllable timing whatever is asked; one call says all it can.
            Mirror("https://lyricsplus.binimum.org", listOf(null)),
            // Apple, then QQ (strong for Chinese and Japanese), then the
            // community set, then its default — Musixmatch, line-timed.
            Mirror("https://lyricsplus.prjktla.my.id", listOf("apple", "qq", "lyricsplus", null)),
        )
        private const val REQUEST_TIMEOUT_MS = 4_000L
        private const val LOOKUP_BUDGET_MS = 6_000L
        private const val USER_AGENT = "Tryptify/1.0 (https://github.com/tryptz/Tryptify)"
        private const val CLIENT_PACKAGE = "Tryptify <https://github.com/tryptz/Tryptify>"
    }
}

@Serializable
private data class KpoeResponse(
    val type: String? = null,
    val metadata: KpoeMetadata? = null,
    val lyrics: List<KpoeLine>? = null,
)

@Serializable
private data class KpoeMetadata(
    val source: String? = null,
    val title: String? = null,
    val artist: String? = null,
    /** alias → {name}; kept loose, since sources fill it differently. */
    val agents: JsonElement? = null,
)

/** Times in milliseconds; read as Double because some sources send fractions. */
@Serializable
private data class KpoeLine(
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val text: String? = null,
    val syllabus: List<KpoeSyllable>? = null,
    /** {singer, key, …} — an object in every answer seen, but read loosely. */
    val element: JsonElement? = null,
) {
    val timeMs: Long get() = time.toLong()

    fun singer(): String? = (element as? JsonObject)?.get("singer")?.jsonPrimitive?.contentOrNull
}

@Serializable
private data class KpoeSyllable(
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val text: String? = null,
)
