package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.domain.model.GenreGraph
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The genre galaxy's stars: every genre in the graph, as it has always been
 * laid out.
 *
 * - **Sound** (morph 0): the genre map's own baked layout — the twelve
 *   family clusters where the map has always put them — laid flat as a disc
 *   a few dozen units thick.
 * - **Time** (morph 1): a spiral galaxy. Distance from the core is when a
 *   genre began — the oldest music at the centre, the newest at the rim — and
 *   each family is an arm, starting in the direction its cluster sits in the
 *   sound layout so the morph turns the map rather than shuffling it.
 *
 * Draws from the scene's stream in the order it always has, so the galaxy is
 * the same one, point for point, that it was before the field was its own
 * thing.
 */
object GenreStarField {

    fun from(graph: GenreGraph): StarField {
        val genres = graph.allGenres
        val size = genres.size
        val index: Map<String, Int> = genres.withIndex().associate { (i, g) -> g.id to i }
        val families = genres.map { it.family }.distinct().sorted()
        val family = IntArray(size) { families.indexOf(genres[it].family) }
        // The square root of reach, so the head doesn't swamp the tail.
        val max = genres.maxOfOrNull { it.reach ?: 0 }?.coerceAtLeast(1) ?: 1
        val prominence = FloatArray(size) { sqrt((genres[it].reach ?: 0).toFloat() / max) }
        val startYear = IntArray(size) { genres[it].era.getOrNull(0) ?: 2000 }
        // Parent links as (child, parent) pairs.
        val links = buildList {
            for (i in 0 until size) {
                val parent = genres[i].parents.firstNotNullOfOrNull { index[it] } ?: continue
                add(i); add(parent)
            }
        }.toIntArray()
        val x = FloatArray(size) { genres[it].x }
        val y = FloatArray(size) { genres[it].y }
        return StarField(
            ids = genres.map { it.id },
            names = genres.map { it.name },
            families = families,
            family = family,
            prominence = prominence,
            startYear = startYear,
            links = links,
            layout = BakedLayout(x, y),
        )
    }

    /**
     * Where each genre sits on the disc, and which way each family's arm runs
     * in the spiral: the frame the library galaxy is laid out in, so an artist
     * sits where their genre does on the genre map and the two maps are one
     * sky. The same numbers [from] lays the genres out by, without the scatter.
     */
    class Frame(
        /** Each genre's place on the disc, by id: x and z, scene units. */
        val disc: Map<String, Pair<Float, Float>>,
        /** Each family's arm angle in the spiral, radians, by family id. */
        val arms: Map<String, Float>,
        /** Where the timeline's track leaves the core. */
        val trackAngle: Float,
    )

    fun frame(graph: GenreGraph): Frame {
        val genres = graph.allGenres
        val families = genres.map { it.family }.distinct().sorted()
        val sumX = FloatArray(families.size)
        val sumZ = FloatArray(families.size)
        val count = IntArray(families.size)
        val disc = HashMap<String, Pair<Float, Float>>(genres.size * 2)
        for (g in genres) {
            val x = g.x / LAYOUT_EXTENT * GalaxyScene.RADIUS
            val z = g.y / LAYOUT_EXTENT * GalaxyScene.RADIUS
            disc[g.id] = x to z
            val f = families.indexOf(g.family)
            sumX[f] += x; sumZ[f] += z; count[f]++
        }
        val clusterAngle = FloatArray(families.size) { f ->
            atan2(sumZ[f] / count[f].coerceAtLeast(1), sumX[f] / count[f].coerceAtLeast(1))
        }
        val armAngle = GalaxyScene.bundleArms(clusterAngle)
        return Frame(disc, families.withIndex().associate { (f, id) -> id to armAngle[f] }, GalaxyScene.widestGap(armAngle))
    }

    /** The baked map's coordinates as the disc, and the families as the spiral's arms. */
    private class BakedLayout(private val x: FloatArray, private val y: FloatArray) : StarLayout {
        override fun place(field: StarField, gauss: () -> Float, sound: FloatArray, time: FloatArray): Float {
            val size = field.size
            val families = field.families.size
            val sumX = FloatArray(families)
            val sumZ = FloatArray(families)
            val count = IntArray(families)
            for (i in 0 until size) {
                val sx = x[i] / LAYOUT_EXTENT * GalaxyScene.RADIUS
                val sz = y[i] / LAYOUT_EXTENT * GalaxyScene.RADIUS
                sound[i * 3] = sx
                sound[i * 3 + 1] = gauss() * DISC_THICKNESS
                sound[i * 3 + 2] = sz
                val f = field.family[i]
                sumX[f] += sx; sumZ[f] += sz; count[f]++
            }
            val clusterAngle = FloatArray(families) { f ->
                atan2(sumZ[f] / count[f].coerceAtLeast(1), sumX[f] / count[f].coerceAtLeast(1))
            }
            val armAngle = GalaxyScene.bundleArms(clusterAngle)
            val trackAngle = GalaxyScene.widestGap(armAngle)
            for (i in 0 until size) {
                val r = GalaxyScene.timeDistance(GalaxyScene.timeRadius(field.startYear[i])) + gauss() * 12f
                val a = armAngle[field.family[i]] + GalaxyScene.ARM_TWIST * (r / GalaxyScene.RADIUS) + gauss() * 0.09f
                time[i * 3] = cos(a) * r
                time[i * 3 + 1] = gauss() * 34f * (1.15f - r / GalaxyScene.RADIUS)
                time[i * 3 + 2] = sin(a) * r
            }
            return trackAngle
        }
    }

    /** The baked layout's extent, so the sound layout fills [GalaxyScene.RADIUS]. */
    private const val LAYOUT_EXTENT = 1250f
    private const val DISC_THICKNESS = 26f
}
