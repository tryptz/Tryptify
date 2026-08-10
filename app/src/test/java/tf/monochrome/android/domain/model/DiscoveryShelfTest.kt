package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Lazy-list keys for discovery cards.
 *
 * A shelf can hold tracks, albums and artists, and their ids come from three
 * different namespaces that freely overlap — album 1234 and artist 1234 are
 * both ordinary. Keying a `LazyRow` on the bare id would let two different
 * cards claim the same key, which Compose treats as a duplicate and crashes on.
 * The type prefix is what stops that, so it is tested rather than assumed.
 */
class DiscoveryShelfTest {

    private fun track(id: String) = DiscoveryItem.TrackItem(
        UnifiedTrack(
            id = id,
            title = "Track $id",
            durationSeconds = 180,
            artistName = "Artist",
            source = PlaybackSource.QobuzCached(qobuzId = id.toLongOrNull() ?: 0L),
            sourceType = SourceType.QOBUZ,
        )
    )

    private fun album(id: Long) = DiscoveryItem.AlbumItem(Album(id = id, title = "Album $id"))

    private fun artist(id: Long) = DiscoveryItem.ArtistItem(Artist(id = id, name = "Artist $id"))

    @Test
    fun `the same numeric id in different namespaces gets different keys`() {
        val keys = listOf(track("1234"), album(1234L), artist(1234L)).map { it.key }
        assertEquals("all three should be distinct", 3, keys.toSet().size)
    }

    @Test
    fun `a shelf of mixed items has no duplicate keys`() {
        val shelf = DiscoveryShelf(
            id = "mixed",
            title = "Mixed",
            items = listOf(track("1"), track("2"), album(1L), album(2L), artist(1L)),
        )
        assertEquals(shelf.items.size, shelf.items.map { it.key }.toSet().size)
    }

    @Test
    fun `keys are stable across rebuilds of the same item`() {
        assertEquals(album(7L).key, album(7L).key)
        assertNotEquals(album(7L).key, album(8L).key)
    }

    @Test
    fun `de-duplicating on the key is what makes a shelf safe to render`() {
        // A catalogue search can return the same record twice — a re-release, a
        // mirrored entry, an artist credited twice in a similar-artists list.
        // The builders run distinctBy on this key before wrapping a shelf, so
        // the property that has to hold is that the key identifies the record.
        val raw = listOf(album(1L), track("9"), album(1L), artist(1L), track("9"))
        assertEquals(3, raw.distinctBy { it.key }.size)
    }

    @Test
    fun `a shelf carries its reason so the row can explain itself`() {
        val shelf = DiscoveryShelf(
            id = "similar_to_42",
            title = "Because you play Aphex Twin",
            reason = "Artists Qobuz places next to Aphex Twin",
            items = listOf(artist(1L)),
        )
        assertEquals("Artists Qobuz places next to Aphex Twin", shelf.reason)
        // See All is offered by default; a shelf can opt out of it.
        assertEquals(true, shelf.seeAll)
        assertEquals(false, shelf.copy(seeAll = false).seeAll)
    }
}
