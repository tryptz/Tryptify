package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.charts.ChartEntry
import kotlin.math.sqrt

/**
 * The planets are a genre's chart, read as a solar system: the order has to be
 * the chart's, one artist has to be one planet however the chart spells it,
 * and every body has to be where its orbit says.
 */
class PlanetSystemTest {

    private fun entry(rank: Int, artist: String, title: String, listens: Long = 0) =
        ChartEntry(rank = rank, title = title, artistName = artist, listenCount = listens)

    private val chart = listOf(
        entry(1, "Burial", "Archangel", 900),
        entry(2, "Four Tet", "Two Thousand and Seventeen", 700),
        entry(3, "Burial", "Near Dark", 800),
        entry(4, "Beyoncé", "Cuff It", 300),
        entry(5, "Beyonce", "Break My Soul", 350),
        entry(6, "Four Tet", "Baby", 100),
        entry(7, "Burial", "Untrue", 50),
    )

    @Test
    fun `an empty chart has no planets`() {
        assertNull(PlanetSystem.from("dubstep", emptyList()))
    }

    @Test
    fun `planets are artists, most listened first`() {
        val system = PlanetSystem.from("dubstep", chart)!!
        assertEquals(listOf("Burial", "Four Tet", "Beyoncé"), system.planets.map { it.artist })
        assertEquals(1f, system.planets[0].share, 0f)
        assertTrue(system.planets.zipWithNext().all { (a, b) -> a.orbit < b.orbit && a.share >= b.share })
    }

    @Test
    fun `one artist spelt two ways is one planet`() {
        val system = PlanetSystem.from("dubstep", chart)!!
        val beyonce = system.planets.single { it.artist.startsWith("Beyonc") }
        assertEquals(listOf("Cuff It", "Break My Soul"), beyonce.moons.map { it.entry.title })
    }

    @Test
    fun `moons are the artist's tracks, in chart order`() {
        val system = PlanetSystem.from("dubstep", chart)!!
        assertEquals(listOf("Archangel", "Near Dark", "Untrue"), system.planets[0].moons.map { it.entry.title })
    }

    @Test
    fun `without listen counts the ranks decide`() {
        val ranked = chart.map { it.copy(listenCount = 0) }
        val system = PlanetSystem.from("dubstep", ranked)!!
        // Burial holds ranks 1, 3 and 7; Four Tet 2 and 6.
        assertEquals("Burial", system.planets[0].artist)
        assertEquals("Four Tet", system.planets[1].artist)
    }

    @Test
    fun `no more planets or moons than the view has room for`() {
        val big = (1..100).map { entry(it, "Artist ${it % 9}", "Track $it", (1000 - it).toLong()) }
        val system = PlanetSystem.from("pop", big)!!
        assertEquals(PlanetSystem.MAX_PLANETS, system.planets.size)
        assertTrue(system.planets.all { it.moons.size <= PlanetSystem.MAX_MOONS })
        for (planet in system.planets) {
            assertTrue("${planet.artist} reaches past the fit", planet.orbit + planet.reach <= PlanetSystem.MAX_REACH)
        }
    }

    @Test
    fun `bodies stay on their orbits`() {
        val system = PlanetSystem.from("dubstep", chart)!!
        val out = FloatArray(3)
        val moon = FloatArray(3)
        for (time in listOf(0f, 3.3f, 41f, 900f)) {
            for (p in system.planets.indices) {
                system.planetPosition(p, time, 10f, -5f, 20f, out)
                val d = sqrt((out[0] - 10f).let { it * it } + (out[1] + 5f).let { it * it } + (out[2] - 20f).let { it * it })
                assertEquals(system.planets[p].orbit, d, 1e-3f)
                for (m in system.planets[p].moons.indices) {
                    system.moonPosition(p, m, time, 10f, -5f, 20f, moon)
                    val dm = sqrt((moon[0] - out[0]).let { it * it } + (moon[1] - out[1]).let { it * it } + (moon[2] - out[2]).let { it * it })
                    assertEquals(system.planets[p].moons[m].orbit, dm, 1e-3f)
                }
            }
        }
    }

    @Test
    fun `the same chart gives the same system`() {
        val a = PlanetSystem.from("dubstep", chart)!!
        val b = PlanetSystem.from("dubstep", chart)!!
        val pa = FloatArray(3); val pb = FloatArray(3)
        a.moonPosition(0, 1, 12.5f, 0f, 0f, 0f, pa)
        b.moonPosition(0, 1, 12.5f, 0f, 0f, 0f, pb)
        assertTrue(pa.contentEquals(pb))
    }
}
