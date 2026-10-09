package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Discover's daily genres, against the **real bundled graph and histories**.
 *
 * What has to hold is what the page promises out loud: the pick is the same all
 * day, it is next door to the listener's genres when it says it is, it is never
 * somewhere they have already been, and the genre of the day always has the
 * history the card quotes from.
 */
class DailyDiscoveryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val assets = File("src/main/assets")

    private val graph: GenreGraph by lazy {
        val data = json.decodeFromString(GenreGraphData.serializer(), File(assets, "genre_graph.json").readText())
        GenreGraph(data, emptyMap())
    }

    private val withHistory: Set<String> by lazy {
        json.decodeFromString(GenreHistoryData.serializer(), File(assets, "genre_history.json").readText())
            .entries.keys
    }

    private val anchors = listOf("trance", "eurodance")

    @Test
    fun `the anchors used here are on the map`() {
        anchors.forEach { assertNotNull("$it is not in the graph", graph[it]) }
    }

    @Test
    fun `the same day gives the same pick`() {
        val a = DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day = 20_000)
        val b = DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day = 20_000)
        assertEquals(a, b)
    }

    @Test
    fun `a week of picks is not one genre`() {
        val week = (20_000L until 20_007L).mapNotNull {
            DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day = it)?.genre?.id
        }
        assertEquals(7, week.size)
        assertTrue("a week of picks was $week", week.toSet().size >= 3)
    }

    @Test
    fun `the pick is next door to the listener's genres, and says which`() {
        for (day in 20_000L until 20_030L) {
            val pick = DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day)!!
            assertTrue("${pick.genre.id} claims no anchor", pick.via.isNotEmpty())
            assertTrue(pick.via.all { it.id in anchors })
            assertFalse("${pick.genre.id} is an anchor", pick.genre.id in anchors)
            assertFalse("${pick.genre.id} is a family root", pick.genre.parents.isEmpty())
            // What it claims is what explain() finds again from the stored id.
            assertEquals(pick.via.toSet(), DailyDiscovery.explain(graph, pick.genre.id, anchors).toSet())
        }
    }

    @Test
    fun `an explored genre is never picked`() {
        val explored = mutableSetOf<String>()
        repeat(10) { step ->
            val pick = DailyDiscovery.todaysGenre(graph, anchors, explored, day = 20_000L + step)!!
            assertFalse("${pick.genre.id} was already explored", pick.genre.id in explored)
            explored += pick.genre.id
        }
    }

    @Test
    fun `yesterday's pick is not today's`() {
        for (day in 20_000L until 20_060L) {
            val yesterday = DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day - 1)!!.genre.id
            val today = DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day, avoid = setOf(yesterday))!!
            assertNotEquals(yesterday, today.genre.id)
        }
    }

    @Test
    fun `a new listener still gets a well-known genre, and no claimed link`() {
        val pick = DailyDiscovery.todaysGenre(graph, emptyList(), emptySet(), day = 20_000)!!
        assertTrue(pick.via.isEmpty())
        assertNotNull("a fallback pick should be a genre people tag", pick.genre.reach)
        assertFalse(pick.genre.parents.isEmpty())
    }

    @Test
    fun `stale anchors from an older dataset are ignored, not crashed on`() {
        val pick = DailyDiscovery.todaysGenre(graph, listOf("no-such-genre"), emptySet(), day = 20_000)
        assertNotNull(pick)
        assertTrue(pick!!.via.isEmpty())
    }

    @Test
    fun `the genre of the day always has a history, and is not today's discovery`() {
        for (day in 20_000L until 20_060L) {
            val today = DailyDiscovery.todaysGenre(graph, anchors, emptySet(), day)!!.genre.id
            val spot = DailyDiscovery.spotlight(graph, withHistory, anchors, setOf(today), day)!!
            assertTrue("${spot.id} has no history", spot.id in withHistory)
            assertNotEquals(today, spot.id)
            assertFalse(spot.id in anchors)
        }
    }

    @Test
    fun `the genre of the day works for a new listener too`() {
        val spot = DailyDiscovery.spotlight(graph, withHistory, emptyList(), emptySet(), day = 20_000)
        assertNotNull(spot)
        assertTrue(spot!!.id in withHistory)
    }

    @Test
    fun `no histories loaded means no genre of the day`() {
        assertEquals(null, DailyDiscovery.spotlight(graph, emptySet(), anchors, emptySet(), day = 20_000))
    }
}
