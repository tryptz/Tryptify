package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedArtist

/**
 * The library sorts, pinned at the point they are easiest to get wrong.
 *
 * These exist because the sorts were rewritten to compute their text keys once
 * per element instead of once per comparison — a `compareBy { it.name.lowercase() }`
 * selector runs on every comparison, so a library sort allocated a fresh String
 * O(n log n) times. That rewrite is only safe if the order it produces is
 * byte-for-byte the order the old one produced, ties included, which is what
 * the tests below hold.
 *
 * The subtle half is descending. The implementation sorts ascending and
 * reverses the RESULT; it does not reverse the comparator. Those are different
 * whenever two elements tie: reversing the list flips tied elements as well,
 * reversing the comparator leaves them in input order. The library has always
 * done the former, so a "simplification" to `comparator.reversed()` would
 * silently reshuffle every equal-named artist in the list.
 */
class LibrarySortTest {

    private fun artist(name: String, tracks: Int = 0, albums: Int = 0) = UnifiedArtist(
        id = "$name-$tracks-$albums",
        name = name,
        albumCount = albums,
        trackCount = tracks,
        sourceType = SourceType.LOCAL,
    )

    private fun names(list: List<UnifiedArtist>) = list.map { it.id }

    @Test
    fun `name sort is case-insensitive and ascending`() {
        val sorted = listOf(artist("beta"), artist("Alpha"), artist("gamma"))
            .applySort(LibrarySort(LibrarySortKey.NAME, ascending = true))
        assertEquals(listOf("Alpha", "beta", "gamma"), sorted.map { it.name })
    }

    @Test
    fun `ties keep their input order when ascending`() {
        // Three artists whose sort key is identical: a stable sort must leave
        // them in the order they arrived.
        val input = listOf(artist("same", tracks = 1), artist("same", tracks = 2), artist("same", tracks = 3))
        val sorted = input.applySort(LibrarySort(LibrarySortKey.NAME, ascending = true))
        assertEquals(listOf("same-1-0", "same-2-0", "same-3-0"), names(sorted))
    }

    @Test
    fun `ties reverse when descending, because the list is reversed and not the comparator`() {
        // This is the assertion that fails if anyone "simplifies" descending to
        // sortedWith(comparator.reversed()) — that keeps ties in input order.
        val input = listOf(artist("same", tracks = 1), artist("same", tracks = 2), artist("same", tracks = 3))
        val sorted = input.applySort(LibrarySort(LibrarySortKey.NAME, ascending = false))
        assertEquals(listOf("same-3-0", "same-2-0", "same-1-0"), names(sorted))
    }

    @Test
    fun `descending is exactly the ascending result reversed`() {
        val input = listOf(
            artist("delta"), artist("alpha"), artist("delta"), artist("Bravo"), artist("alpha"),
        )
        val ascending = input.applySort(LibrarySort(LibrarySortKey.NAME, ascending = true))
        val descending = input.applySort(LibrarySort(LibrarySortKey.NAME, ascending = false))
        assertEquals(names(ascending.reversed()), names(descending))
    }

    @Test
    fun `numeric sorts are unaffected by the text-key rewrite`() {
        val input = listOf(artist("a", tracks = 30), artist("b", tracks = 4), artist("c", tracks = 100))
        val byTracks = input.applySort(LibrarySort(LibrarySortKey.TRACKS, ascending = true))
        assertEquals(listOf(4, 30, 100), byTracks.map { it.trackCount })
    }

    @Test
    fun `an empty or single-element list survives every key`() {
        for (key in LibrarySortKey.entries) {
            for (ascending in listOf(true, false)) {
                val sort = LibrarySort(key, ascending)
                assertEquals(emptyList<UnifiedArtist>(), emptyList<UnifiedArtist>().applySort(sort))
                assertEquals(1, listOf(artist("only")).applySort(sort).size)
            }
        }
    }

    @Test
    fun `sorting does not mutate the list it was given`() {
        val input = listOf(artist("charlie"), artist("alpha"), artist("bravo"))
        val before = names(input)
        input.applySort(LibrarySort(LibrarySortKey.NAME, ascending = true))
        input.applySort(LibrarySort(LibrarySortKey.NAME, ascending = false))
        assertEquals("applySort must return a new list, never sort in place", before, names(input))
    }
}
