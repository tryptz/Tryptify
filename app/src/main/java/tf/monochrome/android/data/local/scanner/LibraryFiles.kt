package tf.monochrome.android.data.local.scanner

import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

/**
 * The files a scan reads: where the library's music is, said once.
 *
 * With folders picked, the folders themselves are read ([FolderWalker]):
 * what is in them is the library, whether or not Android has indexed it yet.
 * Android's index is still asked about those folders and added in, because
 * on some versions an app can open a file it is not shown when it lists the
 * folder; a row of its for a file that is gone is left out.
 *
 * With no folder picked the library is "the music on this phone", and that
 * is a question only Android's index can answer.
 */
@Singleton
class LibraryFiles @Inject constructor(
    private val mediaStoreSource: MediaStoreSource,
) {
    class Listing(
        val files: List<AudioFileInfo>,
        /** Found by reading the folders. */
        val walked: Int,
        /** Of those, the ones Android's index does not have. */
        val unindexed: List<String>,
    )

    fun list(roots: Set<String>, excluded: Set<String>, includeAtmosVideos: Boolean): Listing {
        if (roots.isEmpty()) {
            return Listing(mediaStoreSource.queryAllAudio(excluded, roots, includeAtmosVideos), 0, emptyList())
        }
        val hidden = ArrayList<String>()
        val walked = roots.flatMap { FolderWalker.walk(File(it), excluded, hidden) }
        if (hidden.isNotEmpty()) {
            android.util.Log.w(TAG, "${hidden.size} folder(s) under the library hold a .nomedia file and are left out: ${hidden.take(5)}")
        }
        val indexed = mediaStoreSource.queryAllAudio(excluded, roots, includeAtmosVideos = false, musicOnly = false)
        val files = merge(walked, indexed) { File(it).exists() }
        val known = indexed.mapTo(HashSet()) { it.absolutePath }
        val videos = if (includeAtmosVideos) mediaStoreSource.queryAtmosVideos(excluded) else emptyList()
        val seen = files.mapTo(HashSet()) { it.absolutePath }
        return Listing(
            files = files + videos.filter { it.absolutePath !in seen },
            walked = walked.size,
            unindexed = walked.map { it.path }.filterNot { it in known },
        )
    }

    companion object {
        private const val TAG = "LibraryFiles"

        /**
         * [walked] and [indexed] as one list, a file once. A walked file is
         * taken as walked: its size and date were read from the file just
         * now, where the index's are from whenever it last looked. A file
         * only the index knows is kept while it [exists].
         */
        internal fun merge(
            walked: List<WalkedFile>,
            indexed: List<AudioFileInfo>,
            exists: (String) -> Boolean,
        ): List<AudioFileInfo> {
            val out = LinkedHashMap<String, AudioFileInfo>(walked.size + 16)
            for (file in walked) {
                out[file.path] = AudioFileInfo(
                    absolutePath = file.path,
                    displayName = file.path.substringAfterLast('/'),
                    mimeType = "",
                    sizeBytes = file.sizeBytes,
                    dateModified = file.modifiedMs,
                    duration = 0,
                    uri = null,
                )
            }
            for (file in indexed) {
                if (file.absolutePath in out) continue
                if (exists(file.absolutePath)) out[file.absolutePath] = file
            }
            return out.values.toList()
        }
    }
}
