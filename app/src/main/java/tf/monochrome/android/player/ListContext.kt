package tf.monochrome.android.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which list a queue was played from, so that when it runs out playback can
 * carry on into the list beside it (Settings › Audio › Playback, "Continue
 * into the next list").
 *
 * Pure Kotlin, like [PlaybackSnapshotCodec]: it is saved with the queue and
 * every rule here is pinned by `ListContextTest`.
 *
 * Only ids are held, never tracks. A list's songs are loaded when playback
 * reaches it, one list at a time.
 */
@Serializable
sealed interface ListRef {
    /** One of the listener's own playlists. */
    @Serializable
    @SerialName("playlist")
    data class Playlist(val id: String) : ListRef

    /** A catalogue album. */
    @Serializable
    @SerialName("album")
    data class Album(val id: Long) : ListRef

    /** An album of the on-device library. */
    @Serializable
    @SerialName("local_album")
    data class LocalAlbum(val id: Long) : ListRef

    /**
     * A phone folder: its own songs, or with [withSubfolders] (its Play
     * button) everything under it, so the next folder is the one after that
     * whole branch.
     */
    @Serializable
    @SerialName("folder")
    data class Folder(val path: String, val withSubfolders: Boolean = false) : ListRef

    /** A playlist on the listener's music server (Subsonic or Tryptify Cloud). */
    @Serializable
    @SerialName("subsonic_playlist")
    data class SubsonicPlaylist(val id: String) : ListRef

    /** An album on the listener's music server. */
    @Serializable
    @SerialName("subsonic_album")
    data class SubsonicAlbum(val id: String) : ListRef
}

/**
 * The lists the queue came from.
 *
 * [row] is the lists side by side on the screen the listener opened it from
 * (an artist's albums, the Library's playlists), in the order shown, without
 * repeats: a list shown twice would otherwise walk back to its first copy and
 * go round for ever. It is empty for folders, which walk the whole folder tree
 * instead ([FolderWalk]).
 *
 * [head] is the list at the front of the queue and [tail] the one at its end;
 * they differ only while the next list has been added behind the playing one.
 *
 * [headCut] means the queue no longer starts where [head] does (a long queue
 * is saved only around the play head, see `QueueWindow`), so Previous on its
 * first track restarts it rather than jumping to the list before.
 */
@Serializable
data class ListContext(
    val row: List<ListRef> = emptyList(),
    val head: ListRef,
    val tail: ListRef = head,
    val headCut: Boolean = false,
) {
    /** The list after [list] in [row], or null at its end (and for folders). */
    fun after(list: ListRef = tail): ListRef? = row.indexOf(list).takeIf { it >= 0 }?.let { row.getOrNull(it + 1) }

    /** The list before [list] in [row], or null at its start (and for folders). */
    fun before(list: ListRef = head): ListRef? = row.indexOf(list).takeIf { it > 0 }?.let { row[it - 1] }

    companion object {
        /**
         * A row is kept to this many lists either side of the one played, so a
         * Library of thousands of albums costs a few kilobytes in the saved
         * queue, not a few hundred.
         */
        const val MAX_EITHER_SIDE = 100

        /**
         * The context for playing [list], opened from a screen showing [row].
         * A [row] that does not hold [list] (the list was opened some other
         * way) leaves it on its own.
         */
        fun of(list: ListRef, row: List<ListRef>): ListContext {
            val distinct = row.distinct()
            val at = distinct.indexOf(list)
            if (list is ListRef.Folder || at < 0) return ListContext(head = list)
            val from = (at - MAX_EITHER_SIDE).coerceAtLeast(0)
            val until = (at + MAX_EITHER_SIDE + 1).coerceAtMost(distinct.size)
            return ListContext(row = distinct.subList(from, until).toList(), head = list)
        }
    }
}

/** The folder tree as the folder browser shows it, asked one folder at a time. */
interface FolderView {
    /** The Folders list, in its order. */
    suspend fun roots(): List<String>

    /** The folders directly inside [path], in the folder sort. */
    suspend fun children(path: String): List<String>

    /** Whether [path] holds songs of its own, not only in its subfolders. */
    suspend fun hasOwnSongs(path: String): Boolean
}

/**
 * The phone folders in the order "Continue into the next list" walks them:
 * the whole tree, top to bottom, each folder before its subfolders, as the
 * folder browser shows them. Only folders with songs of their own count; the
 * rest are passed through.
 *
 * Only the neighbour is looked for, never the whole order: a library of
 * thousands of folders is asked about the few around the one playing.
 */
object FolderWalk {
    /**
     * The folder after [path], or null when it is the last. With
     * [skipBelow] the folders inside [path] are passed over: they have
     * already played.
     */
    suspend fun after(view: FolderView, path: String, skipBelow: Boolean = false): String? {
        if (!skipBelow) {
            for (child in view.children(path)) firstAtOrBelow(view, child)?.let { return it }
        }
        val roots = view.roots()
        var at = path
        while (true) {
            val siblings = if (at in roots) roots else view.children(parentOf(at) ?: return null)
            val from = siblings.indexOf(at)
            if (from < 0) return null
            for (sibling in siblings.drop(from + 1)) firstAtOrBelow(view, sibling)?.let { return it }
            if (at in roots) return null
            at = parentOf(at) ?: return null
        }
    }

    /** The folder before [path], or null when it is the first. */
    suspend fun before(view: FolderView, path: String): String? {
        val roots = view.roots()
        var at = path
        while (true) {
            val siblings = if (at in roots) roots else view.children(parentOf(at) ?: return null)
            val from = siblings.indexOf(at)
            if (from < 0) return null
            for (sibling in siblings.take(from).asReversed()) lastAtOrBelow(view, sibling)?.let { return it }
            if (at in roots) return null
            val parent = parentOf(at) ?: return null
            if (view.hasOwnSongs(parent)) return parent
            at = parent
        }
    }

    private suspend fun firstAtOrBelow(view: FolderView, path: String): String? {
        if (view.hasOwnSongs(path)) return path
        for (child in view.children(path)) firstAtOrBelow(view, child)?.let { return it }
        return null
    }

    private suspend fun lastAtOrBelow(view: FolderView, path: String): String? {
        for (child in view.children(path).asReversed()) lastAtOrBelow(view, child)?.let { return it }
        return if (view.hasOwnSongs(path)) path else null
    }

    private fun parentOf(path: String): String? =
        path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { null }
}

/**
 * The rows of lists the listener opened lists from, so a list knows what is
 * beside it on the screen it came from: an artist's albums, the Library's
 * playlists, a shelf. In memory only, the last [KEEP] rows, each kept to a
 * window around the list opened ([ListContext.of]).
 */
object ListTrail {
    private const val KEEP = 8

    private val rows = object : LinkedHashMap<ListRef, List<ListRef>>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ListRef, List<ListRef>>?) = size > KEEP
    }

    /** [item] was opened from a screen showing [row]; [ref] is null for what is not a list. */
    fun <T> opened(item: T, row: List<T>, ref: (T) -> ListRef?) {
        val list = ref(item) ?: return
        val context = ListContext.of(list, row.mapNotNull(ref))
        synchronized(rows) { rows[list] = context.row }
    }

    /** A local album's id, from its `local_album_<n>` key. */
    fun localAlbum(key: String): ListRef? = key.removePrefix("local_album_").toLongOrNull()?.let(ListRef::LocalAlbum)

    /** The lists [list] is played among: its row, when it was opened from one. */
    fun contextFor(list: ListRef): ListContext =
        ListContext.of(list, synchronized(rows) { rows[list] }.orEmpty())
}
