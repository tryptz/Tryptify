package tf.monochrome.android.data.import_

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * One song of an M3U playlist: where it is ([location], a path or an address,
 * as the file has it), and what its `#EXTINF` line said about it, if it had
 * one.
 */
data class M3uEntry(
    val location: String,
    /** The `#EXTINF` text after the comma, whole: usually "Artist - Title". */
    val display: String? = null,
    /** The `#EXTINF` length in seconds; null when absent, zero or -1 (a stream). */
    val durationSeconds: Int? = null,
) {
    /** How the song is named in the list of songs that were not found. */
    val label: String
        get() = display?.takeIf { it.isNotBlank() }
            ?: location.replace('\\', '/').trimEnd('/').substringAfterLast('/')
}

/** A parsed M3U: its `#PLAYLIST` name, if it has one, and its songs in order. */
data class M3uPlaylist(val title: String?, val entries: List<M3uEntry>)

/**
 * Reads `.m3u` and `.m3u8` playlists (issue #155): the plain list of paths, and
 * the extended form with `#EXTM3U`, `#EXTINF` and `#PLAYLIST` lines.
 *
 * Nothing here needs Android, so it is tested against whole files
 * ([M3uTest]); finding each song among the listener's own music is
 * [M3uLocalMatcher]'s, and reading the picked file [M3uPlaylistImporter]'s.
 */
object M3u {
    /** A playlist file past this is not a playlist; the same cap the CSV import has. */
    const val MAX_BYTES: Int = PlaylistCsv.MAX_BYTES

    /** More songs than any playlist has; past it the file is something else. */
    const val MAX_ENTRIES: Int = 50_000

    /**
     * Bytes to text. `.m3u8` is UTF-8 by definition; `.m3u` is whatever the
     * program that wrote it used, which is UTF-8 from anything recent and
     * Windows-1252 from the players the format comes from. So: a byte-order
     * mark decides when there is one; otherwise UTF-8 if the bytes are valid
     * UTF-8 (text in another encoding with an accent in it practically never
     * is), and Windows-1252 if not. Read as UTF-8 regardless, "Beyoncé" from
     * an old `.m3u` came out with a replacement character and matched no file.
     */
    fun decode(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && at(0) == 0xFF && at(1) == 0xFE) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && at(0) == 0xFE && at(1) == 0xFF) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    // Every JVM and Android has it; ISO-8859-1 is the same letters if one ever did not.
    private val WINDOWS_1252: Charset =
        runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /**
     * The playlist in [text]. A line starting with `#` is a directive or a
     * comment, as the format has it; every other line that is not blank is a
     * song. An `#EXTINF` describes the next song only.
     *
     * Throws [IllegalArgumentException] for text that is not a playlist: it
     * holds NUL characters (an audio file picked by mistake, read as text,
     * would otherwise come out as thousands of "songs"), or more lines than
     * [MAX_ENTRIES].
     */
    fun parse(text: String): M3uPlaylist {
        require('\u0000' !in text) { "not a text file" }
        var title: String? = null
        var display: String? = null
        var duration: Int? = null
        val entries = ArrayList<M3uEntry>()
        for (raw in text.lineSequence()) {
            // lineSequence splits on \n, \r\n and a lone \r.
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith(EXTINF, ignoreCase = true) -> {
                        val info = line.substring(EXTINF.length)
                        val comma = info.indexOf(',')
                        val head = if (comma >= 0) info.substring(0, comma) else info
                        display = if (comma >= 0) info.substring(comma + 1).trim().ifEmpty { null } else null
                        // "123", "123.4", "-1", and "-1 tvg-id=…" from IPTV lists.
                        duration = LEADING_NUMBER.find(head.trim())?.value?.toDoubleOrNull()
                            ?.let { Math.round(it).toInt() }?.takeIf { it > 0 }
                    }
                    line.startsWith(PLAYLIST, ignoreCase = true) ->
                        title = line.substring(PLAYLIST.length).trim().ifEmpty { null } ?: title
                }
                continue
            }
            require(entries.size < MAX_ENTRIES) { "too many lines" }
            entries += M3uEntry(location = line, display = display, durationSeconds = duration)
            display = null
            duration = null
        }
        return M3uPlaylist(title, entries)
    }

    private const val EXTINF = "#EXTINF:"
    private const val PLAYLIST = "#PLAYLIST:"
    private val LEADING_NUMBER = Regex("""^-?\d+(?:\.\d+)?""")
}
