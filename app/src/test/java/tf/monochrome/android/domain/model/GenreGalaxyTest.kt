package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** "Surprise me" and "You are here", against the real graph. */
class GenreGalaxyTest {

    private val graph: GenreGraph by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val data = json.decodeFromString(GenreGraphData.serializer(), File("src/main/assets/genre_graph.json").readText())
        GenreGraph(data, emptyMap())
    }

    @Test
    fun `surprise never picks somewhere explored, a family root, or where you are`() {
        val explored = graph.allGenres.sortedByDescending { it.reach ?: -1 }.take(50).map { it.id }.toSet()
        val random = Random(42)
        repeat(200) {
            val pick = GenreGalaxy.surprise(graph, explored, random, current = "trance")!!
            assertFalse(pick.id in explored)
            assertFalse(pick.parents.isEmpty())
            assertTrue(pick.id != "trance")
            assertNotNull(pick.reach)
        }
    }

    @Test
    fun `with everything well known explored, surprise still goes somewhere`() {
        val everything = graph.allGenres.map { it.id }.toSet()
        assertNotNull(GenreGalaxy.surprise(graph, everything, Random(1)))
    }

    @Test
    fun `you are where you last were, else where you hearted, ignoring stale ids`() {
        assertEquals("trance", GenreGalaxy.here(graph, listOf("no-such-genre", "trance", "techno"), setOf("jazz"))?.id)
        assertEquals("jazz", GenreGalaxy.here(graph, emptyList(), setOf("jazz"))?.id)
        assertNull(GenreGalaxy.here(graph, listOf("gone"), emptySet()))
    }
}
