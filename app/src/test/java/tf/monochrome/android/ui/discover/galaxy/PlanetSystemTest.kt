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
            assertTrue(
                "${planet.artist} reaches past the fit",
                planet.orbit + planet.reach <= PlanetSystem.reachFor(system.starRadius) + 1e-3f,
            )
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

    @Test
    fun `planets are small beside their star, and moons beside their planet`() {
        for (star in listOf(GalaxyScene.STAR_RADIUS_MIN, PlanetSystem.DEFAULT_STAR_RADIUS, GalaxyScene.STAR_RADIUS_MAX)) {
            val system = PlanetSystem.from("dubstep", chart, starRadius = star)!!
            for (planet in system.planets) {
                assertTrue("${planet.artist} is not much smaller than its star", planet.radius <= star / 8f + 1e-4f)
                // Clear of the star, and of the planet inside it.
                assertTrue(planet.orbit - planet.reach > star)
                for (moon in planet.moons) {
                    assertTrue(moon.radius < planet.radius / 3f)
                    assertTrue(moon.orbit > planet.radius + moon.radius)
                }
            }
            for ((inner, outer) in system.planets.zipWithNext()) {
                assertTrue("moons of neighbouring planets cross", inner.orbit + inner.reach < outer.orbit + outer.reach)
                assertTrue(outer.orbit - inner.orbit > inner.reach)
            }
        }
    }

    @Test
    fun `the hottest artist this week orbits nearest, a cold one far out`() {
        val system = PlanetSystem.from("dubstep", chart)!!
        val orbits = system.planets.map { it.orbit }
        assertTrue(orbits.zipWithNext().all { (a, b) -> b > a })
        assertEquals(PlanetSystem.firstOrbit(system.starRadius), orbits.first(), 1e-3f)
        // In proportion to heat, not evenly spaced: Four Tet is far less hot
        // than Burial, so the gap after Burial is wider than an even step.
        val even = PlanetSystem.orbitStep(system.starRadius)
        assertTrue(orbits[1] - orbits[0] > even)
    }

    @Test
    fun `orbits never come close enough for moons to cross, however the heat falls`() {
        for (heat in listOf(List(6) { 1f }, List(6) { 0f }, listOf(1f, 0.99f, 0.98f, 0.1f, 0.09f, 0.08f))) {
            val orbits = PlanetSystem.heatOrbits(heat, PlanetSystem.DEFAULT_STAR_RADIUS)
            val first = PlanetSystem.firstOrbit(PlanetSystem.DEFAULT_STAR_RADIUS)
            val last = first + (PlanetSystem.MAX_PLANETS - 1) * PlanetSystem.orbitStep(PlanetSystem.DEFAULT_STAR_RADIUS)
            assertTrue(orbits.all { it >= first - 1e-3f && it <= last + 1e-3f })
            assertTrue(orbits.toList().zipWithNext().all { (a, b) -> b - a > PlanetSystem.DEFAULT_STAR_RADIUS * 0.5f })
        }
    }

    @Test
    fun `a planet is the size of its artist's catalogue`() {
        val sized = PlanetSystem.from("dubstep", chart, releases = mapOf("Burial" to 12, "Four Tet" to 160, "Beyoncé" to 2))!!
        val byName = sized.planets.associateBy { it.artist }
        assertTrue(byName.getValue("Four Tet").radius > byName.getValue("Burial").radius)
        assertTrue(byName.getValue("Burial").radius > byName.getValue("Beyoncé").radius)
        // Unknown is middling, and the biggest catalogue tops out.
        assertEquals(PlanetSystem.CATALOG_UNKNOWN_SHARE, PlanetSystem.catalogShare(null), 0f)
        assertEquals(1f, PlanetSystem.catalogShare(PlanetSystem.CATALOG_FULL * 10), 0f)
    }

    @Test
    fun `a size coming in late moves nothing but the planet itself`() {
        val before = PlanetSystem.from("dubstep", chart)!!
        val after = before.sizedBy(mapOf("Burial" to 300))
        assertTrue(after.planets[0].radius > before.planets[0].radius)
        for (p in before.planets.indices) {
            assertEquals(before.planets[p].orbit, after.planets[p].orbit, 0f)
            assertEquals(before.planets[p].moons.map { it.orbit }, after.planets[p].moons.map { it.orbit })
        }
        assertTrue(before.sizedBy(emptyMap()) === before)
    }

    @Test
    fun `a giant genre is a wide system and a niche one a tight one`() {
        val giant = PlanetSystem.from("pop", chart, starRadius = GalaxyScene.starRadius(1f))!!
        val dwarf = PlanetSystem.from("philly club", chart, starRadius = GalaxyScene.starRadius(0f))!!
        assertEquals(GalaxyScene.STAR_RADIUS_MAX, giant.starRadius, 1e-4f)
        assertEquals(GalaxyScene.STAR_RADIUS_MIN, dwarf.starRadius, 1e-4f)
        assertTrue(giant.reach > dwarf.reach * 2f)
        assertTrue(giant.planets[0].radius > dwarf.planets[0].radius * 2f)
        // And the stars in between grow with how well known the genre is.
        val radii = (0..10).map { GalaxyScene.starRadius(it / 10f) }
        assertTrue(radii.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `the listener's planet size scales the worlds and their moons, and moves no planet`() {
        val base = PlanetSystem.from("dubstep", chart)!!
        assertTrue(base.withBodySize(1f) === base)
        val big = base.withBodySize(2f)
        assertEquals(2f, big.bodyScale, 0f)
        assertEquals(base.starRadius, big.starRadius, 0f)
        base.planets.zip(big.planets).forEach { (a, b) ->
            assertEquals(a.orbit, b.orbit, 0f)
            assertEquals(a.radius * 2f, b.radius, 1e-5f)
            a.moons.zip(b.moons).forEach { (m, n) ->
                assertEquals(m.radius * 2f, n.radius, 1e-5f)
                assertEquals(m.orbit * 2f, n.orbit, 1e-5f)
            }
        }
        // Back to the usual is the system it came from, size for size.
        val back = big.withBodySize(1f)
        base.planets.zip(back.planets).forEach { (a, b) -> assertEquals(a.radius, b.radius, 1e-5f) }
    }

    @Test
    fun `however big the planets are, every moon is clear of its own planet`() {
        val top = tf.monochrome.android.domain.model.GalaxyVisualSettings.BODY_SIZE_RANGE.endInclusive
        val sized = PlanetSystem.from("dubstep", chart, releases = mapOf("Burial" to 400, "Four Tet" to 300))!!
        for (k in listOf(0.5f, 1f, top)) {
            for (planet in sized.withBodySize(k).planets) {
                for (moon in planet.moons) assertTrue("at $k", moon.orbit - moon.radius > planet.radius)
            }
        }
    }

    @Test
    fun `a catalogue arriving after the size was set keeps the size`() {
        val base = PlanetSystem.from("dubstep", chart)!!
        val counts = mapOf("Burial" to 120)
        val sizedThenScaled = base.sizedBy(counts).withBodySize(1.8f)
        val scaledThenSized = base.withBodySize(1.8f).sizedBy(counts)
        assertEquals(1.8f, scaledThenSized.bodyScale, 0f)
        sizedThenScaled.planets.zip(scaledThenSized.planets).forEach { (a, b) ->
            assertEquals(a.radius, b.radius, 1e-5f)
            a.moons.zip(b.moons).forEach { (m, n) -> assertEquals(m.radius, n.radius, 1e-5f) }
        }
    }
}
