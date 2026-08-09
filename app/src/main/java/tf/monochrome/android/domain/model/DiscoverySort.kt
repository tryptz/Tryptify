package tf.monochrome.android.domain.model

/**
 * How a Discover category is ordered.
 *
 * Applied to shelves that have already been fetched rather than pushed into the
 * search: Qobuz ranks a genre query one way and one way only, so asking it for
 * "the newest drum & bass" is not a request it can answer. Sorting what came
 * back is instant, works identically for every category — a mood, a genre, the
 * personalized feed — and never costs a second round trip when the listener
 * changes their mind.
 *
 * The three are genuinely different orderings, not three names for the same
 * list. [POPULAR] is the catalogue's own ranking, which is what a search
 * returns; [NEWEST] is by release year; [FOR_YOU] floats what the listener
 * already plays to the front of every shelf.
 */
enum class DiscoverySort(val id: String, val label: String) {
    /** Artists already in the library first, then the catalogue's order. */
    FOR_YOU("for_you", "For you"),

    /** The catalogue's own ranking — how the store itself sorts a search. */
    POPULAR("popular", "Most popular"),

    /** Most recent release first; anything undated falls to the back. */
    NEWEST("newest", "Newest");

    companion object {
        val DEFAULT = FOR_YOU

        fun fromId(id: String?): DiscoverySort =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Reorder every shelf's contents, and the shelves themselves.
         *
         * [familiarArtists] must already be lower-cased; it is compared against
         * artist names rather than ids because a shelf mixes sources and only
         * some of them carry a catalogue artist id.
         *
         * Only [NEWEST] reorders the shelves themselves, and deliberately so.
         * The feed builder interleaves familiar, neighbouring and genre shelves
         * on purpose — three familiar rows followed by three genre rows reads
         * as two pages stapled together — and re-sorting by how familiar each
         * shelf is would undo exactly that. Under [FOR_YOU] the builder's
         * arrangement *is* the personalization; this only floats the artists
         * you already play to the front of each row. Sorting is stable, so
         * within a tier everything keeps the order the builder chose.
         */
        fun apply(
            shelves: List<DiscoveryShelf>,
            sort: DiscoverySort,
            familiarArtists: Set<String>,
        ): List<DiscoveryShelf> {
            if (sort == POPULAR) return shelves
            val sorted = shelves.map { shelf ->
                shelf.copy(items = order(shelf.items, sort, familiarArtists))
            }
            return when (sort) {
                POPULAR, FOR_YOU -> sorted
                // A shelf is as new as its newest record.
                NEWEST -> sorted.sortedByDescending { shelf ->
                    shelf.items.maxOfOrNull { it.releaseYear ?: 0 } ?: 0
                }
            }
        }

        private fun order(
            items: List<DiscoveryItem>,
            sort: DiscoverySort,
            familiarArtists: Set<String>,
        ): List<DiscoveryItem> = when (sort) {
            POPULAR -> items
            NEWEST -> items.sortedByDescending { it.releaseYear ?: Int.MIN_VALUE }
            FOR_YOU -> items.sortedByDescending { it.isBy(familiarArtists) }
        }
    }
}

/** Release year, where the item carries one. Tracks inherit their album's. */
val DiscoveryItem.releaseYear: Int?
    get() = when (this) {
        is DiscoveryItem.TrackItem -> track.releaseYear
        // Qobuz dates are ISO-ish ("2024-05-01"); the leading year is the only
        // part that matters here and the only part that's reliably present.
        is DiscoveryItem.AlbumItem -> album.releaseDate?.take(4)?.toIntOrNull()
        is DiscoveryItem.ArtistItem -> null
    }

/** Whether this is by an artist the listener already plays. */
fun DiscoveryItem.isBy(familiarArtists: Set<String>): Boolean {
    if (familiarArtists.isEmpty()) return false
    val names = when (this) {
        is DiscoveryItem.TrackItem ->
            (track.artistNames + track.artistName).ifEmpty { listOf(track.artistName) }
        is DiscoveryItem.AlbumItem -> album.artists.map { it.name } + listOfNotNull(album.artist?.name)
        is DiscoveryItem.ArtistItem -> listOf(artist.name)
    }
    return names.any { it.lowercase() in familiarArtists }
}
