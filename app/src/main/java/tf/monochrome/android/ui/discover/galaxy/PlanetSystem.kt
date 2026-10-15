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
 * A star's planets and their moons: for a genre, its most popular artists
 * with their charted tracks as moons ([from]); for an artist in the library,
 * their albums with the tracks on them ([LocalSystems]).
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
    /** The star this system belongs to: its id in the [StarField]. */
    val starId: String,
    val planets: List<Planet>,
    /**
     * The star's own radius, scene units — the genre's size ([GalaxyScene.starRadius]).
     * Everything in the system is laid out from it: the planets are a few
     * hundredths of it across and the orbits a few of it out, so a big
     * genre is a big system round a giant, and a small one a tight system
     * round a dwarf.
     */
    val starRadius: Float = DEFAULT_STAR_RADIUS,
    /**
     * The listener's planet and moon size, as a share of the usual, already
     * in every radius and moon orbit here ([withBodySize]). Kept so what is
     * drawn from it — the least speck a far planet is drawn at — scales the
     * same way.
     */
    val bodyScale: Float = 1f,
    /** How many planets and moons a system of this kind can have, which its spacing is laid out for. */
    val limits: SystemLimits = SystemLimits.CHART,
    /**
     * Planets past [SystemLimits.maxPlanets]: not in orbit, drawn as an
     * asteroid belt beyond the last one, and listed in full in the system's
     * sheet. Their orbits mean nothing.
     */
    val belt: List<Planet> = emptyList(),
) {
    val beltCount: Int get() = belt.size


    class Planet(
        /** What the planet is called on the map: an artist, or an album. */
        val label: String,
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
        /** Its colour, 0..360: a planet with no artwork, and its atmosphere. */
        val hue: Float,
        val artworkUrl: String?,
        val moons: List<Moon>,
        /** What it is keyed by, for its look: its name, or an album's grouping key. */
        val key: String = label,
        /** How much it is being played lately, 0..1: drawn as a brighter atmosphere, never a move. */
        val glow: Float = 0f,
        /** Tracks past [SystemLimits.maxMoons]: drawn as a ring round the planet, listed after its moons. */
        val ring: List<MoonRef> = emptyList(),
        /** A line under its name in the system's list, when it has one: an album's year. */
        val subtitle: String? = null,
        /**
         * Minor or major, for its atmosphere: -1 for a minor key, drawn deeper,
         * 1 for a major one, drawn lighter, 0 for none known. An album's key.
         */
        val tone: Float = 0f,
        /** How loud it was mastered, 0..1: a wider haze round its atmosphere. An album's LUFS. */
        val haze: Float = 0f,
        /** Seconds for its cover to turn once, 0 for still: one turn per 32 beats of an album's tempo. */
        val spinPeriod: Float = 0f,
        /**
         * How deep its night side's shadow is, a share of the full depth, 0..1.
         * 1 is the depth every planet had; an album mastered flat (little
         * dynamic range) is lit flatter, down to 0.55.
         */
        val shade: Float = 1f,
    ) {
        val ringCount: Int get() = ring.size

        /** The outermost moon's orbit, or the planet's own size without moons. */
        val reach: Float get() = moons.maxOfOrNull { it.orbit } ?: radius

        /**
         * This planet at the size of a catalogue of [count] releases, round a
         * star of [starRadius], at the listener's [bodyScale].
         */
        internal fun sizedFor(count: Int?, starRadius: Float, bodyScale: Float = 1f): Planet {
            val r = planetRadius(count, starRadius) * bodyScale
            val k = r / radius
            return copy(releases = count, radius = r, moons = moons.map { it.copy(radius = it.radius * k) })
        }

        /** This planet and its moons [k] times the size, the moons' orbits widened with them. */
        internal fun scaled(k: Float): Planet =
            copy(radius = radius * k, moons = moons.map { it.copy(orbit = it.orbit * k, radius = it.radius * k) })

        private fun copy(releases: Int? = this.releases, radius: Float = this.radius, moons: List<Moon> = this.moons) = Planet(
            label, share, releases, orbit, period, phase, tilt, node, radius, hue, artworkUrl, moons,
            key, glow, ring, subtitle, tone, haze, spinPeriod, shade,
        )
    }

    class Moon(
        /** The track it is: what a tap on it plays. */
        val ref: MoonRef,
        val orbit: Float,
        val period: Float,
        val phase: Float,
        val tilt: Float,
        val node: Float,
        val radius: Float,
        /**
         * Its glow's colour, 0..360, from its track's key; [NO_KEY_HUE] for
         * none, which glows in its star's light as every moon did.
         */
        val keyHue: Float = NO_KEY_HUE,
        /** -1 a minor key (deeper), 1 a major one (paler), 0 none: as a planet's [Planet.tone]. */
        val tone: Float = 0f,
    ) {
        /** [MoonRef.title]: what it is matched by, not what it is labelled. */
        val title: String get() = ref.title

        /** What it is labelled: [MoonRef.label]. */
        val label: String get() = ref.label

        /** Whether its glow takes its own key's colour rather than its star's light. */
        val hasKeyHue: Boolean get() = keyHue >= 0f

        internal fun copy(orbit: Float = this.orbit, radius: Float = this.radius) =
            Moon(ref, orbit, period, phase, tilt, node, radius, keyHue, tone)
    }

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

    /**
     * The furthest anything in the system goes from the star: the last
     * planet's orbit and its moons, or the belt beyond them.
     */
    val reach: Float
        get() {
            val bodies = planets.maxOfOrNull { it.orbit + it.reach } ?: starRadius
            return if (beltCount > 0) maxOf(bodies, beltOuter(starRadius, limits)) else bodies
        }

    /** Where the asteroid belt runs, from and to, in scene units from the star. */
    val beltInner: Float get() = beltInner(starRadius, limits)
    val beltOuter: Float get() = beltOuter(starRadius, limits)

    /**
     * The same system with each planet the size of its artist's catalogue
     * ([releases], by artist name as the planets carry it). Nothing moves:
     * an orbit is how hot the artist is, and a moon's orbit is laid out for
     * the biggest planet the star could have, so only the worlds themselves
     * grow or shrink as the counts come in.
     */
    fun sizedBy(releases: Map<String, Int>): PlanetSystem {
        if (planets.none { releases[it.label] != it.releases }) return this
        return PlanetSystem(
            starId,
            planets.map { it.sizedFor(releases[it.label] ?: it.releases, starRadius, bodyScale) },
            starRadius,
            bodyScale,
            limits,
            belt,
        )
    }

    /**
     * The same system with its planets and moons at the listener's [size], a
     * share of the usual. The moons' orbits widen with them, so a bigger
     * planet never swallows its own moons; the planets' orbits stay where
     * their artists' heat put them, and the star is its genre's size still.
     */
    fun withBodySize(size: Float): PlanetSystem {
        val k = size / bodyScale
        if (k == 1f || !k.isFinite() || k <= 0f) return this
        // The belt's albums too: drawn as grains, but listed in the sheet at
        // their size against the star at this scale, as the planets are.
        return PlanetSystem(starId, planets.map { it.scaled(k) }, starRadius, size, limits, belt.map { it.scaled(k) })
    }

    companion object {
        /** A chart's system at most: [SystemLimits.CHART]. */
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
        fun reachFor(starRadius: Float, limits: SystemLimits = SystemLimits.CHART): Float =
            if (limits.belt) beltOuter(starRadius, limits)
            else lastOrbit(starRadius, limits) + moonOrbit(starRadius, limits.maxMoons - 1)

        /** The outermost a planet's orbit is laid out to, round a star of [starRadius]. */
        internal fun lastOrbit(starRadius: Float, limits: SystemLimits = SystemLimits.CHART): Float =
            firstOrbit(starRadius) + (limits.maxPlanets - 1) * orbitSpacing(starRadius, limits)

        /**
         * The even step between orbits a system of [limits] is laid out on:
         * the usual step, or — where moons are kept wholly apart — at least
         * two moon systems' width.
         */
        internal fun orbitSpacing(starRadius: Float, limits: SystemLimits): Float =
            if (limits.apartMoons) maxOf(orbitStep(starRadius), moonOrbit(starRadius, limits.maxMoons - 1) * APART_MARGIN)
            else orbitStep(starRadius)

        /** Two moon systems' width, and a tenth more, between neighbouring orbits. */
        private const val APART_MARGIN = 2.2f

        /** The asteroid belt starts a moon system's width past the last orbit, so no moon reaches it. */
        internal fun beltInner(starRadius: Float, limits: SystemLimits): Float =
            lastOrbit(starRadius, limits) + minOrbitGap(starRadius, limits)

        internal fun beltOuter(starRadius: Float, limits: SystemLimits): Float =
            beltInner(starRadius, limits) + orbitStep(starRadius) * BELT_WIDTH_STEPS

        /** How wide the belt is, in orbit steps. */
        private const val BELT_WIDTH_STEPS = 1.2f

        /**
         * Moon [m]'s orbit round any planet of a star of [starRadius]: laid out
         * for the biggest planet it could have, so it is clear of every one,
         * and stays put when a planet's size comes in late.
         */
        internal fun moonOrbit(starRadius: Float, m: Int): Float = largestPlanet(starRadius) * (2.2f + 1.1f * m)

        /** The least gap between two planets' orbits: their moons never cross. */
        internal fun minOrbitGap(starRadius: Float, limits: SystemLimits = SystemLimits.CHART): Float =
            moonOrbit(starRadius, limits.maxMoons - 1) * 1.7f

        /**
         * Each planet's orbit from how hot its artist is ([heat], hottest
         * first, 0..1): the hottest on the first orbit, a planet with none on
         * the last, and between them in proportion — then pushed apart where
         * two are too close for their moons, and pulled back in from the last
         * orbit where that ran a cold one off the end.
         */
        internal fun heatOrbits(heat: List<Float>, starRadius: Float, limits: SystemLimits = SystemLimits.CHART): FloatArray {
            val first = firstOrbit(starRadius)
            val last = lastOrbit(starRadius, limits)
            val gap = minOrbitGap(starRadius, limits)
            val out = FloatArray(heat.size) { first + (1f - heat[it].coerceIn(0f, 1f)) * (last - first) }
            for (k in 1 until out.size) out[k] = maxOf(out[k], out[k - 1] + gap)
            if (out.isNotEmpty() && out.last() > last) {
                out[out.size - 1] = last
                for (k in out.size - 2 downTo 0) out[k] = minOf(out[k], out[k + 1] - gap)
            }
            return out
        }

        /** The nearest planet's year, in seconds; further out is slower, as Kepler has it. */
        internal const val FIRST_PERIOD = 26f
        internal const val MOON_PERIOD = 7f

        /** A moon's [Moon.keyHue] when its track has no key. */
        const val NO_KEY_HUE = -1f

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
                    label = name,
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
                            ref = MoonRef.Chart(entry),
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

        internal const val TAU = 6.2831855f

        /** A hash that is the same on every run, unlike nothing else to hand. */
        internal fun stableHash(text: String): Int {
            var h = 0x811C9DC5.toInt()
            for (c in text) {
                h = h xor c.code
                h *= 0x01000193
            }
            return h
        }

        internal fun unit(bits: Int): Float = (bits and 0xFF) / 255f

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

/**
 * How many planets and moons a system can have. The spacing is laid out for
 * the most it could hold: the least gap between orbits clears the outermost
 * moon, so neighbouring planets' moons never cross whatever the counts.
 */
data class SystemLimits(
    val maxPlanets: Int,
    val maxMoons: Int,
    /** Whether planets past [maxPlanets] go on as an asteroid belt, rather than not at all. */
    val belt: Boolean,
    /**
     * Whether neighbouring planets' moons are kept wholly apart — their orbits
     * at least both moon systems' width from each other — rather than only
     * the next planet clear of the last one's moons.
     */
    val apartMoons: Boolean = false,
) {
    companion object {
        /** A genre's chart: its six hottest artists, four tracks each. */
        val CHART = SystemLimits(PlanetSystem.MAX_PLANETS, PlanetSystem.MAX_MOONS, belt = false)

        /** An artist in the library: eight albums and six tracks each, the rest as a belt and rings. */
        val LIBRARY = SystemLimits(maxPlanets = 8, maxMoons = 6, belt = true, apartMoons = true)

        /** The most of either, which a drawing sizes its slots for. */
        val LARGEST = SystemLimits(
            maxOf(CHART.maxPlanets, LIBRARY.maxPlanets),
            maxOf(CHART.maxMoons, LIBRARY.maxMoons),
            belt = true,
        )
    }
}

/** What a moon is: the track it plays. */
sealed interface MoonRef {
    /**
     * The track's own title, as its chart or library has it: what a
     * library moon is matched to the track playing by.
     */
    val title: String

    /** What its moon is labelled, on the map and in the system's sheet. */
    val label: String get() = title

    /** A track off a genre's chart, matched to a catalogue when tapped. */
    class Chart(val entry: ChartEntry) : MoonRef {
        override val title: String get() = entry.title
    }

    /** A file in the library: [trackId] is its `local_tracks` row, [key] its path. */
    class Local(
        val trackId: Long,
        val key: String,
        override val title: String,
        override val label: String = title,
    ) : MoonRef
}

/** How far from a star the camera comes to rest, at the least, in scene units. */
const val GALAXY_ARRIVE_DISTANCE = 40f
