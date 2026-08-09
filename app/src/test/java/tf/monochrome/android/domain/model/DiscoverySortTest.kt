package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three Discover orderings.
 *
 * What actually needs guarding is that they are three *different* orderings and
 * that none of them loses a record — a sort that silently drops the items it
 * can't date, or that reorders the feed builder's deliberate interleave, is a
 * worse bug than an unsorted list.
 */
class DiscoverySortTest {

    private fun track(id: String, artist: String, year: Int?) = DiscoveryItem.TrackItem(
        UnifiedTrack(
            id = id,
            title = id,
            durationSeconds = 180,
            artistName = artist,
            artistNames = listOf(artist),
            releaseYear = year,
            source = PlaybackSource.HiFiApi(tidalId = 1),
            sourceType = SourceType.API,
        ),
    )

    private fun shelf(id: String, vararg items: DiscoveryItem) =
        DiscoveryShelf(id = id, title = id, reason = null, items = items.toList())

    private val familiar = setOf("aphex twin")

    private val feed = listOf(
        shelf(
            "a",
            track("t1", "Stranger", 1998),
            track("t2", "Aphex Twin", 2011),
            track("t3", "Other", null),
        ),
        shelf("b", track("t4", "Nobody", 2024)),
    )

    @Test
    fun `most popular leaves the catalogue's own order alone`() {
        val out = DiscoverySort.apply(feed, DiscoverySort.POPULAR, familiar)
        assertEquals(feed, out)
    }

    @Test
    fun `for you floats artists you already play to the front of each shelf`() {
        val out = DiscoverySort.apply(feed, DiscoverySort.FOR_YOU, familiar)
        assertEquals("t2", out[0].items.first().key.removePrefix("t:"))
        // And the ones it doesn't know keep the order they arrived in.
        assertEquals(
            listOf("t2", "t1", "t3"),
            out[0].items.map { it.key.removePrefix("t:") },
        )
    }

    @Test
    fun `for you leaves the shelf order to the feed builder`() {
        // The builder interleaves familiar, neighbouring and genre shelves on
        // purpose; re-sorting shelves by familiarity would undo that.
        val out = DiscoverySort.apply(feed, DiscoverySort.FOR_YOU, familiar)
        assertEquals(feed.map { it.id }, out.map { it.id })
    }

    @Test
    fun `newest leads with the most recent, and undated records fall to the back`() {
        val out = DiscoverySort.apply(feed, DiscoverySort.NEWEST, familiar)
        assertEquals("b", out.first().id)
        assertEquals(
            listOf("t2", "t1", "t3"),
            out.first { it.id == "a" }.items.map { it.key.removePrefix("t:") },
        )
    }

    @Test
    fun `no ordering ever loses a record`() {
        val before = feed.flatMap { it.items }.map { it.key }.toSet()
        for (sort in DiscoverySort.entries) {
            val after = DiscoverySort.apply(feed, sort, familiar).flatMap { it.items }.map { it.key }
            assertEquals("$sort changed the item count", before.size, after.size)
            assertEquals("$sort lost or invented items", before, after.toSet())
        }
    }

    @Test
    fun `an empty library doesn't make everything familiar`() {
        val out = DiscoverySort.apply(feed, DiscoverySort.FOR_YOU, emptySet())
        assertEquals(feed.map { it.items }, out.map { it.items })
        assertTrue(feed.flatMap { it.items }.none { it.isBy(emptySet()) })
    }

    @Test
    fun `ids round-trip and an unknown one falls back rather than throwing`() {
        for (sort in DiscoverySort.entries) {
            assertEquals(sort, DiscoverySort.fromId(sort.id))
        }
        assertEquals(DiscoverySort.DEFAULT, DiscoverySort.fromId("nonsense"))
        assertEquals(DiscoverySort.DEFAULT, DiscoverySort.fromId(null))
    }
}
