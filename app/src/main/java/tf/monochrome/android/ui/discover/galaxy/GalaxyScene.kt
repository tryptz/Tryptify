package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.domain.model.GenreGraph
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The genre galaxy as points in 3D: every genre a star, in two layouts the
 * view morphs between, plus the dust, the core and the far sky around them.
 *
 * - **Sound** (morph 0): the genre map's own baked layout — the twelve
 *   family clusters where the map has always put them — laid flat as a disc
 *   a few dozen units thick.
 * - **Time** (morph 1): a spiral galaxy. Distance from the core is when a
 *   genre began — the oldest music at the centre, the newest at the rim — and
 *   each family is an arm, starting in the direction its cluster sits in the
 *   sound layout so the morph turns the map rather than shuffling it. The
 *   timeline itself is a spiral too: a track wound at the arms' own pitch,
 *   through the widest gap between them, with the years marked along it
 *   ([trackPoint]), so time reads outwards along any arm.
 *
 * Pure and seeded: the same graph gives the same galaxy every launch, and
 * the tests can check it without a device.
 */
class GalaxyScene(
    graph: GenreGraph,
    dustCount: Int = DEFAULT_DUST,
    coreCount: Int = DEFAULT_CORE,
    skyCount: Int = DEFAULT_SKY,
    seed: Int = 977,
) {
    private val random = Random(seed)
    private fun gauss(): Float {
        var u = 0.0
        while (u == 0.0) u = random.nextDouble()
        return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * random.nextDouble())).toFloat()
    }

    val genres = graph.allGenres
    val size: Int = genres.size
    val index: Map<String, Int> = genres.withIndex().associate { (i, g) -> g.id to i }

    /** Family ids, in a fixed order; [family] indexes into this. */
    val families: List<String> = genres.map { it.family }.distinct().sorted()
    val family = IntArray(size) { families.indexOf(genres[it].family) }

    /** How well known each genre is, 0..1 (square root of reach, so the head doesn't swamp the tail). */
    val prominence = run {
        val max = genres.maxOfOrNull { it.reach ?: 0 }?.coerceAtLeast(1) ?: 1
        FloatArray(size) { sqrt((genres[it].reach ?: 0).toFloat() / max) }
    }

    /** Twinkle phase per genre. */
    val phase = FloatArray(size) { random.nextFloat() * 6.2832f }

    val sound = FloatArray(size * 3)
    val time = FloatArray(size * 3)

    /** Where the time track leaves the core: the middle of the widest gap between arms. */
    val trackAngle: Float

    init {
        val sumX = FloatArray(families.size)
        val sumZ = FloatArray(families.size)
        val count = IntArray(families.size)
        for (i in 0 until size) {
            val g = genres[i]
            val x = g.x / LAYOUT_EXTENT * RADIUS
            val z = g.y / LAYOUT_EXTENT * RADIUS
            sound[i * 3] = x
            sound[i * 3 + 1] = gauss() * DISC_THICKNESS
            sound[i * 3 + 2] = z
            sumX[family[i]] += x; sumZ[family[i]] += z; count[family[i]]++
        }
        val clusterAngle = FloatArray(families.size) { f ->
            atan2(sumZ[f] / count[f].coerceAtLeast(1), sumX[f] / count[f].coerceAtLeast(1))
        }
        val armAngle = bundleArms(clusterAngle)
        trackAngle = widestGap(armAngle)
        for (i in 0 until size) {
            val r = timeDistance(timeRadius(startYear(i))) + gauss() * 12f
            val a = armAngle[family[i]] + ARM_TWIST * (r / RADIUS) + gauss() * 0.09f
            time[i * 3] = cos(a) * r
            time[i * 3 + 1] = gauss() * 34f * (1.15f - r / RADIUS)
            time[i * 3 + 2] = sin(a) * r
        }
    }

    fun startYear(i: Int): Int = genres[i].era.getOrNull(0) ?: 2000

    /** The point on the time track at [year], on the disc, written to [out] at [at]. */
    fun trackPoint(year: Int, out: FloatArray, at: Int = 0) = trackPointAt(timeRadius(year), out, at)

    /** The point on the time track [share] of the way out (0 core, 1 rim). */
    fun trackPointAt(share: Float, out: FloatArray, at: Int = 0) {
        val r = timeDistance(share)
        val a = trackAngle + ARM_TWIST * (r / RADIUS)
        out[at] = cos(a) * r
        out[at + 1] = 0f
        out[at + 2] = sin(a) * r
    }

    /** Where genre [i] is at [morph] (0 sound, 1 time), written to [out] at [at]. */
    fun position(i: Int, morph: Float, out: FloatArray, at: Int = 0) {
        val k = i * 3
        out[at] = sound[k] + (time[k] - sound[k]) * morph
        out[at + 1] = sound[k + 1] + (time[k + 1] - sound[k + 1]) * morph
        out[at + 2] = sound[k + 2] + (time[k + 2] - sound[k + 2]) * morph
    }

    // ── Dust: small stars around each genre, so the galaxy has a body ──

    val dustCount = dustCount
    val dustOwner = IntArray(dustCount) { random.nextInt(size.coerceAtLeast(1)) }
    val dustOffset = FloatArray(dustCount * 3).also { o ->
        for (k in 0 until dustCount) {
            o[k * 3] = gauss() * 52f; o[k * 3 + 1] = gauss() * 16f; o[k * 3 + 2] = gauss() * 52f
        }
    }
    /** Brightness per dust grain, 0..1. */
    val dustLight = FloatArray(dustCount) { 0.35f + random.nextFloat() * 0.65f }

    /** A dust grain's position: its genre's, plus its offset (drawn in a little in the spiral). */
    fun dustPosition(k: Int, morph: Float, out: FloatArray, at: Int = 0) {
        position(dustOwner[k], morph, out, at)
        val shrink = 1f - 0.2f * morph
        out[at] += dustOffset[k * 3] * shrink
        out[at + 1] += dustOffset[k * 3 + 1]
        out[at + 2] += dustOffset[k * 3 + 2] * shrink
    }

    // ── The core: a warm bulge at the centre, the same in both layouts ──

    val core = FloatArray(coreCount * 3).also { c ->
        // The nuclear bulge: a puffed lens of old stars round the black hole,
        // thickest at the middle and thinning out across the inner disc, as
        // the Milky Way's is seen side on. Starts outside the hole's shadow,
        // so the shadow is not full of stars in front of it.
        for (k in 0 until coreCount) {
            val r = DISK_INNER * 1.4f + kotlin.math.abs(gauss()) * 190f
            val a = random.nextFloat() * 6.2832f
            c[k * 3] = cos(a) * r
            c[k * 3 + 1] = gauss() * 105f * exp(-(r - DISK_INNER) / 240f)
            c[k * 3 + 2] = sin(a) * r
        }
    }

    /**
     * The halo: a sparse sphere of old stars round the whole disc, and a few
     * globular clusters in it — the galaxy's outskirts in the side view of the
     * Milky Way, where the disc is a line and the halo is a cloud.
     */
    val halo = FloatArray((HALO_STARS + GLOBULARS * GLOBULAR_STARS) * 3).also { h ->
        var k = 0
        fun put(x: Float, y: Float, z: Float) {
            h[k * 3] = x; h[k * 3 + 1] = y; h[k * 3 + 2] = z; k++
        }
        fun direction(out: FloatArray) {
            val u = random.nextFloat() * 2f - 1f
            val t = random.nextFloat() * 6.2832f
            val m = sqrt(1f - u * u)
            out[0] = m * cos(t); out[1] = u; out[2] = m * sin(t)
        }
        val d = FloatArray(3)
        repeat(HALO_STARS) {
            direction(d)
            // Denser toward the middle: radius drawn on 1/r², 0.6 to 1.5 of the disc.
            val r = RADIUS * (0.6f + 0.9f * random.nextFloat() * random.nextFloat())
            put(d[0] * r, d[1] * r * 0.8f, d[2] * r)
        }
        repeat(GLOBULARS) {
            direction(d)
            val r = RADIUS * (0.55f + 0.6f * random.nextFloat())
            val cx = d[0] * r; val cy = d[1] * r * 0.8f; val cz = d[2] * r
            repeat(GLOBULAR_STARS) { put(cx + gauss() * 16f, cy + gauss() * 16f, cz + gauss() * 16f) }
        }
    }

    // ── The far sky: directions only, too far to move as the camera does ──

    val sky = FloatArray(skyCount * 3).also { s ->
        for (k in 0 until skyCount) {
            val u = random.nextFloat() * 2f - 1f
            val t = random.nextFloat() * 6.2832f
            val m = sqrt(1f - u * u)
            s[k * 3] = m * cos(t); s[k * 3 + 1] = u; s[k * 3 + 2] = m * sin(t)
        }
    }

    // ── Family structure ──

    /** Parent links as (child, parent) index pairs. */
    // `size` inside buildList is the list being built, so the genre count is
    // taken outside it.
    val links: IntArray = run {
        val n = size
        buildList {
            for (i in 0 until n) {
                val parent = genres[i].parents.firstNotNullOfOrNull { index[it] } ?: continue
                add(i); add(parent)
            }
        }.toIntArray()
    }

    /** Genres each family's nebulae sit behind: a few of its best known, spread down its list. */
    val nebulaAnchors: IntArray = run {
        val n = size
        buildList {
        for (f in families.indices) {
            val members = (0 until n).filter { family[it] == f }.sortedByDescending { prominence[it] }
            val picks = minOf(NEBULAE_PER_FAMILY, members.size)
            for (k in 0 until picks) add(members[k * members.size / picks])
        }
        }.toIntArray()
    }

    /** Each nebula's radius, 190..300 units, so no two families wear the same cloud. */
    val nebulaRadius = FloatArray(nebulaAnchors.size) { 190f + random.nextFloat() * 110f }

    /** Each nebula's offset from its genre, so the clouds don't sit dead on a star. */
    val nebulaJitter = FloatArray(nebulaAnchors.size * 3).also { j ->
        for (k in nebulaAnchors.indices) {
            j[k * 3] = gauss() * 40f; j[k * 3 + 1] = gauss() * 20f; j[k * 3 + 2] = gauss() * 40f
        }
    }

    companion object {
        /** Radius of the galaxy, in scene units. */
        const val RADIUS = 1000f

        /** The baked layout's extent, so the sound layout fills [RADIUS]. */
        private const val LAYOUT_EXTENT = 1250f
        private const val DISC_THICKNESS = 26f
        /** How far an arm winds from core to rim, radians: a little over half a turn. */
        const val ARM_TWIST = 5.6f

        /**
         * The black hole at the middle: the shadow's radius, and the accretion
         * disk's inner and outer edges. The disc layout's nearest genre is over
         * four hundred units out, so it all sits in the hole the baked map
         * already has; the timeline starts outside it ([timeDistance]).
         */
        const val HOLE_SHADOW = 58f
        const val DISK_INNER = 80f
        const val DISK_OUTER = 300f

        /** Where the timeline's oldest music starts: clear of the accretion disk. */
        const val CORE_CLEAR = 340f

        /** How far out a genre [share] of the way through time sits (0 oldest, 1 newest). */
        fun timeDistance(share: Float): Float = CORE_CLEAR + share * (RADIUS * 0.95f - CORE_CLEAR)

        /** Families per arm: the timeline's twelve families wound as four arms, like the Milky Way's. */
        private const val ARMS = 4
        private const val ARM_SPREAD = 0.2f

        /**
         * Each family's arm angle: the families, in the order their clusters
         * sit round the disc, dealt into [ARMS] bundles of neighbours, each
         * bundle an arm, its families side by side in it. The bundles sit
         * where their families' clusters sit on average, so the morph turns
         * the map rather than shuffling it.
         */
        internal fun bundleArms(clusterAngle: FloatArray): FloatArray {
            val n = clusterAngle.size
            if (n == 0) return clusterAngle
            val order = clusterAngle.indices.sortedBy { ((clusterAngle[it] % TAU) + TAU) % TAU }
            val perArm = (n + ARMS - 1) / ARMS
            val out = FloatArray(n)
            for (b in 0 until ARMS) {
                val members = order.drop(b * perArm).take(perArm)
                if (members.isEmpty()) continue
                // The circular mean of the members' angles.
                var sx = 0f; var sz = 0f
                members.forEach { sx += cos(clusterAngle[it]); sz += sin(clusterAngle[it]) }
                val centre = atan2(sz, sx)
                members.forEachIndexed { k, f ->
                    out[f] = centre + (k - (members.size - 1) / 2f) * ARM_SPREAD
                }
            }
            return out
        }

        private const val HALO_STARS = 420
        private const val GLOBULARS = 9
        private const val GLOBULAR_STARS = 28
        private const val NEBULAE_PER_FAMILY = 5

        const val DEFAULT_DUST = 6000
        const val DEFAULT_CORE = 1300
        const val DEFAULT_SKY = 900

        /** The years marked along the time track. */
        val TRACK_YEARS = intArrayOf(1600, 1900, 1950, 1980, 2000, 2020)

        /** The middle of the widest angular gap between [angles], radians. */
        internal fun widestGap(angles: FloatArray): Float {
            if (angles.isEmpty()) return 0f
            val sorted = angles.map { ((it % TAU) + TAU) % TAU }.sorted()
            var best = 0f
            var at = 0f
            for (k in sorted.indices) {
                val from = sorted[k]
                val to = if (k + 1 < sorted.size) sorted[k + 1] else sorted[0] + TAU
                if (to - from > best) {
                    best = to - from; at = from + (to - from) / 2f
                }
            }
            return at
        }

        private const val TAU = 6.2831855f

        /**
         * How far from the core a genre that began in [year] sits, 0..1.
         *
         * Piecewise, because the dataset is lopsided: a hundred genres over
         * the fifteen centuries before 1900 and the rest since. A straight
         * scale would put two thirds of the map in the outermost tenth.
         */
        fun timeRadius(year: Int): Float {
            val stops = TIME_STOPS
            if (year <= stops[0].first) return stops[0].second
            for (k in 1 until stops.size) {
                val (y1, r1) = stops[k]
                if (year <= y1) {
                    val (y0, r0) = stops[k - 1]
                    return r0 + (r1 - r0) * (year - y0) / (y1 - y0).toFloat()
                }
            }
            return 1f
        }

        private val TIME_STOPS = listOf(
            400 to 0.05f, 1600 to 0.13f, 1900 to 0.28f, 1950 to 0.45f, 1980 to 0.62f, 2000 to 0.8f, 2025 to 1f,
        )
    }
}
