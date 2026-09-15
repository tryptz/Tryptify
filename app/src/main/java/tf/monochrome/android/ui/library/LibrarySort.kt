package tf.monochrome.android.ui.library

import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * The dimensions the local-library tabs can be sorted by. Not every key applies
 * to every tab — each tab exposes the subset that makes sense for its data (see
 * [SONG_SORT_KEYS] / [ALBUM_SORT_KEYS] / [ARTIST_SORT_KEYS]).
 */
enum class LibrarySortKey(val label: String) {
    NAME("Name"),
    DATE("Date"),
    FILE_TYPE("File type"),
    TIME("Time"),
    TRACKS("Tracks"),
    ALBUMS("Albums"),
}

/** A sort selection: which [key] and the direction. */
data class LibrarySort(val key: LibrarySortKey, val ascending: Boolean = true)

// Songs and albums carry a real date, file type and duration, so they offer the
// full Date / Name / File type / Time palette. Artists store none of those — only
// a name and roll-up counts — so they sort by Name / Tracks / Albums instead.
val SONG_SORT_KEYS = listOf(
    LibrarySortKey.NAME, LibrarySortKey.DATE, LibrarySortKey.FILE_TYPE, LibrarySortKey.TIME,
)
val ALBUM_SORT_KEYS = listOf(
    LibrarySortKey.NAME, LibrarySortKey.DATE, LibrarySortKey.FILE_TYPE, LibrarySortKey.TIME,
)
val ARTIST_SORT_KEYS = listOf(
    LibrarySortKey.NAME, LibrarySortKey.TRACKS, LibrarySortKey.ALBUMS,
)

/**
 * One element decorated with the text keys it sorts by, computed once.
 *
 * `compareBy { it.title.lowercase() }` looks harmless and is not: the selector
 * runs on every *comparison*, so a library sort allocated a fresh lowercase
 * String O(n log n) times. Decorating costs one String per element instead.
 */
private class TextKeyed<T>(val value: T, val first: String, val second: String)

/**
 * Sort by text keys computed once per element.
 *
 * Stable, like the `sortedWith` it replaces — `ArrayList.sortWith` is a TimSort
 * — so elements the keys tie on keep the order they arrived in. Callers that
 * want descending reverse the RESULT rather than the comparator, which is not
 * the same thing and is why this stays stable: reversing the list flips ties as
 * well, reversing the comparator leaves them alone, and the first is what the
 * library has always shown.
 *
 * [second] defaults to a constant, which ties for every element and so leaves
 * the single-key case ordered purely by [first] and input order.
 */
private inline fun <T> List<T>.sortedByText(
    crossinline first: (T) -> String,
    crossinline second: (T) -> String = { "" },
): List<T> {
    val slots = ArrayList<TextKeyed<T>>(size)
    for (element in this) slots.add(TextKeyed(element, first(element), second(element)))
    slots.sortWith(compareBy({ it.first }, { it.second }))
    val sorted = ArrayList<T>(size)
    for (slot in slots) sorted.add(slot.value)
    return sorted
}

@JvmName("applySortTracks")
fun List<UnifiedTrack>.applySort(sort: LibrarySort): List<UnifiedTrack> {
    val sorted = when (sort.key) {
        LibrarySortKey.DATE -> sortedWith(compareBy { it.dateModified ?: 0L })
        LibrarySortKey.FILE_TYPE -> sortedByText({ it.codec?.name ?: "￿" }, { it.title.lowercase() })
        LibrarySortKey.TIME -> sortedWith(compareBy { it.durationSeconds })
        else -> sortedByText(first = { it.title.lowercase() })
    }
    return if (sort.ascending) sorted else sorted.reversed()
}

@JvmName("applySortAlbums")
fun List<UnifiedAlbum>.applySort(sort: LibrarySort): List<UnifiedAlbum> {
    val sorted = when (sort.key) {
        LibrarySortKey.DATE -> sortedWith(compareBy { it.year ?: 0 })
        LibrarySortKey.FILE_TYPE ->
            sortedByText({ it.qualitySummary ?: "￿" }, { it.title.lowercase() })
        LibrarySortKey.TIME -> sortedWith(compareBy { it.totalDuration })
        else -> sortedByText(first = { it.title.lowercase() })
    }
    return if (sort.ascending) sorted else sorted.reversed()
}

@JvmName("applySortArtists")
fun List<UnifiedArtist>.applySort(sort: LibrarySort): List<UnifiedArtist> {
    val sorted = when (sort.key) {
        LibrarySortKey.TRACKS -> sortedWith(compareBy { it.trackCount })
        LibrarySortKey.ALBUMS -> sortedWith(compareBy { it.albumCount })
        else -> sortedByText(first = { it.name.lowercase() })
    }
    return if (sort.ascending) sorted else sorted.reversed()
}
