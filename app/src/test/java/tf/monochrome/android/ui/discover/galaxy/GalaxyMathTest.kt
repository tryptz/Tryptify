package tf.monochrome.android.ui.discover.galaxy

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreGraphData
import java.io.File
import kotlin.math.hypot

/**
 * The galaxy's geometry, on the real genre graph: what the view draws has to
 * be where the scene says, every genre has to be somewhere, and the time
 * spiral has to actually be about time.
 */
class GalaxyMathTest {

    private val graph: GenreGraph by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        GenreGraph(json.decodeFromString(GenreGraphData.serializer(), File("src/main/assets/genre_graph.json").readText()), emptyMap())
    }
    private val scene by lazy { GalaxyScene(graph) }

    @Test
    fun `every genre has a finite place in both layouts`() {
        assertEquals(graph.size, scene.size)
        for (i in 0 until scene.size * 3) {
            assertTrue(scene.sound[i].isFinite())
            assertTrue(scene.time[i].isFinite())
        }
    }

    @Test
    fun `the same graph gives the same galaxy`() {
        val again = GalaxyScene(graph)
        assertTrue(scene.sound.contentEquals(again.sound))
        assertTrue(scene.time.contentEquals(again.time))
        assertTrue(scene.dustOffset.contentEquals(again.dustOffset))
    }

    @Test
    fun `in the time layout, older genres sit nearer the core`() {
        val radius = { i: Int -> hypot(scene.time[i * 3], scene.time[i * 3 + 2]) }
        val old = (0 until scene.size).filter { scene.startYear(it) < 1900 }.map(radius).average()
        val mid = (0 until scene.size).filter { scene.startYear(it) in 1950..1979 }.map(radius).average()
        val new = (0 until scene.size).filter { scene.startYear(it) >= 2000 }.map(radius).average()
        assertTrue("old $old, mid $mid, new $new", old < mid && mid < new)
    }

    @Test
    fun `time radius rises with the year and stays in the disc`() {
        var last = -1f
        for (year in listOf(400, 1200, 1600, 1850, 1900, 1950, 1980, 2000, 2010, 2025, 2030)) {
            val r = GalaxyScene.timeRadius(year)
            assertTrue("$year → $r", r >= last && r in 0f..1f)
            last = r
        }
    }

    @Test
    fun `morph 0 is the sound layout and 1 the time layout`() {
        val out = FloatArray(3)
        scene.position(7, 0f, out)
        assertEquals(scene.sound[21], out[0], 1e-4f)
        scene.position(7, 1f, out)
        assertEquals(scene.time[23], out[2], 1e-4f)
    }

    @Test
    fun `dust and links point at real genres`() {
        assertTrue("dust owner out of range", scene.dustOwner.all { it in 0 until scene.size })
        assertTrue("link out of range", scene.links.all { it in 0 until scene.size })
        assertTrue("odd link count ${scene.links.size}", scene.links.size % 2 == 0)
        // Most genres have a parent on the map; an empty list is the bug where
        // buildList's own `size` stood in for the genre count.
        assertTrue("only ${scene.links.size / 2} links", scene.links.size / 2 > scene.size / 2)
        assertTrue("no nebula anchors", scene.nebulaAnchors.isNotEmpty())
    }

    private fun overview(width: Float = 1080f, height: Float = 2200f): CameraFrame {
        val camera = GalaxyCamera().apply { distance = GalaxyCamera.fitDistance(GalaxyScene.RADIUS, width / height) }
        return camera.frame(width, height, centerY = height * 0.42f)
    }

    @Test
    fun `the target lands in the middle of the visible area`() {
        val f = overview()
        val out = FloatArray(3)
        assertTrue(f.project(0f, 0f, 0f, out, 0))
        assertEquals(540f, out[0], 0.5f)
        assertEquals(2200f * 0.42f, out[1], 0.5f)
        assertEquals(f.distance, out[2], 0.5f)
    }

    @Test
    fun `a point behind the camera is not drawn`() {
        val f = overview()
        val out = FloatArray(3)
        val behind = floatArrayOf(f.ex - f.fx * 50f, f.ey - f.fy * 50f, f.ez - f.fz * 50f)
        assertFalse(f.project(behind[0], behind[1], behind[2], out, 0))
        assertEquals(-1f, out[2], 0f)
    }

    @Test
    fun `nearer is bigger`() {
        val f = overview()
        assertTrue(f.scaleAt(500f) > f.scaleAt(3000f))
    }

    @Test
    fun `the overview fits the galaxy across a portrait phone`() {
        val f = overview()
        val out = FloatArray(3)
        // The rim points to the left and right of the centre, on the disc.
        assertTrue(f.project(-GalaxyScene.RADIUS, 0f, 0f, out, 0))
        val left = out[0]
        assertTrue(f.project(GalaxyScene.RADIUS, 0f, 0f, out, 0))
        val right = out[0]
        val across = right - left
        assertTrue("galaxy spans $across of 1080 px", across in 900f..1400f)
    }

    @Test
    fun `orbit and zoom stay inside their limits`() {
        val c = GalaxyCamera()
        c.orbit(0f, 100_000f)
        assertEquals(GalaxyCamera.MAX_PITCH, c.pitch, 0f)
        c.orbit(0f, -100_000f)
        assertEquals(GalaxyCamera.MIN_PITCH, c.pitch, 0f)
        c.zoom(1e6f)
        assertEquals(GalaxyCamera.MIN_DISTANCE, c.distance, 0f)
        c.zoom(1e-6f)
        assertEquals(GalaxyCamera.MAX_DISTANCE, c.distance, 0f)
    }

    @Test
    fun `panning lets go of the followed star`() {
        val c = GalaxyCamera().apply { follow = 12; followPlanet = 2 }
        c.orbit(40f, 10f)
        c.zoom(1.5f)
        assertEquals("orbit and zoom keep the star", 12, c.follow)
        assertEquals("orbit and zoom keep the planet", 2, c.followPlanet)
        c.pan(30f, 0f, c.frame(1080f, 2200f))
        assertEquals(-1, c.follow)
        assertEquals(-1, c.followPlanet)
    }
}
