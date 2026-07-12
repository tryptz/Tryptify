package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kugou (kugou.com) — free, no-auth lyrics source used as a second word-level
 * fallback (after NetEase) when TIDAL has no synced lyrics. Kugou's KRC
 * format carries per-word offsets, giving karaoke-style timing for a catalog
 * that leans CJK but also covers a lot of Western material.
 *
 * The endpoints are unofficial and the KRC payload is obfuscated (XOR +
 * zlib), so every step is defensive — a bad response, timeout, or malformed
 * payload degrades to "no result" rather than throwing.
 *
 * Two calls:
 *  1. `krcs.kugou.com/search` — keyword + duration match, returns candidates.
 *  2. `lyrics.kugou.com/download` — the winning candidate's KRC blob
 *     (base64 of a 4-byte "krc1" magic header + XOR-obfuscated zlib stream).
 */
@Singleton
class KugouLyricsClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun lookup(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        convertToRomaji: Boolean = false,
    ): Lyrics? {
        if (title.isBlank() || artist.isBlank()) return null
        val durationMs = durationSeconds?.takeIf { it > 0 }?.let { it * 1000L }
        val candidate = search(title, artist, durationMs) ?: return null
        val content = download(candidate.id, candidate.accesskey) ?: return null
        val decrypted = decryptKrc(content) ?: return null
        val lines = parseKrc(decrypted, convertToRomaji)
        if (lines.isEmpty()) return null
        return Lyrics(lines = lines, isSynced = true)
    }

    private suspend fun search(title: String, artist: String, durationMs: Long?): KugouCandidate? {
        val keyword = "$artist - $title"
        val url = buildString {
            append("$SEARCH_URL?ver=1&man=yes&client=mobi")
            append("&keyword=").append(keyword.urlEncode())
            if (durationMs != null) append("&duration=").append(durationMs)
        }
        val envelope = fetchJson<KugouSearchEnvelope>(url) ?: return null
        val candidates = envelope.candidates.orEmpty()
        if (candidates.isEmpty()) return null
        return if (durationMs != null) {
            candidates.minByOrNull { c -> kotlin.math.abs((c.duration ?: durationMs) - durationMs) }
        } else {
            candidates.first()
        }
    }

    private suspend fun download(id: String, accesskey: String): String? {
        val url = "$DOWNLOAD_URL?ver=1&client=pc&fmt=krc&charset=utf8" +
            "&id=${id.urlEncode()}&accesskey=${accesskey.urlEncode()}"
        return fetchJson<KugouDownloadEnvelope>(url)?.content?.takeIf { it.isNotBlank() }
    }

    private suspend inline fun <reified T> fetchJson(url: String): T? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val resp = httpClient.get(url) { header("User-Agent", USER_AGENT) }
                if (!resp.status.isSuccess()) return@runCatching null
                json.decodeFromString<T>(resp.bodyAsText())
            }.getOrNull()
        }
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val SEARCH_URL = "https://krcs.kugou.com/search"
        private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val USER_AGENT = "Android712-KugouMusic-10396-Websearch"

        // KRC blobs are prefixed with a literal 4-byte "krc1" magic, then the
        // remaining bytes are XOR-obfuscated (cycling this 16-byte key) zlib
        // data. Both the magic and the key are fixed constants published
        // across the Kugou-lyrics reverse-engineering community (see e.g.
        // ddddxxx/LyricsKit's Kugou provider) — not something derived here.
        private val KRC_MAGIC = byteArrayOf(0x6B, 0x72, 0x63, 0x31) // "krc1"
        private val KRC_KEY = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5E.toByte(), 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69,
        )

        private val KRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val KRC_WORD_REGEX = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

        /** Strips the "krc1" magic, XOR-decodes, and zlib-inflates a KRC blob. */
        fun decryptKrc(base64Content: String): String? = runCatching {
            val raw = Base64.getDecoder().decode(base64Content)
            require(raw.size > KRC_MAGIC.size)
            val body = raw.copyOfRange(KRC_MAGIC.size, raw.size)
            val decoded = ByteArray(body.size) { i ->
                (body[i].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
            }
            inflate(decoded)
        }.getOrNull()

        private fun inflate(deflated: ByteArray): String {
            val inflater = Inflater()
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream(deflated.size * 3)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, count)
            }
            inflater.end()
            return out.toString("UTF-8")
        }

        /**
         * Decrypted KRC body: metadata header lines (`[ti:]`, `[ar:]`, …) are
         * skipped since they don't match the timed-line pattern. Each timed
         * line is `[lineStartMs,lineDurationMs]` followed by one or more
         * `<wordOffsetMs,wordDurationMs,0>word` runs, where the word offset
         * is relative to the *line* start (not absolute).
         */
        fun parseKrc(raw: String, convertToRomaji: Boolean): List<LyricLine> {
            val lines = mutableListOf<LyricLine>()
            raw.split('\n').forEach { rawLine ->
                val lineMatch = KRC_LINE_REGEX.find(rawLine.trim()) ?: return@forEach
                val lineStartMs = lineMatch.groupValues[1].toLongOrNull() ?: return@forEach
                val content = lineMatch.groupValues[3]

                val words = KRC_WORD_REGEX.findAll(content).mapNotNull { wordMatch ->
                    val offsetMs = wordMatch.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val durationMs = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                    var text = wordMatch.groupValues[3]
                    if (convertToRomaji) text = RomajiConverter.convert(text)
                    val startMs = lineStartMs + offsetMs
                    LyricWord(startMs = startMs, endMs = startMs + durationMs, text = text)
                }.filter { it.text.isNotBlank() }.toList()

                if (words.isEmpty()) return@forEach
                val text = words.joinToString("") { it.text }.trim()
                if (text.isBlank()) return@forEach
                lines.add(LyricLine(timeMs = lineStartMs, text = text, words = words))
            }
            return lines
        }
    }
}

@Serializable
private data class KugouSearchEnvelope(
    val status: Int? = null,
    val candidates: List<KugouCandidate>? = null,
)

@Serializable
private data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val duration: Long? = null,
)

@Serializable
private data class KugouDownloadEnvelope(
    val status: Int? = null,
    val content: String? = null,
)
