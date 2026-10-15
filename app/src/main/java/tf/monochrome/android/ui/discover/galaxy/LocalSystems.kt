package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.data.local.tags.Camelot
import tf.monochrome.android.domain.model.LibraryAlbum
import tf.monochrome.android.domain.model.LibraryTrack
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * An artist's system: their albums as planets, each album's tracks as moons.
 *
 * **Release order.** The debut orbits nearest the star and each later record
 * further out, so a system reads as a career spiralling outwards, and
 * playing something never rearranges it. How much an album is being played
 * lately is its atmosphere's glow ([PlanetSystem.Planet.glow]), not its orbit.
 *
 * At most [SystemLimits.maxPlanets] albums are planets: the most played, ties
 * to the longest. The rest are the asteroid belt past the last orbit
 * ([PlanetSystem.belt]). On each planet, at most [SystemLimits.maxMoons]
 * tracks are moons — the most played, ties to the running order — laid out in
 * the album's own order; the rest are its ring ([PlanetSystem.Planet.ring]).
 * Belt and ring are still listed in full in the system's sheet.
 *
 * **A planet is its album's sound.** Its size is the album's running time;
 * its colour is its key, round the Camelot wheel, deeper for minor and lighter
 * for major; a haze widens round it the louder it was mastered; its cover
 * turns once every 32 beats of its tempo; and its night side is as deep as
 * the dynamic range the master kept. An album without a value looks as every
 * planet did before: the colour of its own key, no haze, still, full shadow.
 * A moon's size is its track's running time.
 *
 * Everything round a body (phase, tilt, the colour of an album with no key)
 * comes from its own key, so a rescan leaves the system as it was.
 */
object LocalSystems {

    fun build(
        starId: String,
        albums: List<LibraryAlbum>,
        starRadius: Float = PlanetSystem.DEFAULT_STAR_RADIUS,
        limits: SystemLimits = SystemLimits.LIBRARY,
    ): PlanetSystem? {
        val real = albums.filter { it.tracks.isNotEmpty() }
        if (real.isEmpty()) return null

        val chosen = real
            .sortedWith(
                compareByDescending<LibraryAlbum> { it.plays }
                    .thenByDescending { it.durationSeconds }
                    .thenByDescending { it.tracks.size }
                    .thenBy { it.key },
            )
            .take(limits.maxPlanets)
        val inOrbit = chosen.sortedWith(releaseOrder)
        val belt = (real - chosen.toSet()).sortedWith(releaseOrder)

        val hottest = real.maxOf { it.recentPlays }.coerceAtLeast(0)
        fun glowOf(a: LibraryAlbum) = if (hottest == 0) 0f else a.recentPlays / hottest.toFloat()

        val first = PlanetSystem.firstOrbit(starRadius)
        val step = PlanetSystem.orbitSpacing(starRadius, limits)
        val planets = inOrbit.mapIndexed { k, album ->
            planet(album, orbit = first + k * step, first, starRadius, limits, glowOf(album))
        }
        val beltOrbit = PlanetSystem.beltInner(starRadius, limits)
        val belted = belt.map { planet(it, orbit = beltOrbit, first, starRadius, limits, glowOf(it)) }
        return PlanetSystem(starId, planets, starRadius, limits = limits, belt = belted)
    }

    /** Oldest first; undated last; then by title, then key, so the order is the same every time. */
    private val releaseOrder = compareBy<LibraryAlbum>({ it.year ?: Int.MAX_VALUE }, { it.title.lowercase() }, { it.key })

    /** How long an album has to run, in minutes, to be as big as a planet gets: a full CD. */
    const val ALBUM_FULL_MINUTES = 80f

    /** How big an album of [seconds] is, 0..1, on a log scale up to [ALBUM_FULL_MINUTES]. */
    fun lengthShare(seconds: Int): Float =
        (ln(1f + seconds.coerceAtLeast(0) / 60f) / ln(1f + ALBUM_FULL_MINUTES)).coerceIn(0f, 1f)

    /** How long an album has to be, in tracks, to be as big as a planet gets, when no length is known. */
    const val ALBUM_FULL = 24

    /** How big an album of [tracks] tracks is, 0..1, on a log scale up to [ALBUM_FULL]. */
    fun albumShare(tracks: Int): Float =
        (ln(1f + tracks.coerceAtLeast(0)) / ln(1f + ALBUM_FULL)).coerceIn(0f, 1f)

    /** How big [album]'s planet is, 0..1: its running time, or its track count when no track has a length. */
    fun sizeShare(album: LibraryAlbum): Float =
        if (album.durationSeconds > 0) lengthShare(album.durationSeconds) else albumShare(album.tracks.size)

    /**
     * A key's colour, degrees: its number on the Camelot wheel, a twelfth of
     * the way round per step. Neighbours on the wheel, a fifth apart, are
     * neighbouring colours, and a key and its relative (8A, 8B) share one.
     */
    fun keyHue(camelotKey: Int): Float = (Camelot.number(camelotKey) - 1) % 12 * 30f

    /** -1 for a minor key, 1 for a major one, 0 for none known: [PlanetSystem.Planet.tone]. */
    fun keyTone(camelotKey: Int?): Float = when {
        camelotKey == null -> 0f
        Camelot.isMinor(camelotKey) -> -1f
        else -> 1f
    }

    /**
     * How hazy a master of [lufs] is, 0..1: none at -20 LUFS and quieter, a
     * full haze at -6 and louder, where a brickwalled master sits.
     */
    fun hazeOf(lufs: Float?): Float =
        lufs?.takeIf { it.isFinite() }?.let { ((it - HAZE_QUIET_LUFS) / HAZE_SPAN_LU).coerceIn(0f, 1f) } ?: 0f

    /**
     * Seconds per turn of a cover at [bpm]: once every [BEATS_PER_TURN] beats,
     * so 120 BPM turns once in 16 s. Still (0) without a tempo.
     */
    fun spinPeriodOf(bpm: Float?): Float =
        bpm?.takeIf { it.isFinite() && it > 0f }?.let { BEATS_PER_TURN * 60f / it } ?: 0f

    /**
     * How deep the night side is for a dynamic range of [dr] dB, 0.55..1: a
     * master squashed to DR4 is lit flat, one that kept DR16 or more is the
     * full depth. Without a measurement, the full depth every planet had.
     */
    fun shadeOf(dr: Float?): Float {
        val depth = dr?.takeIf { it.isFinite() }?.let { ((it - SHADE_FLAT_DR) / SHADE_SPAN_DR).coerceIn(0f, 1f) } ?: return 1f
        return SHADE_FLATTEST + (1f - SHADE_FLATTEST) * depth
    }

    /**
     * Seconds for moon [m] (0 innermost) to go round once: one orbit per
     * [BEATS_PER_MOON_ORBIT] beats of its track's [bpm], so a 174 BPM track's
     * moon races and a ballad's drifts; each slot further out a little slower,
     * as without a tempo. Without one, the period every moon had.
     */
    fun moonPeriodOf(m: Int, bpm: Float?): Float {
        val slot = 1f + m * 0.55f
        val beat = bpm?.takeIf { it.isFinite() && it > 0f }?.let { 60f / it }
            ?: return PlanetSystem.MOON_PERIOD * slot
        return BEATS_PER_MOON_ORBIT * beat * slot
    }

    private const val BEATS_PER_MOON_ORBIT = 16f
    private const val HAZE_QUIET_LUFS = -20f
    private const val HAZE_SPAN_LU = 14f
    private const val BEATS_PER_TURN = 32f
    private const val SHADE_FLAT_DR = 4f
    private const val SHADE_SPAN_DR = 12f
    private const val SHADE_FLATTEST = 0.55f

    private fun planet(
        album: LibraryAlbum,
        orbit: Float,
        firstOrbit: Float,
        starRadius: Float,
        limits: SystemLimits,
        glow: Float,
    ): PlanetSystem.Planet {
        val seed = PlanetSystem.stableHash(album.key)
        val musicalKey = album.camelotKey?.takeIf { it in 1..24 }
        val radius = starRadius * (PlanetSystem.PLANET_MIN_SHARE + PlanetSystem.PLANET_SPAN_SHARE * sizeShare(album))
        val running = compareBy<LibraryTrack>({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() }, { it.key })
        val moonTracks = album.tracks
            .sortedWith(compareByDescending<LibraryTrack> { it.plays }.then(running))
            .take(limits.maxMoons)
            .sortedWith(running)
        val ring = (album.tracks - moonTracks.toSet()).sortedWith(running)
        val longest = moonTracks.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1) ?: 1
        return PlanetSystem.Planet(
            label = album.title,
            share = glow,
            releases = null,
            orbit = orbit,
            period = PlanetSystem.FIRST_PERIOD * (orbit / firstOrbit).pow(1.5f),
            phase = PlanetSystem.unit(seed) * PlanetSystem.TAU,
            tilt = (PlanetSystem.unit(seed ushr 8) - 0.5f) * 0.36f,
            node = PlanetSystem.unit(seed ushr 24) * PlanetSystem.TAU,
            radius = radius,
            hue = musicalKey?.let(::keyHue) ?: (PlanetSystem.unit(seed ushr 16) * 360f),
            artworkUrl = album.artworkUri,
            moons = moonTracks.mapIndexed { m, track ->
                val moonSeed = PlanetSystem.stableHash(track.key)
                val trackKey = track.camelotKey?.takeIf { it in 1..24 }
                PlanetSystem.Moon(
                    ref = track.ref(),
                    orbit = PlanetSystem.moonOrbit(starRadius, m),
                    period = moonPeriodOf(m, track.bpm),
                    phase = PlanetSystem.unit(moonSeed) * PlanetSystem.TAU,
                    tilt = (PlanetSystem.unit(moonSeed ushr 8) - 0.5f) * 1.1f,
                    node = PlanetSystem.unit(moonSeed ushr 24) * PlanetSystem.TAU,
                    radius = radius * (0.16f + 0.12f * sqrt(track.durationSeconds.coerceAtLeast(0) / longest.toFloat())),
                    keyHue = trackKey?.let(::keyHue) ?: PlanetSystem.NO_KEY_HUE,
                    tone = keyTone(trackKey),
                )
            },
            key = album.key,
            glow = glow,
            ring = ring.map { it.ref() },
            subtitle = album.year?.toString(),
            tone = keyTone(musicalKey),
            haze = hazeOf(album.lufs),
            spinPeriod = spinPeriodOf(album.bpm),
            shade = shadeOf(album.dynamicRange),
        )
    }

    private fun LibraryTrack.ref() = MoonRef.Local(trackId = id, key = key, title = title, label = label)
}
