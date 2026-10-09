package tf.monochrome.android.data.local.scanner

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Has Android index the library folders before a scan reads them.
 *
 * The library is read from MediaStore, never from the folders themselves
 * ([MediaStoreSource]): it is how a file's tags and its playable URI are
 * reached. So a file Android has not indexed is invisible to every scan, and
 * no number of rescans or re-added folders will find it (issue #136: "refuses
 * to read a new file that I just added"). Android indexes most new files by
 * itself, but not every way of putting one there tells it.
 *
 * This walks the user's folders, finds the audio files MediaStore has no row
 * for, and asks Android to index them (MediaScannerConnection), waiting until
 * it has, so the scan that follows sees them.
 */
@Singleton
class FolderIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreSource: MediaStoreSource,
) {
    /**
     * The audio files under [roots] that Android has not indexed, without
     * [excluded] folders. Empty for an empty [roots]: that is the whole-device
     * library, which is MediaStore's own view and has nothing to walk.
     */
    suspend fun findUnindexed(roots: Set<String>, excluded: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (roots.isEmpty()) return@withContext emptyList()
            val hidden = mutableListOf<String>()
            // Only what Android itself takes for audio: it files anything else
            // outside its audio index, so asking would change nothing, and
            // would be asked again on every scan.
            val mimes = MimeTypeMap.getSingleton()
            val onDisk = roots.flatMap { walkAudioFiles(File(it), excluded, hidden) }
                .filter { mimes.getMimeTypeFromExtension(File(it).extension.lowercase())?.startsWith("audio/") == true }
            if (hidden.isNotEmpty()) {
                // Said once per scan, so a debug log explains songs that never
                // show up: Android does not list these as music, asked or not.
                Log.w(TAG, "${hidden.size} folder(s) under the library hold a .nomedia file and are not indexed: ${hidden.take(5)}")
            }
            if (onDisk.isEmpty()) return@withContext emptyList()
            val indexed = mediaStoreSource.queryIndexedAudioPaths(roots)
            onDisk.filterNot { it in indexed }
        }

    /**
     * Asks Android to index [paths] and waits until it has, or until
     * [timeoutMs] runs out. Whatever is not indexed by then is still on its
     * way, and LibraryWatcher picks it up when it lands.
     */
    suspend fun index(paths: List<String>, timeoutMs: Long = timeoutFor(paths.size)) {
        if (paths.isEmpty()) return
        val remaining = AtomicInteger(paths.size)
        val done = CompletableDeferred<Unit>()
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
            if (remaining.decrementAndGet() == 0) done.complete(Unit)
        }
        if (withTimeoutOrNull(timeoutMs) { done.await() } == null) {
            Log.w(TAG, "Android indexed ${paths.size - remaining.get()} of ${paths.size} files in ${timeoutMs / 1000} s; the rest follow")
        } else {
            Log.i(TAG, "Android indexed ${paths.size} file(s) the library folders held that it had not")
        }
    }

    companion object {
        private const val TAG = "FolderIndexer"

        /** Deep enough for any real library, and a stop for a link that loops. */
        private const val MAX_DEPTH = 32

        /** 15 s, plus a quarter of a second a file, at most 3 minutes. */
        internal fun timeoutFor(files: Int): Long = (15_000L + files * 250L).coerceAtMost(180_000L)

        /**
         * Extensions of the audio a library folder holds. By name, because
         * nothing here has read the files yet. A video container is not in it:
         * MediaStore files those as video, so they would look unindexed on
         * every scan.
         */
        internal val AUDIO_EXTENSIONS = setOf(
            // Lossy
            "mp3", "aac", "m4a", "m4b", "m4p",
            "ogg", "oga", "opus",
            "wma", "mpc", "mp2", "mp1",
            // Lossless
            "flac", "alac", "wav", "wave", "w64",
            "aif", "aiff", "aifc",
            "ape", "tak", "wv", "tta", "shn",
            // High-res / DSD / niche
            "dsf", "dff", "dsd",
            // Audio-only containers
            "mka", "caf",
            // Misc
            "au", "amr", "ra", "rm",
        )

        /**
         * The audio files under [root], by extension, as absolute paths in the
         * form MediaStore keeps them.
         *
         * Not walked: [excluded] folders; hidden folders (a leading dot); and a
         * folder holding a `.nomedia` file, with everything under it, recorded
         * in [nomedia]. Android does not index those as music, so asking it to
         * would change nothing, every scan. A folder that cannot be read is
         * passed over, which also makes a root the app cannot list an empty
         * result rather than an error.
         */
        internal fun walkAudioFiles(
            root: File,
            excluded: Set<String>,
            nomedia: MutableList<String>? = null,
        ): List<String> {
            val files = mutableListOf<String>()
            val visited = HashSet<String>()
            val pending = ArrayDeque<Pair<File, Int>>()
            pending.addLast(root to 0)
            while (pending.isNotEmpty()) {
                val (dir, depth) = pending.removeFirst()
                if (depth > MAX_DEPTH) continue
                if (MediaStoreSource.isExcluded(dir.absolutePath, excluded)) continue
                // A symlink back up the tree would otherwise be walked forever.
                val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
                if (!visited.add(canonical)) continue
                val children = dir.listFiles() ?: continue
                if (children.any { it.name == ".nomedia" }) {
                    nomedia?.add(dir.absolutePath)
                    continue
                }
                for (child in children) {
                    if (child.isDirectory) {
                        if (!child.name.startsWith(".")) pending.addLast(child to depth + 1)
                    } else if (child.extension.lowercase() in AUDIO_EXTENSIONS &&
                        !MediaStoreSource.isExcluded(child.absolutePath, excluded)
                    ) {
                        files += child.absolutePath
                    }
                }
            }
            return files
        }
    }
}
