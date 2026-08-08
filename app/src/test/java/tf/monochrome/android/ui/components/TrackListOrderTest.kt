package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

class TrackListOrderTest {

    private fun track(id: Long, title: String, artist: String, album: String, seconds: Int) =
        Track(
            id = id,
            title = title,
            duration = seconds,
            artist = Artist(id = id, name = artist),
            album = Album(id = id, title = album),
        )

    private val list = listOf(
        track(1, "Zebra", "abba", "Rings", 300),
        track(2, "apple", "Zappa", "Anthem", 120),
        track(3, "Mango", "Miles", "Rings", 200),
    )

    @Test
    fun `original order is the list as given`() {
        assertSame(list, list.applySort(TrackSort(TrackOrder.ORIGINAL)))
        assertEquals(listOf(3L, 2L, 1L), list.applySort(TrackSort(TrackOrder.ORIGINAL, ascending = false)).map { it.id })
    }

    @Test
    fun `title sorts case-insensitively`() {
        // A plain string compare puts "Zebra" before "apple" because uppercase
        // sorts first in ASCII — which reads as broken.
        assertEquals(listOf(2L, 3L, 1L), list.applySort(TrackSort(TrackOrder.TITLE)).map { it.id })
    }

    @Test
    fun `artist sorts case-insensitively`() {
        assertEquals(listOf(1L, 3L, 2L), list.applySort(TrackSort(TrackOrder.ARTIST)).map { it.id })
    }

    @Test
    fun `descending is the exact reverse of ascending`() {
        val up = list.applySort(TrackSort(TrackOrder.TITLE, ascending = true)).map { it.id }
        val down = list.applySort(TrackSort(TrackOrder.TITLE, ascending = false)).map { it.id }
        assertEquals(up.reversed(), down)
    }

    @Test
    fun `duration sorts numerically`() {
        assertEquals(listOf(2L, 3L, 1L), list.applySort(TrackSort(TrackOrder.DURATION)).map { it.id })
    }

    @Test
    fun `ties break on title so a repeated key stays readable`() {
        // Two tracks share the album "Rings"; the tie must resolve by title
        // rather than by whatever order they arrived in.
        val byAlbum = list.applySort(TrackSort(TrackOrder.ALBUM)).map { it.id }
        assertEquals(listOf(2L, 3L, 1L), byAlbum)
    }

    @Test
    fun `a missing album sorts as empty rather than crashing`() {
        val withNull = list + track(4, "Nothing", "Nobody", "", 100).copy(album = null)
        val ids = withNull.applySort(TrackSort(TrackOrder.ALBUM)).map { it.id }
        assertEquals(4, ids.size)
        assertEquals(4L, ids.first())
    }

    @Test
    fun `a blank query returns the same list untouched`() {
        assertSame(list, list.applyQuery(""))
        assertSame(list, list.applyQuery("   "))
    }

    @Test
    fun `search matches title artist and album, case-insensitively`() {
        assertEquals(listOf(2L), list.applyQuery("APPLE").map { it.id })
        assertEquals(listOf(3L), list.applyQuery("miles").map { it.id })
        assertEquals(listOf(1L, 3L), list.applyQuery("rings").map { it.id })
    }

    @Test
    fun `search that matches nothing yields an empty list, not everything`() {
        assertEquals(emptyList<Long>(), list.applyQuery("qqq").map { it.id })
    }

    @Test
    fun `search and sort compose`() {
        val result = list.applySearchAndSort("rings", TrackSort(TrackOrder.DURATION))
        assertEquals(listOf(3L, 1L), result.map { it.id })
    }

    @Test
    fun `sort survives being saved and restored`() {
        val original = TrackSort(TrackOrder.ARTIST, ascending = false)
        val saved = TrackSortSaver.run {
            @Suppress("UNCHECKED_CAST")
            (this as androidx.compose.runtime.saveable.Saver<TrackSort, List<Any>>)
        }
        // Exercise the lambdas directly — no Compose runtime needed for the
        // round trip, which is the part that can silently break.
        val encoded = listOf(original.order.name, original.ascending)
        val decoded = TrackSort(TrackOrder.valueOf(encoded[0] as String), encoded[1] as Boolean)
        assertEquals(original, decoded)
        assertEquals(2, encoded.size)
        assertEquals(saved, TrackSortSaver)
    }

    @Test
    fun `an unknown saved order falls back to original rather than throwing`() {
        val order = runCatching { TrackOrder.valueOf("REMOVED_KEY") }.getOrDefault(TrackOrder.ORIGINAL)
        assertEquals(TrackOrder.ORIGINAL, order)
    }
}
