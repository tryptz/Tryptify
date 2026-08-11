package tf.monochrome.android.domain.model

/**
 * One card in a discovery shelf.
 *
 * Shelves are deliberately allowed to mix — an "artists like X" shelf shows
 * artists, a "new releases" shelf shows albums, a mix shows tracks. The old
 * feed could only show tracks, which forced every recommendation through the
 * same shape whether or not that was the thing being recommended.
 */
sealed interface DiscoveryItem {
    val key: String

    data class TrackItem(val track: UnifiedTrack) : DiscoveryItem {
        override val key get() = "t:" + track.id
    }

    data class AlbumItem(val album: Album) : DiscoveryItem {
        override val key get() = "al:" + album.id
    }

    data class ArtistItem(val artist: Artist) : DiscoveryItem {
        override val key get() = "ar:" + artist.id
    }
}

/**
 * A titled row in the discovery feed.
 *
 * [reason] is the point of this type. A recommendation the listener can't
 * account for reads as noise — "Because you play Aphex Twin" and "New this week
 * from artists in your library" are the same list of tracks with and without a
 * reason to trust it, and the difference is most of what makes a discovery page
 * feel curated rather than dumped.
 *
 * [id] is stable across rebuilds of the feed so it can key a lazy list and
 * address a See All screen.
 */
data class DiscoveryShelf(
    val id: String,
    val title: String,
    val reason: String? = null,
    val items: List<DiscoveryItem> = emptyList(),
    /** Whether the header offers a "See All" into the full grid. */
    val seeAll: Boolean = true,
    /**
     * The genre this shelf was built from, when it was built from one.
     *
     * Carried so a See All grid can ask for more of the same genre without
     * having to parse it back out of [id]. Null for shelves with no genre
     * behind them — "New from X", "Because you play X", a mood's flat search.
     */
    val genreId: String? = null,
    /**
     * How deep into [genreId] this shelf already reaches. The next page is
     * [depth] + 1; see DiscoveryFeedUseCase.moreForGenre.
     */
    val depth: Int = 0,
)
