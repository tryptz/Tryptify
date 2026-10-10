package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.isLastFmPlaceholder
import tf.monochrome.android.data.charts.normalizeForMatch
import kotlin.math.cos
import kotlin.math.ln
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
class PlanetSystem(
    val genreId: String,
    val planets: List<Planet>,
    /**
     * The star's own radius, scene units — the genre's size ([GalaxyScene.starRadius]).
     * Everything in the system is laid out from it: the planets are a few
     * hundredths of it across and the orbits a few of it out, so a big
     * genre is a big system round a giant, and a small one a tight system
     * round a dwarf.
     */
    val starRadius: Float = DEFAULT_STAR_RADIUS,
) {

    class Planet(
        val artist: String,
        /**
         * How hot the artist is right now — their listens in the chart's
         * window (the last week) against the hottest artist's, 0..1. It is
         * how close they orbit: the hottest is nearest the star.
         */
        val share: Float,
        /** How many releases the artist has, when known: the size of their planet. */
        val releases: Int?,
        val orbit: Float,
        val period: Float,
        val phase: Float,
        /** How far the orbit's plane leans from the disc, radians. */
        val tilt: Float,
        /**
         * Which way it leans: the angle round the star of the line the orbit
         * tilts about. Each planet's is its own, so the orbits cross one
         * another at angles instead of nesting as rings.
         */
        val node: Float,
        val radius: Float,
        /** Hue for a planet with no artwork, 0..360. */
        val hue: Float,
        val artworkUrl: String?,
        val moons: List<Moon>,
    ) {
        /** The outermost moon's orbit, or the planet's own size without moons. */
        val reach: Float get() = moons.maxOfOrNull { it.orbit } ?: radius

        /** This planet at the size of a catalogue of [count] releases, round a star of [starRadius]. */
        internal fun sizedFor(count: Int?, starRadius: Float): Planet {
            val r = planetRadius(count, starRadius)
            val k = r / radius
            return Planet(
                artist, share, count, orbit, period, phase, tilt, node, r, hue, artworkUrl,
                moons.map { Moon(it.entry, it.orbit, it.period, it.phase, it.tilt, it.node, it.radius * k) },
            )
        }
    }

    class Moon(
        val entry: ChartEntry,
        val orbit: Float,
        val period: Float,
        val phase: Float,
        val tilt: Float,
        val node: Float,
        val radius: Float,
    )

    /** Planet [p] at [time] s, around a star at ([cx], [cy], [cz]), written to [out] at [at]. */
    fun planetPosition(p: Int, time: Float, cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int = 0, spread: Float = 1f) {
        val planet = planets[p]
        orbitPoint(planet.orbit * spread, angle(planet.phase, planet.period, time), planet.tilt, planet.node, cx, cy, cz, out, at)
    }

    /** The point at [angle] radians round planet [p]'s orbit: for drawing the orbit itself. */
    fun orbitPoint(p: Int, angle: Float, cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int = 0, spread: Float = 1f) {
        val planet = planets[p]
        orbitPoint(planet.orbit * spread, angle, planet.tilt, planet.node, cx, cy, cz, out, at)
    }

    /** Moon [m] of planet [p] at [time] s, around a star at ([cx], [cy], [cz]). */
    fun moonPosition(p: Int, m: Int, time: Float, cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int = 0, spread: Float = 1f) {
        planetPosition(p, time, cx, cy, cz, out, at, spread)
        val moon = planets[p].moons[m]
        orbitPoint(moon.orbit * spread, angle(moon.phase, moon.period, time), moon.tilt, moon.node, out[at], out[at + 1], out[at + 2], out, at)
    }

    /** The outermost planet's orbit. */
    val outerOrbit: Float get() = planets.maxOfOrNull { it.orbit } ?: 0f

    /** The furthest anything in the system goes from the star: the last planet's orbit and its moons. */
    val reach: Float get() = planets.maxOfOrNull { it.orbit + it.reach } ?: starRadius

    /**
     * The same system with each planet the size of its artist's catalogue
     * ([releases], by artist name as the planets carry it). Nothing moves:
     * an orbit is how hot the artist is, and a moon's orbit is laid out for
     * the biggest planet the star could have, so only the worlds themselves
     * grow or shrink as the counts come in.
     */
    fun sizedBy(releases: Map<String, Int>): PlanetSystem {
        if (planets.none { releases[it.artist] != it.releases }) return this
        return PlanetSystem(genreId, planets.map { it.sizedFor(releases[it.artist] ?: it.releases, starRadius) }, starRadius)
    }

    companion object {
        const val MAX_PLANETS = 6
        const val MAX_MOONS = 4

        /** A star of middling size, scene units: what a system is laid out round when none is given. */
        const val DEFAULT_STAR_RADIUS = 3.5f

        /**
         * A planet's radius as a share of its star's, from the smallest
         * catalogue to the largest. Real planets are smaller still
         * (Jupiter is a tenth of the Sun), but at a hundredth they would be
         * nothing on a phone until the camera was on top of them; a
         * twenty-fifth to an eighth keeps the star plainly the giant and the
         * planets plainly worlds.
         */
        const val PLANET_MIN_SHARE = 0.04f
        const val PLANET_SPAN_SHARE = 0.08f

        /**
         * A catalogue of this many releases makes a planet as big as a planet
         * gets; sizes run on a log scale below it, so a debut EP is a small
         * world and a fifty-year career a giant without dwarfing everyone.
         */
        const val CATALOG_FULL = 400

        /** The size of a planet whose catalogue is not known (yet): middling. */
        const val CATALOG_UNKNOWN_SHARE = 0.35f

        /** How big a catalogue of [count] releases is, 0..1, on the log scale up to [CATALOG_FULL]. */
        fun catalogShare(count: Int?): Float {
            if (count == null) return CATALOG_UNKNOWN_SHARE
            return (ln(1f + count.coerceAtLeast(0)) / ln(1f + CATALOG_FULL)).coerceIn(0f, 1f)
        }

        /** A planet's radius round a star of [starRadius], for a catalogue of [count] releases. */
        fun planetRadius(count: Int?, starRadius: Float): Float =
            starRadius * (PLANET_MIN_SHARE + PLANET_SPAN_SHARE * catalogShare(count))

        /** The biggest a planet round a star of [starRadius] can be. */
        private fun largestPlanet(starRadius: Float): Float = starRadius * (PLANET_MIN_SHARE + PLANET_SPAN_SHARE)

        /** The nearest planet's orbit, and the step to each next one, in star radii plus a margin. */
        private const val FIRST_ORBIT_RADII = 3.2f
        private const val ORBIT_STEP_RADII = 1.5f
        private const val ORBIT_MARGIN = 2f

        /** The nearest planet's orbit round a star of [starRadius]. */
        fun firstOrbit(starRadius: Float): Float = starRadius * FIRST_ORBIT_RADII + ORBIT_MARGIN

        /** The step out from one planet's orbit to the next. */
        fun orbitStep(starRadius: Float): Float = starRadius * ORBIT_STEP_RADII + ORBIT_MARGIN * 0.75f

        /**
         * The widest a full system round a star of [starRadius] can be: the
         * last planet's orbit plus the furthest its moons go. Known before
         * the chart arrives, so the camera can frame a system it is still
         * waiting for.
         */
        fun reachFor(starRadius: Float): Float =
            firstOrbit(starRadius) + (MAX_PLANETS - 1) * orbitStep(starRadius) + moonOrbit(starRadius, MAX_MOONS - 1)

        /**
         * Moon [m]'s orbit round any planet of a star of [starRadius]: laid out
         * for the biggest planet it could have, so it is clear of every one,
         * and stays put when a planet's size comes in late.
         */
        private fun moonOrbit(starRadius: Float, m: Int): Float = largestPlanet(starRadius) * (2.2f + 1.1f * m)

        /** The least gap between two planets' orbits: their moons never cross. */
        private fun minOrbitGap(starRadius: Float): Float = moonOrbit(starRadius, MAX_MOONS - 1) * 1.7f

        /**
         * Each planet's orbit from how hot its artist is ([heat], hottest
         * first, 0..1): the hottest on the first orbit, a planet with none on
         * the last, and between them in proportion — then pushed apart where
         * two are too close for their moons, and pulled back in from the last
         * orbit where that ran a cold one off the end.
         */
        internal fun heatOrbits(heat: List<Float>, starRadius: Float): FloatArray {
            val first = firstOrbit(starRadius)
            val last = first + (MAX_PLANETS - 1) * orbitStep(starRadius)
            val gap = minOrbitGap(starRadius)
            val out = FloatArray(heat.size) { first + (1f - heat[it].coerceIn(0f, 1f)) * (last - first) }
            for (k in 1 until out.size) out[k] = maxOf(out[k], out[k - 1] + gap)
            if (out.isNotEmpty() && out.last() > last) {
                out[out.size - 1] = last
                for (k in out.size - 2 downTo 0) out[k] = minOf(out[k], out[k + 1] - gap)
            }
            return out
        }

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
        fun from(
            genreId: String,
            entries: List<ChartEntry>,
            starRadius: Float = DEFAULT_STAR_RADIUS,
            /** Each artist's catalogue size, by name as the chart spells it, where known. */
            releases: Map<String, Int> = emptyMap(),
        ): PlanetSystem? {
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
            val orbits = heatOrbits(ranked.map { (it.second / top).toFloat() }, starRadius)
            val first = firstOrbit(starRadius)

            val planets = ranked.mapIndexed { k, (tracks, score) ->
                val name = tracks.first().artistName
                val seed = stableHash(name)
                val share = (score / top).toFloat().coerceIn(0f, 1f)
                val count = releases[name]
                val radius = planetRadius(count, starRadius)
                val orbit = orbits[k]
                val moonTracks = tracks.take(MAX_MOONS)
                val moonTop = moonTracks.maxOf(::weight).coerceAtLeast(1e-9)
                Planet(
                    artist = name,
                    share = share,
                    releases = count,
                    orbit = orbit,
                    period = FIRST_PERIOD * (orbit / first).pow(1.5f),
                    phase = unit(seed) * TAU,
                    // Leaning a little, each its own way: a real system's
                    // orbits are nearly flat, and never quite in one plane.
                    tilt = (unit(seed ushr 8) - 0.5f) * 0.36f,
                    node = unit(seed ushr 24) * TAU,
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
                            orbit = moonOrbit(starRadius, m),
                            period = MOON_PERIOD * (1f + m * 0.55f),
                            phase = unit(moonSeed) * TAU,
                            tilt = (unit(moonSeed ushr 8) - 0.5f) * 1.1f,
                            node = unit(moonSeed ushr 24) * TAU,
                            // A moon is a fraction of its planet, as moons are.
                            radius = radius * (0.16f + 0.12f * sqrt((weight(entry) / moonTop).toFloat())),
                        )
                    },
                )
            }
            return PlanetSystem(genreId, planets, starRadius)
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
            r: Float, a: Float, tilt: Float, node: Float,
            cx: Float, cy: Float, cz: Float, out: FloatArray, at: Int,
        ) {
            // A circle in the disc, leant by [tilt] about the x axis, then
            // turned by [node] about the vertical: the line it leans about.
            val x = cos(a) * r
            val d = sin(a) * r
            val y = d * sin(tilt)
            val z = d * cos(tilt)
            val cn = cos(node)
            val sn = sin(node)
            out[at] = cx + x * cn - z * sn
            out[at + 1] = cy + y
            out[at + 2] = cz + x * sn + z * cn
        }
    }
}

/** How far from a star the camera comes to rest, at the least, in scene units. */
const val GALAXY_ARRIVE_DISTANCE = 40f
