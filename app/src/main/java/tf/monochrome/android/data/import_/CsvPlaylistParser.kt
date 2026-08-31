package tf.monochrome.android.data.import_

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a picked playlist export off disk and hands it to [PlaylistCsv].
 *
 * Everything that can be decided without Android lives in [PlaylistCsv] so it
 * can be tested against real exports; this class is the part that needs a
 * `ContentResolver` — the bytes, and the display name the playlist falls back
 * to when the file carries no title of its own.
 */
@Singleton
class CsvPlaylistParser @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The failure is carried, not flattened. A caller that turns this into
     * "could not parse the file" throws away the one sentence that tells the
     * listener which column was missing or which export to take instead.
     */
    suspend fun parseFromUri(uri: Uri): Result<CsvPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readBytes(uri)
            val name = displayName(uri)?.substringBeforeLast('.').orEmpty()
            PlaylistCsv.parse(PlaylistCsv.decode(bytes), fallbackTitle = name)
        }
    }

    /**
     * The picker has to accept every MIME type — Apple Music exports a `.txt`,
     * and some exporters send `application/octet-stream` — so the file may be
     * anything at all, including something far too large to hold in memory.
     * Reading stops
     * the moment the cap is passed and says so, rather than truncating into a
     * file that would parse cleanly as half a playlist.
     */
    private fun readBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("That file could not be opened.")
        return stream.use { input ->
            // Read by hand rather than with readNBytes, which needs API 33 while
            // this app ships to 26.
            val buffered = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var overCap = false
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffered.write(chunk, 0, read)
                if (buffered.size() > PlaylistCsv.MAX_BYTES) {
                    overCap = true
                    break
                }
            }
            val bytes = buffered.toByteArray()
            if (overCap) {
                throw IllegalArgumentException(
                    "That file is larger than ${PlaylistCsv.MAX_BYTES / (1024 * 1024)} MB — " +
                        "it is probably not a playlist export.",
                )
            }
            if (bytes.isEmpty()) throw IllegalArgumentException("That file is empty.")
            bytes
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) return cursor.getString(index)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }
}
