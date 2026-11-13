package tf.monochrome.android.data.import_

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.player.LocalTrackLocator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a playlist out as an `.m3u` file (the other half of
 * [M3uPlaylistImporter]).
 *
 * An M3U lists files, so a song goes in when it is a file other apps can
 * open: the file the app itself would play it from ([LocalTrackLocator], the
 * lookup every play makes, so a library song and a download saved to a folder
 * of the listener's are both found), as long as that file is in shared
 * storage ([isSharedFile]). A song that only streams has no file to list and
 * is left out, and so is a download kept in the app's own storage, which no
 * other app may read. The count of both is what the export reports.
 *
 * Paths are written whole, as the device has them. A list moved to a
 * computer still finds its songs here on the way back, by the ends of its
 * paths ([M3uLocalMatcher]).
 */
@Singleton
class M3uPlaylistExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localTrackLocator: LocalTrackLocator,
) {
    /** A playlist ready to be saved: the file's suggested name, its text, and how much of the playlist is in it. */
    class Export(val fileName: String, val text: String, val written: Int, val total: Int)

    /** [tracks], the playlist [name]'s songs in order, as an [Export]. Nothing is written yet. */
    suspend fun prepare(name: String, tracks: List<Track>): Export = withContext(Dispatchers.IO) {
        val entries = tracks.mapNotNull { track ->
            val file = localTrackLocator.findLocalSource(
                title = track.title,
                artist = track.displayArtist,
                albumTitle = track.album?.title,
                durationSeconds = track.duration,
                // As a play asks: a download is kept under the song's own id.
                catalogTrackId = track.id,
            )?.filePath
            file?.takeIf(::isSharedFile)?.let {
                M3uEntry(
                    location = it,
                    display = M3u.displayOf(track.displayArtist, track.title),
                    durationSeconds = track.duration.takeIf { seconds -> seconds > 0 },
                )
            }
        }
        Export(fileNameFor(name), M3u.write(name, entries), written = entries.size, total = tracks.size)
    }

    /** Writes [text] to the document the listener picked. False when it could not be written. */
    suspend fun save(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // "wt": a file picked over an older, longer one must not keep its tail.
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    /** Removes a document the picker made and nothing was written to. Best effort. */
    suspend fun discard(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        Unit
    }

    companion object {
        /**
         * Whether [path] is a file another app can be pointed at: a real
         * path, outside the app's own folders. A download saved through the
         * system's folder picker is a `content://` address, which means
         * nothing in a playlist; `Android/data` and `/data` are closed to
         * every app but their owner.
         */
        fun isSharedFile(path: String): Boolean =
            path.startsWith("/") &&
                !path.startsWith("/data/") &&
                "/Android/data/" !in path &&
                "/Android/obb/" !in path

        /**
         * "<playlist>.m3u", with what a file name cannot hold replaced. The
         * extension is `.m3u`, the one the file's type (`audio/x-mpegurl`)
         * goes by: with another, the system's picker adds `.m3u` after it.
         */
        fun fileNameFor(playlistName: String): String {
            val safe = playlistName
                .map { if (it.isISOControl() || it in FORBIDDEN) '_' else it }
                .joinToString("")
                .trim()
                .trim('.')
                .take(MAX_NAME)
                .trim()
            return (safe.ifEmpty { "playlist" }) + ".m3u"
        }

        /** The type the file is created with. */
        const val MIME_TYPE = "audio/x-mpegurl"

        private const val FORBIDDEN = "\\/:*?\"<>|"
        private const val MAX_NAME = 80
    }
}
