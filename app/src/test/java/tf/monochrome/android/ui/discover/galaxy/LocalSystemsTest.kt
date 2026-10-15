package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LibraryAlbum
import tf.monochrome.android.domain.model.LibraryTrack
import kotlin.math.hypot

/**
 * An artist's system: albums in release order, the most played as planets
 * and the rest a belt, tracks as moons and the rest a ring — and a play
 * changing how a planet glows, never where it is.
 */
class LocalSystemsTest {

    private var nextId = 0L

    private fun album(
        title: String,
        year: Int?,
        tracks: Int,
        plays: Int = 0,
        recent: Int = 0,
        trackSeconds: Int? = null,
        bpm: Float? = null,
        key: Int? = null,
        lufs: Float? = null,
        dr: Float? = null,
    ) = LibraryAlbum(
        key = "${title.lowercase()}|artist|${year ?: 0}",
        title = title,
        year = year,
        artworkUri = null,
        tracks = (1..tracks).map { n ->
            LibraryTrack(
                id = nextId++,
                key = "/music/$title/$n.flac",
                title = "$title $n",
                discNumber = 1,
                trackNumber = n,
                durationSeconds = trackSeconds ?: (120 + n * 10),
            )
        },
        plays = plays,
        recentPlays = recent,
        bpm = bpm,
        camelotKey = key,
        lufs = lufs,
        dynamicRange = dr,
    )

    private fun allTracks(sys: PlanetSystem) = (sys.planets + sys.belt).sumOf { it.moons.size + it.ringCount }

    @Test
    fun `no albums, no system`() {
        assertNull(LocalSystems.build("artist:x", emptyList()))
        assertNull(LocalSystems.build("artist:x", listOf(album("Empty", 2000, 0))))
    }

    @Test
    fun `the debut orbits nearest, each later record further out`() {
        val sys = LocalSystems.build(
            "artist:x",
            listOf(album("Third", 2010, 10), album("First", 1995, 12), album("Undated", null, 8), album("Second", 2001, 9)),
        )!!
        assertEquals(listOf("First", "Second", "Third", "Undated"), sys.planets.map { it.label })
        for (k in 1 until sys.planets.size) assertTrue(sys.planets[k].orbit > sys.planets[k - 1].orbit)
    }

    @Test
    fun `the most played albums are planets and the rest are the belt, everything still there`() {
        val albums = (1..12).map { album("Album $it", 1990 + it, 10, plays = if (it % 3 == 0) 100 else it) }
        val sys = LocalSystems.build("artist:x", albums)!!
        assertEquals(SystemLimits.LIBRARY.maxPlanets, sys.planets.size)
        assertEquals(12 - SystemLimits.LIBRARY.maxPlanets, sys.beltCount)
        // The four played a hundred times are all planets.
        assertTrue(listOf(3, 6, 9, 12).all { n -> sys.planets.any { it.label == "Album $n" } })
        assertEquals(12 * 10, allTracks(sys))
        // In orbit, still in release order.
        assertEquals(sys.planets.sortedBy { it.subtitle }.map { it.label }, sys.planets.map { it.label })
    }

    @Test
    fun `the most played tracks are moons in running order, the rest the ring`() {
        val a = album("Long", 2000, 14)
        val played = a.copy(tracks = a.tracks.mapIndexed { i, t -> if (i == 12 || i == 3) t.copy(plays = 50) else t })
        val planet = LocalSystems.build("artist:x", listOf(played))!!.planets.single()
        assertEquals(SystemLimits.LIBRARY.maxMoons, planet.moons.size)
        assertEquals(14 - SystemLimits.LIBRARY.maxMoons, planet.ringCount)
        val titles = planet.moons.map { it.title }
        assertTrue("Long 13" in titles && "Long 4" in titles)
        val numbers = titles.map { it.removePrefix("Long ").toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertTrue(planet.moons.all { it.ref is MoonRef.Local })
    }

    @Test
    fun `a play changes how a planet glows, never where it is`() {
        val albums = listOf(album("A", 1990, 10), album("B", 1995, 10), album("C", 2000, 10))
        val cold = LocalSystems.build("artist:x", albums)!!
        val hot = LocalSystems.build("artist:x", albums.map { if (it.title == "B") it.copy(plays = 5, recentPlays = 5) else it })!!
        assertEquals(cold.planets.map { it.label to it.orbit }, hot.planets.map { it.label to it.orbit })
        assertEquals(0f, cold.planets[1].glow, 0f)
        assertEquals(1f, hot.planets[1].glow, 0f)
        assertEquals(0f, hot.planets[0].glow, 0f)
    }

    @Test
    fun `neighbouring planets' moons never cross, nor reach the belt`() {
        for (star in listOf(GalaxyScene.STAR_RADIUS_MIN, 3.5f, GalaxyScene.STAR_RADIUS_MAX)) {
            val albums = (1..12).map { album("Album $it", 1990 + it, 6 + it) }
            val sys = LocalSystems.build("artist:x", albums, starRadius = star)!!
            for (k in 1 until sys.planets.size) {
                val inner = sys.planets[k - 1]
                val outer = sys.planets[k]
                assertTrue("moons cross at star $star", inner.orbit + inner.reach < outer.orbit - outer.reach)
            }
            val last = sys.planets.last()
            assertTrue("the belt swallows a moon at star $star", last.orbit + last.reach < sys.beltInner)
            // The camera frames the whole system, belt and all, before it arrives.
            assertTrue(sys.reach <= PlanetSystem.reachFor(star, SystemLimits.LIBRARY) + 1e-3f)
        }
    }

    @Test
    fun `a ring sits between its planet and its first moon`() {
        val planet = LocalSystems.build("artist:x", listOf(album("Long", 2000, 20)))!!.planets.single()
        val firstMoon = planet.moons.minOf { it.orbit }
        assertTrue(planet.radius * RING_OUTER_RADII < firstMoon)
    }

    @Test
    fun `a longer album is a bigger planet, never bigger than its star allows`() {
        val sys = LocalSystems.build("artist:x", listOf(album("EP", 2000, 4), album("Double", 2001, 30)))!!
        val ep = sys.planets.first { it.label == "EP" }
        val double = sys.planets.first { it.label == "Double" }
        assertTrue(double.radius > ep.radius)
        assertTrue(double.radius <= sys.starRadius / 8f + 1e-4f)
    }

    @Test
    fun `a rescan leaves the system as it was`() {
        val a = listOf(album("A", 1990, 10), album("B", 1995, 7))
        nextId = 1000
        val b = listOf(album("A", 1990, 10), album("B", 1995, 7))
        val one = LocalSystems.build("artist:x", a)!!
        val two = LocalSystems.build("artist:x", b)!!
        for (p in one.planets.indices) {
            assertEquals(one.planets[p].phase, two.planets[p].phase, 0f)
            assertEquals(one.planets[p].hue, two.planets[p].hue, 0f)
            assertEquals(one.planets[p].moons.map { it.phase }, two.planets[p].moons.map { it.phase })
        }
        val spot = FloatArray(3)
        one.moonPosition(0, 0, 12.5f, 0f, 0f, 0f, spot)
        val again = FloatArray(3)
        two.moonPosition(0, 0, 12.5f, 0f, 0f, 0f, again)
        assertTrue(hypot(spot[0] - again[0], spot[2] - again[2]) < 1e-5f)
    }

    @Test
    fun `a planet's size is its album's running time, not its count of tracks`() {
        // Six long movements outweigh twenty short tracks.
        val sys = LocalSystems.build(
            "artist:x",
            listOf(album("Suite", 2000, 6, trackSeconds = 720), album("Shorts", 2001, 20, trackSeconds = 90)),
        )!!
        val suite = sys.planets.first { it.label == "Suite" }
        val shorts = sys.planets.first { it.label == "Shorts" }
        assertTrue(suite.radius > shorts.radius)
        // A full CD is as big as a planet gets; longer is no bigger.
        assertEquals(1f, LocalSystems.lengthShare(80 * 60), 1e-6f)
        assertEquals(1f, LocalSystems.lengthShare(140 * 60), 0f)
        assertEquals(0f, LocalSystems.lengthShare(0), 0f)
        assertTrue(LocalSystems.lengthShare(20 * 60) < LocalSystems.lengthShare(40 * 60))
    }

    @Test
    fun `with no track lengths known, size falls back to the track count`() {
        val sys = LocalSystems.build("artist:x", listOf(album("EP", 2000, 4, trackSeconds = 0), album("LP", 2001, 14, trackSeconds = 0)))!!
        assertTrue(sys.planets.first { it.label == "LP" }.radius > sys.planets.first { it.label == "EP" }.radius)
    }

    @Test
    fun `a key colours a planet round the Camelot wheel, minor deeper and major lighter`() {
        // 8A (A minor) and 8B (C major) are relatives: one colour, two tones.
        val sys = LocalSystems.build(
            "artist:x",
            listOf(album("Minor", 2000, 8, key = 8), album("Major", 2001, 8, key = 20), album("Next", 2002, 8, key = 9)),
        )!!
        val minor = sys.planets.first { it.label == "Minor" }
        val major = sys.planets.first { it.label == "Major" }
        val next = sys.planets.first { it.label == "Next" }
        assertEquals(210f, minor.hue, 0f)
        assertEquals(210f, major.hue, 0f)
        assertEquals(240f, next.hue, 0f)
        assertEquals(-1f, minor.tone, 0f)
        assertEquals(1f, major.tone, 0f)
        for (code in 1..24) assertTrue(LocalSystems.keyHue(code) in 0f..330f)
        assertEquals(0f, LocalSystems.keyHue(1), 0f)
        assertEquals(330f, LocalSystems.keyHue(24), 0f)
    }

    @Test
    fun `loudness is haze, tempo is spin, dynamic range is shadow`() {
        val sys = LocalSystems.build("artist:x", listOf(album("Loud", 2000, 8, bpm = 128f, lufs = -6f, dr = 4f)))!!
        val p = sys.planets.single()
        assertEquals(1f, p.haze, 0f)
        assertEquals(15f, p.spinPeriod, 1e-4f)
        assertEquals(0.55f, p.shade, 1e-6f)

        assertEquals(0f, LocalSystems.hazeOf(-23f), 0f)
        assertEquals(0.5f, LocalSystems.hazeOf(-13f), 1e-6f)
        assertEquals(1f, LocalSystems.hazeOf(-2f), 0f)
        assertEquals(16f, LocalSystems.spinPeriodOf(120f), 1e-4f)
        assertEquals(0f, LocalSystems.spinPeriodOf(0f), 0f)
        assertEquals(0f, LocalSystems.spinPeriodOf(Float.NaN), 0f)
        assertEquals(1f, LocalSystems.shadeOf(20f), 0f)
        assertEquals(0.775f, LocalSystems.shadeOf(10f), 1e-6f)
    }

    @Test
    fun `an album without the values looks as every planet did`() {
        val plain = LocalSystems.build("artist:x", listOf(album("Plain", 2000, 8)))!!.planets.single()
        assertEquals(PlanetSystem.unit(PlanetSystem.stableHash(plain.key) ushr 16) * 360f, plain.hue, 0f)
        assertEquals(0f, plain.tone, 0f)
        assertEquals(0f, plain.haze, 0f)
        assertEquals(0f, plain.spinPeriod, 0f)
        assertEquals(1f, plain.shade, 0f)
        // A stored key outside the wheel is no key at all.
        val odd = LocalSystems.build("artist:x", listOf(album("Odd", 2000, 8, key = 99)))!!.planets.single()
        assertEquals(0f, odd.tone, 0f)
        assertEquals(PlanetSystem.unit(PlanetSystem.stableHash(odd.key) ushr 16) * 360f, odd.hue, 0f)
    }

    @Test
    fun `a planet keeps its colour, haze, spin and shadow at any body size`() {
        val sys = LocalSystems.build("artist:x", listOf(album("Loud", 2000, 8, bpm = 128f, key = 8, lufs = -9f, dr = 7f)))!!
        val before = sys.planets.single()
        for (resized in listOf(sys.withBodySize(2f), sys.withBodySize(0.5f), sys.sizedBy(mapOf("Loud" to 40)))) {
            val after = resized.planets.single()
            assertEquals(before.hue, after.hue, 0f)
            assertEquals(before.tone, after.tone, 0f)
            assertEquals(before.haze, after.haze, 0f)
            assertEquals(before.spinPeriod, after.spinPeriod, 0f)
            assertEquals(before.shade, after.shade, 0f)
        }
    }

    @Test
    fun `belt albums scale with the planets, so the sheet sizes them alike`() {
        val albums = (1..10).map { album("Album $it", 1990 + it, 10, plays = 10 - it) }
        val sys = LocalSystems.build("artist:x", albums)!!
        val big = sys.withBodySize(2.5f)
        assertEquals(2, sys.belt.size)
        for (b in sys.belt.indices) assertEquals(sys.belt[b].radius * 2.5f, big.belt[b].radius, 1e-4f)
        assertEquals(sys.belt.map { it.label }, big.belt.map { it.label })
    }

    /** One album whose tracks have the given tempos and keys, in running order. */
    private fun albumOfTracks(vararg tracks: Pair<Float?, Int?>) = LibraryAlbum(
        key = "tracks|artist|2000",
        title = "Tracks",
        year = 2000,
        artworkUri = null,
        tracks = tracks.mapIndexed { n, (bpm, key) ->
            LibraryTrack(
                id = nextId++, key = "/music/tracks/$n.flac", title = "Track $n", discNumber = 1,
                trackNumber = n + 1, durationSeconds = 200, bpm = bpm, camelotKey = key,
            )
        },
    )

    @Test
    fun `a moon goes round once every 16 beats of its track, outer ones a little slower`() {
        val moons = LocalSystems.build("artist:x", listOf(albumOfTracks(120f to null, 240f to null, null to null)))!!
            .planets.single().moons
        assertEquals(8f, moons[0].period, 1e-4f)                                // 16 beats at 120 BPM, innermost
        assertEquals(4f * 1.55f, moons[1].period, 1e-4f)                        // twice the tempo, one slot out
        assertEquals(PlanetSystem.MOON_PERIOD * 2.1f, moons[2].period, 1e-4f)   // no tempo: as every moon was
        assertEquals(PlanetSystem.MOON_PERIOD, LocalSystems.moonPeriodOf(0, null), 0f)
        assertEquals(PlanetSystem.MOON_PERIOD, LocalSystems.moonPeriodOf(0, Float.NaN), 0f)
        assertEquals(PlanetSystem.MOON_PERIOD, LocalSystems.moonPeriodOf(0, 0f), 0f)
    }

    @Test
    fun `a moon glows in its track's key, or its star's light without one`() {
        val moons = LocalSystems.build("artist:x", listOf(albumOfTracks(null to 8, null to 20, null to null, null to 77)))!!
            .planets.single().moons
        assertEquals(210f, moons[0].keyHue, 0f)
        assertEquals(-1f, moons[0].tone, 0f)
        assertEquals(210f, moons[1].keyHue, 0f)
        assertEquals(1f, moons[1].tone, 0f)
        for (plain in listOf(moons[2], moons[3])) {
            assertEquals(PlanetSystem.NO_KEY_HUE, plain.keyHue, 0f)
            assertEquals(0f, plain.tone, 0f)
            assertTrue(!plain.hasKeyHue)
        }
    }

    @Test
    fun `a moon keeps its key and its pace at any body size`() {
        val sys = LocalSystems.build("artist:x", listOf(albumOfTracks(128f to 8, 90f to 20)))!!
        val before = sys.planets.single().moons
        for (resized in listOf(sys.withBodySize(2f), sys.withBodySize(0.5f))) {
            val after = resized.planets.single().moons
            assertEquals(before.map { it.keyHue }, after.map { it.keyHue })
            assertEquals(before.map { it.tone }, after.map { it.tone })
            assertEquals(before.map { it.period }, after.map { it.period })
        }
    }

    @Test
    fun `a moon and a ring track are labelled by their label, and matched by their title`() {
        val raw = { n: Int -> "%02d Bioweapon, Audiofreq, Code Black - Song %d".format(n, n) }
        val count = SystemLimits.LIBRARY.maxMoons + 1
        val album = LibraryAlbum(
            key = "time capsule|bioweapon|2025", title = "Time Capsule", year = 2025, artworkUri = null,
            tracks = (1..count).map { n ->
                LibraryTrack(
                    id = nextId++, key = "/music/tc/$n.flac", title = raw(n), discNumber = 1,
                    trackNumber = n, durationSeconds = 200, label = "Song $n",
                )
            },
        )
        val planet = LocalSystems.build("artist:bioweapon", listOf(album))!!.planets.single()
        val moon = planet.moons.first()
        assertEquals("Song 1", moon.label)
        assertEquals(raw(1), moon.title)
        val ring = planet.ring.single() as MoonRef.Local
        assertEquals("Song $count", ring.label)
        assertEquals(raw(count), ring.title)
    }

    private companion object {
        /** The ring's outer edge, in its planet's radii: as the map draws it. */
        const val RING_OUTER_RADII = 1.9f
    }
}
