package tf.monochrome.android.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.discover.galaxy.StarField
import tf.monochrome.android.ui.discover.galaxy.StarLayout

/** The galaxies' search: names that start with the query first, then the biggest stars; aliases count. */
class GalaxySearchTest {

    private val field = StarField(
        ids = listOf("a", "b", "c", "d"),
        names = listOf("Liquid Funk", "Drum and Bass", "Acid House", "House"),
        families = listOf("x"),
        family = IntArray(4),
        prominence = floatArrayOf(0.2f, 0.9f, 0.5f, 0.8f),
        startYear = IntArray(4) { 2000 },
        links = IntArray(0),
        layout = StarLayout { _, _, _, _ -> 0f },
    )

    @Test
    fun `an empty query matches nothing`() {
        assertTrue(matchStars(field, "  ").isEmpty())
    }

    @Test
    fun `a name starting with the query comes before one containing it`() {
        assertEquals(listOf("d", "c"), matchStars(field, "house").map { it.first })
    }

    @Test
    fun `another name finds the star`() {
        val aka = mapOf(1 to listOf("dnb"))
        assertEquals(listOf("b"), matchStars(field, "dnb") { aka[it].orEmpty() }.map { it.first })
    }

    @Test
    fun `no more than the limit`() {
        assertEquals(1, matchStars(field, "u", limit = 1).size)
    }
}
