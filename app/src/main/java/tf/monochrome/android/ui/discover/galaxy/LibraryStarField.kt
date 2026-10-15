package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.LibraryArtist
import tf.monochrome.android.domain.model.LibraryCollab
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The library galaxy's stars: every artist in the library, laid out in the
 * genre galaxy's own frame.
 *
 * - **Sound**: beside their genre. An artist's main genre is the most common
 *   tag on their tracks that the genre graph recognises
 *   ([GenreGraph.resolve]); they sit in a cloud round that genre's place on
 *   the genre map, so your jazz is where jazz is. An artist whose tags it
 *   does not recognise is not guessed at: they float in the halo, above and
 *   below the disc.
 * - **Time**: on their genre family's arm of the spiral, as far out as their
 *   first record is late, like the genres themselves.
 *
 * **Every star stays put as the library grows.** Each artist's scatter,
 * twinkle and place come from a stream seeded by their own key, never one
 * shared down the list, so adding an artist moves nobody else. (The dust round
 * the stars is the scene's, and does move: it is decoration, not a place.)
 */
object LibraryStarField {

    /** The id a star for the artist keyed [key] has, unlike any genre's. */
    fun starId(key: String): String = "$PREFIX$key"

    /** The artist key a star id carries, or null for a star that is not an artist's. */
    fun keyOf(starId: String): String? = starId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    const val UNCHARTED = "uncharted"
    private const val PREFIX = "artist:"

    fun build(
        artists: List<LibraryArtist>,
        collabs: List<LibraryCollab>,
        graph: GenreGraph,
        frame: GenreStarField.Frame = GenreStarField.frame(graph),
    ): StarField {
        val stars = artists.filter { it.trackCount > 0 }.distinctBy { it.key }.sortedBy { it.key }
        val size = stars.size

        // Each artist's main genre, where one is recognised.
        val genre = stars.map { mainGenre(it, graph)?.takeIf { g -> g.id in frame.disc } }
        val familyOf = List(size) { i ->
            when {
                stars[i].kind == LibraryArtist.Kind.UNKNOWN -> UNCHARTED
                stars[i].kind == LibraryArtist.Kind.COMPILATIONS -> COMPILATIONS_FAMILY
                else -> genre[i]?.family ?: UNCHARTED
            }
        }
        val families = familyOf.distinct().sorted()
        val family = IntArray(size) { families.indexOf(familyOf[it]) }

        val most = stars.maxOfOrNull { it.trackCount }?.coerceAtLeast(1) ?: 1
        val prominence = FloatArray(size) {
            // A log scale, so a box set doesn't dwarf everyone with an album.
            (ln(1f + stars[it].trackCount) / ln(1f + most)).coerceIn(0f, 1f) *
                if (stars[it].kind == LibraryArtist.Kind.UNKNOWN) UNKNOWN_DIM else 1f
        }
        val startYear = IntArray(size) { stars[it].firstYear ?: UNDATED_YEAR }
        val phase = FloatArray(size) { keyed(stars[it].key, SALT_PHASE).nextFloat() * TAU }

        val index = stars.withIndex().associate { (i, a) -> a.key to i }
        val links = collabs
            .mapNotNull { c ->
                val a = index[c.a] ?: return@mapNotNull null
                val b = index[c.b] ?: return@mapNotNull null
                if (a == b) null else minOf(a, b) to maxOf(a, b)
            }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .flatMap { listOf(it.first, it.second) }
            .toIntArray()

        val kinds = stars.map { it.kind }
        val keys = stars.map { it.key }
        val discs = genre.map { g -> g?.let { frame.disc[it.id] } }
        return StarField(
            ids = keys.map(::starId),
            names = stars.map { it.name },
            families = families,
            family = family,
            prominence = prominence,
            startYear = startYear,
            links = links,
            layout = KeyedLayout(keys, kinds, discs, familyOf, frame),
            phase = phase,
            systemLimits = SystemLimits.LIBRARY,
        )
    }

    /** The genre most of an artist's tags resolve to, ties to the alphabetically first. */
    internal fun mainGenre(artist: LibraryArtist, graph: GenreGraph): tf.monochrome.android.domain.model.GenreNode? {
        val votes = HashMap<String, Int>()
        for ((tag, count) in artist.genreTags) {
            // The whole tag first, as the scanner reads it; a field holding
            // several genres only when that fails.
            val whole = graph.resolve(tag)
            val nodes = if (whole != null) listOf(whole) else splitTags(tag).mapNotNull { graph.resolve(it) }.distinctBy { it.id }
            for (node in nodes) votes[node.id] = (votes[node.id] ?: 0) + count
        }
        val best = votes.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()
        return best?.let { graph[it.key] }
    }

    /** A tag field holding several genres ("Rock; Pop", "Jazz/Funk"), split into them. */
    internal fun splitTags(tag: String): List<String> =
        tag.split(';', '/', ',', '|', '\u0000').map { it.trim() }.filter { it.isNotEmpty() }

    /** Every star placed from its own key: nothing about one moves another. */
    private class KeyedLayout(
        private val keys: List<String>,
        private val kinds: List<LibraryArtist.Kind>,
        private val discs: List<Pair<Float, Float>?>,
        private val families: List<String>,
        private val frame: GenreStarField.Frame,
    ) : StarLayout {
        override fun place(field: StarField, gauss: () -> Float, sound: FloatArray, time: FloatArray): Float {
            for (i in 0 until field.size) {
                val r = keyed(keys[i], SALT_PLACE)
                fun g(): Float = r.gauss()
                val k = i * 3
                when {
                    kinds[i] == LibraryArtist.Kind.COMPILATIONS -> {
                        // Near the core, clear of the disk: every compilation
                        // touches many artists, and the middle is where they
                        // are all a short line away.
                        val a = r.nextFloat() * TAU
                        val d = GalaxyScene.CORE_CLEAR + COMPILATIONS_OUT
                        sound[k] = cos(a) * d; sound[k + 1] = g() * 8f; sound[k + 2] = sin(a) * d
                        time[k] = sound[k]; time[k + 1] = sound[k + 1]; time[k + 2] = sound[k + 2]
                    }
                    discs[i] == null -> {
                        // Uncharted: in the halo, above or below the disc, never
                        // passed off as belonging to a genre it may not.
                        val a = r.nextFloat() * TAU
                        val out = GalaxyScene.RADIUS * (HALO_MIN + (HALO_MAX - HALO_MIN) * r.nextFloat())
                        val up = (if (r.nextBoolean()) 1f else -1f) * (HALO_LIFT_MIN + (HALO_LIFT_MAX - HALO_LIFT_MIN) * r.nextFloat())
                        sound[k] = cos(a) * out; sound[k + 1] = up; sound[k + 2] = sin(a) * out
                        val share = GalaxyScene.timeDistance(GalaxyScene.timeRadius(field.startYear[i]))
                        time[k] = cos(a) * share; time[k + 1] = up; time[k + 2] = sin(a) * share
                    }
                    else -> {
                        val (gx, gz) = discs[i]!!
                        var x = gx + g() * SCATTER
                        var z = gz + g() * SCATTER
                        // Out of the black hole's reach: a genre near the
                        // core's clearing must not throw an artist into it.
                        val d = hypot(x, z)
                        if (d < GalaxyScene.CORE_CLEAR) {
                            val s = GalaxyScene.CORE_CLEAR / d.coerceAtLeast(1e-3f)
                            x *= s; z *= s
                        }
                        sound[k] = x; sound[k + 1] = g() * SCATTER_THICKNESS; sound[k + 2] = z
                        val arm = frame.arms[families[i]] ?: 0f
                        val out = GalaxyScene.timeDistance(GalaxyScene.timeRadius(field.startYear[i])) + g() * 12f
                        val a = arm + GalaxyScene.ARM_TWIST * (out / GalaxyScene.RADIUS) + g() * 0.09f
                        time[k] = cos(a) * out
                        time[k + 1] = g() * 34f * (1.15f - out / GalaxyScene.RADIUS)
                        time[k + 2] = sin(a) * out
                    }
                }
            }
            return frame.trackAngle
        }
    }

    /** A stream of its own for the artist keyed [key]: the same numbers every run, whoever else is in the library. */
    private fun keyed(key: String, salt: Int): Random =
        Random((PlanetSystem.stableHash(key).toLong() shl 32) xor (salt.toLong() and 0xFFFFFFFFL))

    private fun Random.gauss(): Float {
        var u = 0.0
        while (u == 0.0) u = nextDouble()
        return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * nextDouble())).toFloat()
    }

    private const val SALT_PHASE = 0x51A7
    private const val SALT_PLACE = 0x2B0C

    /** How far round its genre an artist strays on the disc, and how far off the disc's plane. */
    private const val SCATTER = 46f
    private const val SCATTER_THICKNESS = 14f

    /** The halo's band, as shares of the galaxy's radius, and how far above or below the disc. */
    private const val HALO_MIN = 0.45f
    private const val HALO_MAX = 0.85f
    private const val HALO_LIFT_MIN = 150f
    private const val HALO_LIFT_MAX = 320f

    /** How far past the core's clearing the compilations star sits. */
    private const val COMPILATIONS_OUT = 30f
    private const val COMPILATIONS_FAMILY = "compilations"

    /** The year an artist with no dated album is placed at on the timeline. */
    const val UNDATED_YEAR = 2000

    /** How much smaller the unknown artist's star is than its track count says. */
    private const val UNKNOWN_DIM = 0.4f

    private const val TAU = 6.2831855f
}
