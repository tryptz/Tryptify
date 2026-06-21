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

@JvmName("applySortTracks")
fun List<UnifiedTrack>.applySort(sort: LibrarySort): List<UnifiedTrack> {
    val comparator: Comparator<UnifiedTrack> = when (sort.key) {
        LibrarySortKey.DATE -> compareBy { it.dateModified ?: 0L }
        LibrarySortKey.FILE_TYPE -> compareBy(
            { it.codec?.name ?: "￿" }, { it.title.lowercase() },
        )
        LibrarySortKey.TIME -> compareBy { it.durationSeconds }
        else -> compareBy { it.title.lowercase() }
    }
    val sorted = sortedWith(comparator)
    return if (sort.ascending) sorted else sorted.reversed()
}

@JvmName("applySortAlbums")
fun List<UnifiedAlbum>.applySort(sort: LibrarySort): List<UnifiedAlbum> {
    val comparator: Comparator<UnifiedAlbum> = when (sort.key) {
        LibrarySortKey.DATE -> compareBy { it.year ?: 0 }
        LibrarySortKey.FILE_TYPE -> compareBy(
            { it.qualitySummary ?: "￿" }, { it.title.lowercase() },
        )
        LibrarySortKey.TIME -> compareBy { it.totalDuration }
        else -> compareBy { it.title.lowercase() }
    }
    val sorted = sortedWith(comparator)
    return if (sort.ascending) sorted else sorted.reversed()
}

@JvmName("applySortArtists")
fun List<UnifiedArtist>.applySort(sort: LibrarySort): List<UnifiedArtist> {
    val comparator: Comparator<UnifiedArtist> = when (sort.key) {
        LibrarySortKey.TRACKS -> compareBy { it.trackCount }
        LibrarySortKey.ALBUMS -> compareBy { it.albumCount }
        else -> compareBy { it.name.lowercase() }
    }
    val sorted = sortedWith(comparator)
    return if (sort.ascending) sorted else sorted.reversed()
}
