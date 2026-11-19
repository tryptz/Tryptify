package tf.monochrome.android.data.local.scanner

import java.io.File

/** An audio file a walk found: where it is, how big, and when it last changed (to the second, in ms). */
data class WalkedFile(val path: String, val sizeBytes: Long, val modifiedMs: Long)

/** What one look at a path says. [key] names the thing itself, so a link back up the tree is seen as one. */
internal class FileEntry(val isDirectory: Boolean, val size: Long, val modifiedMs: Long, val key: String?)

/**
 * One system call a path. `java.io.File` asks three times to learn whether a
 * path is a folder, how big it is and when it changed, and every one of those
 * is a round trip through Android's storage layer; `Os.stat` answers all
 * three at once.
 */
internal object FileStats {
    @Volatile private var useOs = true

    fun stat(path: String): FileEntry? {
        if (useOs) {
            try {
                val s = android.system.Os.stat(path)
                return FileEntry(
                    isDirectory = android.system.OsConstants.S_ISDIR(s.st_mode),
                    size = s.st_size,
                    // Seconds, as Android's own index keeps a file's date:
                    // the two must agree, or every file looks changed.
                    modifiedMs = s.st_mtime * 1000,
                    key = "${s.st_dev}:${s.st_ino}",
                )
            } catch (_: android.system.ErrnoException) {
                return null
            } catch (_: Throwable) {
                // Not on a phone (a unit test): plain java.io from here on.
                useOs = false
            }
        }
        val file = File(path)
        if (!file.exists()) return null
        val directory = file.isDirectory
        return FileEntry(
            isDirectory = directory,
            size = file.length(),
            modifiedMs = file.lastModified() / 1000 * 1000,
            key = if (directory) runCatching { file.canonicalPath }.getOrNull() else null,
        )
    }
}

/**
 * Reads the library's folders itself, instead of asking Android what it has
 * indexed in them.
 *
 * Android's index decides what is "music" by its own rules and in its own
 * time: a file copied in a way that tells it nothing stays unknown for any
 * number of rescans, a file under Ringtones or Podcasts is not music to it,
 * and its rows outlive deleted files. A folder the listener picked holds what
 * it holds; this lists that, with each file's size and date in the same pass.
 */
object FolderWalker {
    /** Deep enough for any real library, and a stop for a link that loops. */
    private const val MAX_DEPTH = 32

    /**
     * The audio files under [root], by extension ([FolderIndexer.AUDIO_EXTENSIONS]).
     *
     * Not walked: [excluded] folders; hidden folders and files (a leading
     * dot: macOS leaves a "._Song.mp3" beside every song it copies); and a
     * folder below the root holding a `.nomedia` file, with everything under
     * it, recorded in [nomedia]: that is how an app says its sounds are not
     * a music collection (voice messages, game audio). The root itself is
     * walked whatever it holds, since the listener picked it by hand.
     *
     * A folder that cannot be read is passed over, which also makes a root
     * the app cannot list an empty result rather than an error.
     */
    fun walk(root: File, excluded: Set<String>, nomedia: MutableList<String>? = null): List<WalkedFile> {
        val files = ArrayList<WalkedFile>()
        val rootPath = root.absolutePath.trimEnd('/').ifEmpty { "/" }
        val rootEntry = FileStats.stat(rootPath) ?: return files
        if (!rootEntry.isDirectory) return files
        val visited = HashSet<String>()
        visited += rootEntry.key ?: rootPath
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(rootPath to 0)
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            if (depth > MAX_DEPTH) continue
            if (MediaStoreSource.isExcluded(dir, excluded)) continue
            val names = File(dir).list() ?: continue
            if (depth > 0 && names.any { it == ".nomedia" }) {
                nomedia?.add(dir)
                continue
            }
            for (name in names) {
                if (name.startsWith(".")) continue
                val path = "$dir/$name"
                val entry = FileStats.stat(path) ?: continue
                if (entry.isDirectory) {
                    // A link back up the tree would otherwise be walked forever.
                    if (visited.add(entry.key ?: path)) pending.addLast(path to depth + 1)
                } else if (name.substringAfterLast('.', "").lowercase() in FolderIndexer.AUDIO_EXTENSIONS &&
                    !MediaStoreSource.isExcluded(path, excluded)
                ) {
                    files += WalkedFile(path, entry.size, entry.modifiedMs)
                }
            }
        }
        return files
    }
}
