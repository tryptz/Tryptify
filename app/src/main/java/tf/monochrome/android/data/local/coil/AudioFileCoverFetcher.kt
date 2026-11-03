package tf.monochrome.android.data.local.coil

import android.content.Context
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import java.io.File

/**
 * Coil 3 fetcher that pulls embedded album art directly from an audio file
 * via MediaMetadataRetriever. Activates when the model is a file:// URI
 * pointing at one of the [AUDIO_EXTENSIONS] formats — anything else (cached
 * JPG/PNG/WebP, network URLs, content:// images) falls through to Coil's
 * built-in fetchers.
 *
 * Why this exists: the local-media scanner caches embedded art at scan time,
 * but cache files get evicted, scans miss freshly-downloaded tracks until
 * MediaScanner runs, and some files keep their art only in container atoms
 * that the cache layer hasn't been taught to read. Pointing the player's
 * artwork URI at the audio file itself (and letting this fetcher pull the
 * picture on demand) means the cover is shown whenever the file has one,
 * regardless of cache state.
 */
class AudioFileCoverFetcher(
    private val filePath: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = File(filePath)
        if (!file.exists()) return null
        // A file with no embedded picture costs the same native open as one
        // with a cover, returns nothing, and is asked again the next time the
        // row scrolls back — Coil caches images, not the absence of one. A
        // device log shows 22 of these in five seconds from a handful of
        // art-less tracks being recycled through a list.
        val stamp = file.lastModified()
        if (NoEmbeddedArt.known(filePath, stamp)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val bytes = retriever.embeddedPicture
                ?: return null.also { NoEmbeddedArt.remember(filePath, stamp) }
            // The picture's bytes, not a bitmap: Coil's decoder reads the size
            // from the header and decodes straight to the size asked for, its
            // aspect kept, as a hardware bitmap where it can. Decoded here at
            // full size first, a 3000-pixel cover was 36 MB on the heap for a
            // 200dp thumbnail, then squeezed to the requested box.
            SourceFetchResult(
                source = ImageSource(Buffer().write(bytes), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory(@Suppress("unused") private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "file") return null
            val path = data.path ?: return null
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXTENSIONS) return null
            return AudioFileCoverFetcher(path, options)
        }
    }

    /**
     * Paths whose audio holds no embedded picture, so the decoder is not opened
     * for them twice.
     *
     * Keyed on the file's modification time as well as its path, so retagging a
     * track puts its cover back without anything having to clear this.
     *
     * Deliberately forgetful and small: it is an optimisation for rows going
     * past on screen, and a bounded LRU cannot grow into a leak on a library of
     * any size. Missing an entry costs one wasted open, which is what happened
     * every time before.
     *
     * Only a definite "opened, nothing inside" is remembered. A failure to open
     * might be a file still being written or a transient read error, and
     * writing that off permanently would hide a cover that does exist.
     *
     * `local_tracks` already carries `hasEmbeddedArt` from the scanner, which is
     * the same fact recorded properly; consulting it from here would mean a
     * database hit per fetch and an entry point into Hilt from a Coil
     * component, so this stays in memory.
     */
    private object NoEmbeddedArt {
        private const val MAX_ENTRIES = 512

        private val seen = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) =
                    size > MAX_ENTRIES
            }
        )

        fun known(path: String, stamp: Long): Boolean = seen[path] == stamp

        fun remember(path: String, stamp: Long) {
            seen[path] = stamp
        }
    }

    internal companion object {
        /**
         * Extensions this fetcher claims. Also read by StreamResolver, which
         * has to recognise the same "artwork URI is really an audio file"
         * case in order NOT to hand it to Media3 — see the note there.
         */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus",
            "wav", "wma", "aif", "aiff", "ape", "dsf", "dff",
        )
    }
}
