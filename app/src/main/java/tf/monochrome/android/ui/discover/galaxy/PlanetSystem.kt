package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.isLastFmPlaceholder
import tf.monochrome.android.data.charts.normalizeForMatch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The selected genre's planets: its most popular artists, each with its
 * charted tracks as moons.
 *
 * Built from the genre's own chart, so a planet is an artist the outside
 * world actually plays under that genre and a moon is a record you can tap
 * and hear. The most popular artist orbits nearest the star and is the
 * biggest; the order outwards is the chart's.
 *
 * Positions are a function of time and nothing else — no state, no
 * simulation — so the system looks the same every visit (phases come from
 * the names) and stands still when motion is reduced, because the clock does.
 * Sizes and distances are in scene units, the galaxy's own, around a star the
 * camera comes to rest [GALAXY_ARRIVE_DISTANCE] from.
 */
class PlanetSystem(val genreId: String, val planets: List<Planet>) {

    class Planet(
        val artist: String,
        /** Popularity against the system's most popular artist, 0..1. */
        val share: Float,
        val orbit: Float,
        val period: Float,
        val phase: Float,
        /** How far the orbit's plane leans from the disc, radians. */
        val tilt: Float,
        val radius: Float,
        /** Hue for a planet with no artwork, 0..360. */
        val hue: Float,
        val artworkUrl: String?,
        val moons: List<Moon>,
    ) {
        /** The outermost moon's orbit, or the planet's own size without moons. */
        val reach: Float get() = moons.maxOfOrNull { it.orbit } ?: radius
    }

    class Moon(
        val entry: ChartEntry,
        val orbit: Float,
        val period: Float,
        val phase: Float,
        val tilt: Float,
        val radius: Float,
    )

    /** Planet [p] at [time] s, around a star at ([cx], [cy], [cz]), written to [out] at [at]. */
    fun planetPosition(p: Int, time: Float, cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int = 0, spread: Float = 1f) {
        val planet = planets[p]
        orbitPoint(planet.orbit * spread, angle(planet.phase, planet.period, time), planet.tilt, cx, cy, cz, out, at)
    }

    /** The point at [angle] radians round planet [p]'s orbit: for drawing the orbit itself. */
    fun orbitPoint(p: Int, angle: Float, cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int = 0, spread: Float = 1f) {
        val planet = planets[p]
        orbitPoint(planet.orbit * spread, angle, planet.tilt, cx, cy, cz, out, at)
    }

    /** Moon [m] of planet [p] at [time] s, around a star at ([cx], [cy], [cz]). */
    fun moonPosition(p: Int, m: Int, time: Float, cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int = 0, spread: Float = 1f) {
        planetPosition(p, time, cx, cy, cz, out, at, spread)
        val moon = planets[p].moons[m]
        orbitPoint(moon.orbit * spread, angle(moon.phase, moon.period, time), moon.tilt, out[at], out[at + 1], out[at + 2], out, at)
    }

    /** The outermost planet's orbit. */
    val outerOrbit: Float get() = planets.maxOfOrNull { it.orbit } ?: 0f

    companion object {
        const val MAX_PLANETS = 6
        const val MAX_MOONS = 4

        /** The nearest planet's orbit, and the step out to each next one. */
        const val FIRST_ORBIT = 16f
        const val ORBIT_STEP = 8f

        /** The widest a system can be: the last planet's orbit plus its moons. */
        const val MAX_REACH = FIRST_ORBIT + (MAX_PLANETS - 1) * ORBIT_STEP + 11.5f

        /** The nearest planet's year, in seconds; further out is slower, as Kepler has it. */
        private const val FIRST_PERIOD = 26f
        private const val MOON_PERIOD = 7f

        /**
         * A system from a genre's chart, or null when it has no artists.
         *
         * Popularity is the listens the chart reports, summed per artist; a
         * chart without counts falls back on its ranks, so the order is still
         * the chart's. Artists are matched on their normalised names, which is
         * what keeps "Beyoncé" and "Beyonce" one planet.
         */
        fun from(genreId: String, entries: List<ChartEntry>): PlanetSystem? {
            if (entries.isEmpty()) return null
            val counted = entries.any { it.listenCount > 0 }
            val byArtist = LinkedHashMap<String, MutableList<ChartEntry>>()
            for (entry in entries.sortedBy { it.rank }) {
                val key = normalizeForMatch(entry.artistName).ifBlank { entry.artistName.lowercase() }
                if (key.isBlank()) continue
                byArtist.getOrPut(key) { mutableListOf() }.add(entry)
            }
            fun weight(e: ChartEntry): Double = if (counted) e.listenCount.toDouble() else 1.0 / e.rank.coerceAtLeast(1)
            val ranked = byArtist.values
                .map { tracks -> tracks to tracks.sumOf(::weight) }
                .sortedWith(compareByDescending<Pair<List<ChartEntry>, Double>> { it.second }.thenBy { it.first.first().rank })
                .take(MAX_PLANETS)
            val top = ranked.firstOrNull()?.second?.takeIf { it > 0.0 } ?: return null

            val planets = ranked.mapIndexed { k, (tracks, score) ->
                val name = tracks.first().artistName
                val seed = stableHash(name)
                val share = (score / top).toFloat().coerceIn(0f, 1f)
                val radius = 2f + 2.2f * sqrt(share)
                val orbit = FIRST_ORBIT + k * ORBIT_STEP
                val moonTracks = tracks.take(MAX_MOONS)
                val moonTop = moonTracks.maxOf(::weight).coerceAtLeast(1e-9)
                Planet(
                    artist = name,
                    share = share,
                    orbit = orbit,
                    period = FIRST_PERIOD * (orbit / FIRST_ORBIT).pow(1.5f),
                    phase = unit(seed) * TAU,
                    tilt = (unit(seed ushr 8) - 0.5f) * 0.5f,
                    radius = radius,
                    hue = unit(seed ushr 16) * 360f,
                    // Charts cached before the placeholder was filtered still
                    // carry it, so it is passed over here too.
                    artworkUrl = tracks.firstNotNullOfOrNull { e ->
                        e.artworkUrl?.takeIf { it.isNotBlank() && !isLastFmPlaceholder(it) }
                    },
                    moons = moonTracks.mapIndexed { m, entry ->
                        val moonSeed = stableHash(entry.title + name)
                        Moon(
                            entry = entry,
                            orbit = radius * 1.6f + 1.2f + m * 1.1f,
                            period = MOON_PERIOD * (1f + m * 0.55f),
                            phase = unit(moonSeed) * TAU,
                            tilt = (unit(moonSeed ushr 8) - 0.5f) * 1.1f,
                            radius = 0.45f + 0.35f * sqrt((weight(entry) / moonTop).toFloat()),
                        )
                    },
                )
            }
            return PlanetSystem(genreId, planets)
        }

        private const val TAU = 6.2831855f

        /** A hash that is the same on every run, unlike nothing else to hand. */
        private fun stableHash(text: String): Int {
            var h = 0x811C9DC5.toInt()
            for (c in text) {
                h = h xor c.code
                h *= 0x01000193
            }
            return h
        }

        private fun unit(bits: Int): Float = (bits and 0xFF) / 255f

        private fun angle(phase: Float, period: Float, time: Float): Float = phase + TAU * (time / period % 1f)

        private fun orbitPoint(
            r: Float, a: Float, tilt: Float,
            cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int,
        ) {
            val x = cos(a) * r
            val d = sin(a) * r
            out[at] = cx + x
            out[at + 1] = cy + d * sin(tilt)
            out[at + 2] = cz + d * cos(tilt)
        }
    }
}

/** How far from a star the camera comes to rest, at the least, in scene units. */
const val GALAXY_ARRIVE_DISTANCE = 230f
