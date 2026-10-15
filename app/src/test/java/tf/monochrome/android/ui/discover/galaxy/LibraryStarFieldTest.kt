package tf.monochrome.android.ui.discover.galaxy

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreGraphData
import tf.monochrome.android.domain.model.LibraryArtist
import tf.monochrome.android.domain.model.LibraryCollab
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The library galaxy's stars, on the real genre graph: every artist beside
 * their genre, the ones it does not know in the halo, and — the rule this map
 * adds — a growing library never moving a star that was already there.
 */
class LibraryStarFieldTest {

    private val graph: GenreGraph by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        GenreGraph(json.decodeFromString(GenreGraphData.serializer(), File("src/main/assets/genre_graph.json").readText()), emptyMap())
    }

    private fun artist(key: String, tracks: Int = 10, year: Int? = 1995, vararg tags: Pair<String, Int>) =
        LibraryArtist(key = key, name = key.uppercase(), trackCount = tracks, firstYear = year, genreTags = tags.toMap())

    private val library = listOf(
        artist("miles davis", 40, 1957, "Jazz" to 40),
        artist("burial", 12, 2006, "Dubstep" to 12),
        artist("metallica", 30, 1983, "Thrash Metal" to 25, "Metal" to 5),
        artist("aphex twin", 50, 1992, "IDM" to 30, "Ambient" to 20),
        artist("mystery band", 8, 2011, "Wobble Hymnal Core" to 8),
        artist("no tags at all", 3, null),
    )

    private fun scene(artists: List<LibraryArtist>, collabs: List<LibraryCollab> = emptyList()) =
        GalaxyScene(LibraryStarField.build(artists, collabs, graph))

    private fun soundOf(s: GalaxyScene, key: String): FloatArray {
        val i = s.index.getValue(LibraryStarField.starId(key))
        return floatArrayOf(s.sound[i * 3], s.sound[i * 3 + 1], s.sound[i * 3 + 2])
    }

    private fun timeOf(s: GalaxyScene, key: String): FloatArray {
        val i = s.index.getValue(LibraryStarField.starId(key))
        return floatArrayOf(s.time[i * 3], s.time[i * 3 + 1], s.time[i * 3 + 2])
    }

    @Test
    fun `the same library gives the same galaxy`() {
        val a = scene(library)
        val b = scene(library.reversed())
        assertArrayEquals(a.sound, b.sound, 0f)
        assertArrayEquals(a.time, b.time, 0f)
        assertArrayEquals(a.phase, b.phase, 0f)
    }

    @Test
    fun `adding an artist moves no star that was already there`() {
        val before = scene(library)
        val after = scene(library + artist("bjork", 25, 1993, "Art Pop" to 25) + artist("aaa first by name", 4, 2001, "House" to 4))
        for (a in library) {
            assertArrayEquals("${a.key} moved on the disc", soundOf(before, a.key), soundOf(after, a.key), 0f)
            assertArrayEquals("${a.key} moved in the spiral", timeOf(before, a.key), timeOf(after, a.key), 0f)
            val ib = before.index.getValue(LibraryStarField.starId(a.key))
            val ia = after.index.getValue(LibraryStarField.starId(a.key))
            assertEquals("${a.key} twinkles differently", before.phase[ib], after.phase[ia], 0f)
        }
    }

    @Test
    fun `an artist sits beside their genre on the genre map`() {
        val frame = GenreStarField.frame(graph)
        val s = scene(library)
        val jazz = frame.disc.getValue(graph.resolve("Jazz")!!.id)
        val miles = soundOf(s, "miles davis")
        assertTrue("Miles Davis is far from jazz", hypot(miles[0] - jazz.first, miles[2] - jazz.second) < 46f * 4)
    }

    @Test
    fun `an artist's main genre is the tag most of their tracks carry`() {
        val node = LibraryStarField.mainGenre(artist("x", 30, 1990, "Ambient" to 5, "Techno" to 20, "Nonsense" to 40), graph)
        assertEquals(graph.resolve("Techno")!!.id, node!!.id)
        assertNull(LibraryStarField.mainGenre(artist("y", 3, 1990, "Nonsense" to 3), graph))
    }

    @Test
    fun `a tag holding several genres counts each`() {
        val node = LibraryStarField.mainGenre(artist("z", 10, 1990, "Nonsense; Techno" to 10), graph)
        assertEquals(graph.resolve("Techno")!!.id, node!!.id)
    }

    @Test
    fun `an unrecognised genre is not guessed at, it is in the halo`() {
        val s = scene(library)
        for (key in listOf("mystery band", "no tags at all")) {
            val p = soundOf(s, key)
            assertTrue("$key is in the disc at y=${p[1]}", abs(p[1]) >= 150f)
            val i = s.index.getValue(LibraryStarField.starId(key))
            assertEquals(LibraryStarField.UNCHARTED, s.families[s.family[i]])
        }
    }

    @Test
    fun `no star falls into the black hole in either layout`() {
        // Many artists on a genre near the core's clearing: the scatter must not
        // throw any of them inside it.
        val crowd = (0 until 300).map { artist("artist $it", 5 + it % 40, 1960 + it % 60, "Jazz" to 5) }
        val s = scene(crowd + library)
        for (i in 0 until s.size) {
            val d = hypot(s.sound[i * 3], s.sound[i * 3 + 2])
            val t = hypot(s.time[i * 3], s.time[i * 3 + 2])
            assertTrue("${s.names[i]} in the disc at $d", d >= GalaxyScene.CORE_CLEAR - 1e-3f)
            assertTrue("${s.names[i]} in the spiral at $t", t > GalaxyScene.DISK_OUTER)
        }
    }

    @Test
    fun `a bigger collection is a bigger star, the unknown artist a dim one`() {
        val unknown = LibraryArtist("unknown artist", "Unknown Artist", 50, null, kind = LibraryArtist.Kind.UNKNOWN)
        val s = scene(library + unknown)
        fun prominence(key: String) = s.prominence[s.index.getValue(LibraryStarField.starId(key))]
        assertTrue(prominence("aphex twin") > prominence("burial"))
        assertTrue(prominence("unknown artist") < prominence("aphex twin"))
    }

    @Test
    fun `collaborations link two stars once, and only stars`() {
        val s = scene(
            library,
            listOf(
                LibraryCollab("burial", "aphex twin", 2),
                LibraryCollab("aphex twin", "burial", 1),
                LibraryCollab("burial", "someone not in the library", 3),
                LibraryCollab("burial", "burial", 1),
            ),
        )
        assertEquals(2, s.links.size)
        val names = setOf(s.names[s.links[0]], s.names[s.links[1]])
        assertEquals(setOf("BURIAL", "APHEX TWIN"), names)
    }

    @Test
    fun `stars are laid out for the library's bigger systems`() {
        assertEquals(SystemLimits.LIBRARY, LibraryStarField.build(library, emptyList(), graph).systemLimits)
    }

    @Test
    fun `an artist's star id round-trips to their key, and a genre's does not`() {
        assertEquals("burial", LibraryStarField.keyOf(LibraryStarField.starId("burial")))
        assertNull(LibraryStarField.keyOf(graph.allGenres.first().id))
    }

    @Test
    fun `the genre galaxy draws every star as a sprite, as it always has`() {
        assertTrue(GalaxyScene(graph).spriteAlways.all { it })
    }

    @Test
    fun `a big library keeps its always-drawn sprites to the budget, the biggest stars first`() {
        val big = (0 until 3000).map { artist("artist $it", 1 + it % 300, 1960 + it % 60, "Rock" to 1) }
        val s = scene(big)
        assertEquals(GalaxyScene.STAR_SPRITE_BUDGET, s.spriteAlways.count { it })
        val smallestKept = (0 until s.size).filter { s.spriteAlways[it] }.minOf { s.prominence[it] }
        val largestDropped = (0 until s.size).filter { !s.spriteAlways[it] }.maxOf { s.prominence[it] }
        assertTrue(smallestKept >= largestDropped)
    }
}
