package tf.monochrome.android.data.import_

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * What exportify.net hands the app when Export is tapped inside it, and the
 * playlists in it.
 *
 * Exportify (exportify.net) signs in to Spotify under its own registration,
 * which predates Spotify's limits on new apps, so any account can use it,
 * Free included, for private playlists and Liked Songs too, with no 100-song
 * cap. It saves through FileSaver's `saveAs(blob, name)`: one playlist as
 * `Name.csv`, "Export All" as `spotify_playlists.zip`. Inside the app that
 * `saveAs` is replaced (see ExportifyScreen) by one that posts the file here as
 * a data URL, with the name of the playlist whose Export was tapped: the file
 * name has its spaces turned into underscores.
 */
object ExportifyFiles {

    /** One saved file, as the page posts it. */
    @Serializable
    data class Message(
        val fileName: String,
        /** The playlist's own name, for a single export; null for Export All. */
        val displayName: String? = null,
        val dataUrl: String,
    )

    /** A playlist in an export: its CSV text, and the name to give it. */
    data class ExportedPlaylist(val title: String, val csv: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseMessage(text: String): Message? = runCatching { json.decodeFromString<Message>(text) }.getOrNull()

    /** The bytes in a `data:...;base64,...` URL, or null when it is not one. */
    fun bytes(dataUrl: String): ByteArray? {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return null
        if (!dataUrl.substring(0, comma).endsWith(";base64")) return null
        return runCatching { Base64.getDecoder().decode(dataUrl.substring(comma + 1)) }.getOrNull()
    }

    /**
     * The playlists in [message]: one for a CSV, one per CSV in a ZIP. Throws
     * [IllegalArgumentException] for anything else, with a message to show.
     */
    fun playlists(message: Message): List<ExportedPlaylist> {
        val data = bytes(message.dataUrl)
            ?: throw IllegalArgumentException("Exportify's file could not be read.")
        if (data.size > PlaylistCsv.MAX_BYTES * ZIP_CAP_FACTOR) {
            throw IllegalArgumentException("Exportify's file is too large to import.")
        }
        val name = message.fileName
        return when {
            name.endsWith(".csv", ignoreCase = true) -> listOf(
                ExportedPlaylist(
                    title = message.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: titleFrom(name),
                    csv = PlaylistCsv.decode(data),
                ),
            )
            name.endsWith(".zip", ignoreCase = true) -> zipPlaylists(data)
            else -> throw IllegalArgumentException("Exportify saved a file that is not a playlist: $name")
        }
    }

    /** Every CSV in an Export All ZIP, in the order Exportify wrote them. */
    private fun zipPlaylists(data: ByteArray): List<ExportedPlaylist> {
        val out = ArrayList<ExportedPlaylist>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.substringAfterLast('/')
                if (entry.isDirectory || !entryName.endsWith(".csv", ignoreCase = true)) continue
                // Bounded as it inflates. Read whole first and measured after,
                // an entry that inflates to hundreds of MB (a few hundred KB
                // of zeros does) ran the app out of memory before any check.
                // One playlist is held to one CSV's cap, as a picked file is.
                val limit = minOf(PlaylistCsv.MAX_BYTES.toLong(), ZIP_BUDGET - total)
                val bytes = readAtMost(zip, limit)
                    ?: throw IllegalArgumentException("Exportify's file is too large to import.")
                total += bytes.size
                out += ExportedPlaylist(title = titleFrom(entryName), csv = PlaylistCsv.decode(bytes))
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("Exportify's file has no playlists in it.")
        return out
    }

    /**
     * A playlist name from Exportify's file name: its extension off, and the
     * underscores it put for spaces turned back ("Road_Trip_2_.csv" ->
     * "Road Trip 2"; the trailing underscores mark a duplicate name).
     */
    fun titleFrom(fileName: String): String =
        fileName.substringBeforeLast('.')
            .trimEnd('_')
            .replace('_', ' ')
            .trim()
            .ifEmpty { "Spotify playlist" }

    /**
     * All of [input], or null once it passes [limit] bytes: checked as it is
     * read, so no more than [limit] is ever held.
     */
    internal fun readAtMost(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            read += n
            if (read > limit) return null
            out.write(buffer, 0, n)
        }
    }

    /** An Export All ZIP holds many playlists; it may be this many times one's cap. */
    private const val ZIP_CAP_FACTOR = 20

    /** What an Export All may inflate to, all its playlists together. */
    private const val ZIP_BUDGET = PlaylistCsv.MAX_BYTES.toLong() * ZIP_CAP_FACTOR
}
