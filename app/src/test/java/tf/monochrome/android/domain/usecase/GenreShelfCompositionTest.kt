package tf.monochrome.android.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a genre row is put together from two sources, and what it says about
 * itself once it is.
 *
 * The row in the bug report claimed nothing and was built from a name search.
 * These hold the two halves of the fix: that a shelf merges its sources rather
 * than choosing one, and that whatever it ends up holding, the reason line
 * names it.
 */
class GenreShelfCompositionTest {

    @Test
    fun `the stronger source keeps the head of the row`() {
        val merged = mergeByKey(listOf("c1", "c2"), listOf("a1", "a2", "a3"), limit = 5) { it }
        assertEquals(listOf("c1", "c2", "a1", "a2", "a3"), merged)
    }

    @Test
    fun `a record in both halves appears once, in its stronger position`() {
        // The same track charts and is also one of its artist's top tracks —
        // the normal case, not the exception. A repeat here is not a cosmetic
        // double: the lazy row is keyed on the card key and a duplicate takes
        // the screen down.
        val merged = mergeByKey(listOf("c1", "c2"), listOf("c2", "a1"), limit = 5) { it }
        assertEquals(listOf("c1", "c2", "a1"), merged)
    }

    @Test
    fun `either half alone still fills a row`() {
        assertEquals(listOf("a1", "a2"), mergeByKey(emptyList(), listOf("a1", "a2"), 5) { it })
        assertEquals(listOf("c1"), mergeByKey(listOf("c1"), emptyList(), 5) { it })
        assertTrue(mergeByKey(emptyList<String>(), emptyList(), 5) { it }.isEmpty())
    }

    @Test
    fun `the row is capped at what it can show`() {
        assertEquals(listOf("c1", "c2"), mergeByKey(listOf("c1", "c2", "c3"), listOf("a1"), 2) { it })
        assertTrue(mergeByKey(listOf("c1"), listOf("a1"), 0) { it }.isEmpty())
    }

    @Test
    fun `a chart-only row says exactly what it always said`() {
        // Pinned rather than merely asserted: this wording is what the feed has
        // read for as long as the chart path has existed, and a shelf that
        // quietly renames its evidence is a shelf nobody can compare.
        assertEquals(
            "For wind down · ranked by plays",
            genreShelfReason("For wind down", charted = 12, fromArtists = 0),
        )
    }

    @Test
    fun `a row filled from both sources names both`() {
        assertEquals(
            "For rage · 200–260 BPM · ranked by plays and its most-played artists",
            genreShelfReason("For rage · 200–260 BPM", charted = 4, fromArtists = 16),
        )
    }

    @Test
    fun `a row built entirely from the genre's artists does not claim the chart`() {
        assertEquals(
            "The genre itself · its most-played artists",
            genreShelfReason("The genre itself", charted = 0, fromArtists = 20),
        )
    }

    @Test
    fun `a borrowed row names the genre it borrowed from`() {
        assertEquals(
            "For rage · by way of Gabber",
            genreShelfReason("For rage", charted = 0, fromArtists = 0, borrowedFrom = "Gabber"),
        )
    }

    @Test
    fun `a row with nothing behind it claims nothing`() {
        assertEquals("For focus", genreShelfReason("For focus", charted = 0, fromArtists = 0))
    }
}
