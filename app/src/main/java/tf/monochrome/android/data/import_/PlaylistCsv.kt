package tf.monochrome.android.data.import_

/** Where a tabular playlist export came from, as far as its headers can tell. */
enum class CsvSource(val label: String) {
    /** Exportify and the other Spotify exporters: `Track Name`, `Artist Name(s)`. */
    SPOTIFY("Spotify"),

    /** The Music app's own export: UTF-16 tab-separated, `Name` / `Artist` / `Total Time`. */
    APPLE_MUSIC("Apple Music"),

    /** Takeout and the YouTube Music exporters: `Song Title`, `Artist Names`. */
    YOUTUBE_MUSIC("YouTube Music"),

    /** Readable, but not recognisably any of the above. */
    GENERIC("CSV"),
}

data class CsvPlaylist(
    val title: String,
    val tracks: List<CsvTrack>,
    /** What the headers looked like, for the message shown after an import. */
    val source: CsvSource = CsvSource.GENERIC,
)

data class CsvTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * Reads the playlist exports people actually have, rather than the one export
 * this app was first written against.
 *
 * The original parser assumed Exportify's Spotify CSV and nothing else: UTF-8,
 * comma-separated, and four hard-coded header names. Every other export failed,
 * and each failed *differently*, which is why the failure read as "CSV is
 * broken" rather than as an unsupported format:
 *
 *  - **Apple Music** exports UTF-16 with a byte-order mark and **tab**
 *    separators. Decoded as UTF-8 the whole file comes back as NUL-separated
 *    mojibake, so not one header matched and the file looked corrupt.
 *  - **A UTF-8 byte-order mark** — which Exportify itself writes — became part
 *    of the first header, so `track name` was really `\uFEFFtrack name` and the
 *    *title* column was the one that went missing, on the export this parser was
 *    written for.
 *  - Every other exporter (Soundiiz, TuneMyMusic, Takeout) writes the same data
 *    under different names: `Name`, `Song Title`, `Artist`, `Duration`.
 *
 * Splitting is RFC 4180 rather than `split(',')`, which matters in two places
 * that both show up in real libraries: a quoted field may contain the delimiter
 * or a newline, and a `""` inside quotes is one literal quote. The old parser
 * also dropped **every** quote character it saw, so a title like
 * `Always the Sun — Original 7" Edit` lost the inch mark; here a quote is only
 * special at the start of a field, so unquoted TSV keeps it.
 */
object PlaylistCsv {

    /**
     * The picker accepts every MIME type because Apple's export is a `.txt`, so
     * a wrong
     * pick is easy and would otherwise be read into memory in full.
     */
    const val MAX_BYTES: Int = 8 * 1024 * 1024

    /** The byte-order mark, once decoded. Invisible, and load-bearing. */
    private const val BOM = "\uFEFF"

    private val DELIMITERS = charArrayOf(',', '\t', ';', '|')

    // Ordered: the first header that matches wins, so an export carrying both
    // `Track Name` and `Name` resolves to the more specific one.
    private val TITLE_HEADERS = listOf(
        "track name", "song title", "song name", "track title", "title", "name", "song", "track",
    )
    private val ARTIST_HEADERS = listOf(
        "artist name(s)", "artist name", "artist names", "artist(s)", "artists", "artist",
        "album artist", "performer",
    )
    private val ALBUM_HEADERS = listOf(
        "album name", "album title", "album", "release",
    )
    private val DURATION_HEADERS = listOf(
        "track duration (ms)", "duration (ms)", "duration ms", "total time",
        "track duration", "duration", "length", "time",
    )

    /**
     * Bytes to text, honouring the byte-order mark the exporter wrote.
     *
     * The BOM is consumed rather than left in the string: as the first character
     * of the first header it silently breaks the match for the *title* column,
     * which is the one whose absence aborts the import.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // No mark. Latin-alphabet text in UTF-16 is half NUL bytes, and which
        // half they land in is the endianness — a cheap check that costs nothing
        // on the UTF-8 files it will usually see, and rescues the exports that
        // omit the mark.
        val probe = minOf(bytes.size, 512)
        var nulAtEven = 0
        var nulAtOdd = 0
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) if (i % 2 == 0) nulAtEven++ else nulAtOdd++
        }
        if (nulAtEven + nulAtOdd > probe / 4) {
            return String(bytes, if (nulAtOdd >= nulAtEven) Charsets.UTF_16LE else Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Which separator the header row uses. Counted outside quotes, because a
     * quoted header like `"Artist, Composer"` would otherwise vote for a comma
     * in a tab-separated file.
     */
    fun detectDelimiter(headerLine: String): Char {
        val counts = IntArray(DELIMITERS.size)
        var inQuotes = false
        for (c in headerLine) {
            if (c == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (inQuotes) continue
            val idx = DELIMITERS.indexOf(c)
            if (idx >= 0) counts[idx]++
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        // maxByOrNull keeps the first index on a tie, which is the comma.
        return if (counts[best] == 0) ',' else DELIMITERS[best]
    }

    /**
     * RFC 4180 rows: quoted fields may hold the delimiter and line breaks, and
     * `""` inside quotes is one literal quote.
     *
     * A quote only opens a field when the field is still empty. Tab-separated
     * exports do not quote anything, and their titles are full of inch marks and
     * closing quotes; treating one of those as an opening quote swallows the
     * rest of the file into a single field.
     */
    fun splitRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' ->
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                inQuotes -> field.append(c)
                c == '"' && field.isEmpty() -> inQuotes = true
                c == delimiter -> {
                    row.add(field.toString())
                    field.clear()
                }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row.add(field.toString())
        rows.add(row)
        // A trailing newline leaves one empty row, and Apple's export ends with
        // a blank line of its own.
        return rows.filter { fields -> fields.any { it.isNotBlank() } }
    }

    /**
     * A duration in whatever the exporter felt like writing.
     *
     * `3:45` and `1:02:03` are clock times. A bare number is ambiguous, and the
     * column name settles it where it can: Apple's `Total Time` is milliseconds,
     * Soundiiz's `Duration` is seconds. Where the name says nothing, anything
     * over ten thousand is milliseconds — ten thousand seconds is a
     * two-and-three-quarter-hour track, and ten thousand milliseconds is ten
     * seconds, so real tracks are never near the boundary.
     */
    fun parseDurationMs(raw: String, header: String = ""): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        if (':' in value) {
            val parts = value.split(':').map { it.trim().toDoubleOrNull() ?: return 0L }
            val seconds = when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> return 0L
            }
            return (seconds * 1000).toLong()
        }
        val number = value.replace(',', '.').toDoubleOrNull() ?: return 0L
        if (number <= 0) return 0L
        val saysMillis = "ms" in header || "millis" in header || header == "total time"
        val saysSeconds = "(s)" in header || header.endsWith(" seconds")
        return when {
            saysMillis -> number.toLong()
            saysSeconds -> (number * 1000).toLong()
            number > 10_000 -> number.toLong()
            else -> (number * 1000).toLong()
        }
    }

    /**
     * Parses a decoded export. Throws [IllegalArgumentException] with a message
     * meant to be shown as-is: an import that fails without saying which column
     * it wanted is indistinguishable from one that is simply broken.
     */
    fun parse(text: String, fallbackTitle: String): CsvPlaylist {
        // A mark can also sit mid-file when an exporter concatenates chunks.
        val cleaned = text.replace(BOM, "")
        val headerLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() }
            ?: throw IllegalArgumentException("That file is empty.")
        val rows = splitRows(cleaned, detectDelimiter(headerLine))
        if (rows.isEmpty()) throw IllegalArgumentException("That file is empty.")

        val headers = rows.first().map(::normalizeHeader)
        val titleIdx = columnFor(headers, TITLE_HEADERS)
        val artistIdx = columnFor(headers, ARTIST_HEADERS)
        if (titleIdx < 0 || artistIdx < 0) {
            throw IllegalArgumentException(missingColumnMessage(headers, titleIdx, artistIdx))
        }
        val albumIdx = columnFor(headers, ALBUM_HEADERS)
        val durationIdx = columnFor(headers, DURATION_HEADERS)

        val tracks = rows.drop(1).mapNotNull { fields ->
            val title = fields.getOrNull(titleIdx)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            CsvTrack(
                title = title,
                artist = fields.getOrNull(artistIdx)?.trim().orEmpty(),
                album = if (albumIdx >= 0) fields.getOrNull(albumIdx)?.trim().orEmpty() else "",
                durationMs = if (durationIdx >= 0) {
                    parseDurationMs(fields.getOrNull(durationIdx).orEmpty(), headers[durationIdx])
                } else {
                    0L
                },
            )
        }
        if (tracks.isEmpty()) {
            throw IllegalArgumentException(
                "Found the header row but no tracks under it — the file has columns and no songs.",
            )
        }
        return CsvPlaylist(
            title = fallbackTitle.ifBlank { "Imported Playlist" },
            tracks = tracks,
            source = detectSource(headers),
        )
    }

    /** Lower-cased, unquoted, with runs of whitespace collapsed. */
    private fun normalizeHeader(raw: String): String =
        raw.replace(BOM, "")
            .trim()
            .removeSurrounding("\"")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Matched whole, never as a substring: `Album Name` contains "name", and a
     * substring match would hand the album column back as the track title.
     */
    private fun columnFor(headers: List<String>, candidates: List<String>): Int {
        candidates.forEach { candidate ->
            val idx = headers.indexOf(candidate)
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun detectSource(headers: List<String>): CsvSource = when {
        headers.any { it in SPOTIFY_MARKERS } -> CsvSource.SPOTIFY
        headers.any { it in APPLE_MARKERS } -> CsvSource.APPLE_MUSIC
        headers.any { it in YOUTUBE_MARKERS } -> CsvSource.YOUTUBE_MUSIC
        else -> CsvSource.GENERIC
    }

    private val SPOTIFY_MARKERS = setOf("track uri", "spotify track id", "artist name(s)", "added at")
    private val APPLE_MARKERS = setOf("persistent id", "total time", "track identifier", "apple music")
    private val YOUTUBE_MARKERS = setOf("video id", "song title", "artist names")

    private fun missingColumnMessage(headers: List<String>, titleIdx: Int, artistIdx: Int): String {
        val missing = buildList {
            if (titleIdx < 0) add("a track title")
            if (artistIdx < 0) add("an artist")
        }.joinToString(" and ")
        val found = headers.filter { it.isNotBlank() }
        val seen = if (found.isEmpty()) {
            "That file has no header row."
        } else {
            "Its columns are: ${found.joinToString(", ")}."
        }
        return "Couldn't find $missing column in that file. $seen " +
            "Export from Spotify with Exportify, or from Apple Music with File › Library › Export Playlist."
    }
}
