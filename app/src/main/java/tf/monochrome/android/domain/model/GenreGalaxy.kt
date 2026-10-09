package tf.monochrome.android.domain.model

import kotlin.math.sqrt
import kotlin.random.Random

/** Where one family's cloud sits on the map, in layout units. */
data class FamilyCloud(val family: String, val x: Float, val y: Float, val radius: Float)

/**
 * The genre map as a galaxy: what the drawing needs beyond the genres
 * themselves, worked out once from the graph.
 */
object GenreGalaxy {

    /** How many of the best-known genres "Surprise me" draws from. */
    const val SURPRISE_POOL = 200

    /**
     * One soft cloud per family, centred on its genres and wide enough to sit
     * behind most of them: the spread of its genres around their centre, a
     * little over. The map's families are radial clusters, so a cloud is the
     * honest shape — it marks where a family is, not a border it keeps to.
     */
    fun familyClouds(graph: GenreGraph): List<FamilyCloud> =
        graph.allGenres
            .filter { it.family.isNotBlank() }
            .groupBy { it.family }
            .mapNotNull { (family, nodes) ->
                if (nodes.isEmpty()) return@mapNotNull null
                val cx = nodes.map { it.x }.average().toFloat()
                val cy = nodes.map { it.y }.average().toFloat()
                val spread = sqrt(nodes.map { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }.average()).toFloat()
                FamilyCloud(family, cx, cy, (spread * CLOUD_SPREAD).coerceAtLeast(MIN_CLOUD_RADIUS))
            }
            .sortedBy { it.family }

    /**
     * Somewhere the listener has not been: a well-known genre, not a family
     * root (those are directions, not places), not [explored], and not
     * [current]. Falls back to any well-known genre once everything near the
     * top has been explored. Null only for an empty graph.
     */
    fun surprise(graph: GenreGraph, explored: Set<String>, random: Random, current: String? = null): GenreNode? {
        val known = graph.allGenres
            .filter { it.parents.isNotEmpty() && it.reach != null }
            .sortedWith(compareByDescending<GenreNode> { it.reach ?: -1 }.thenBy { it.id })
            .take(SURPRISE_POOL)
        val fresh = known.filter { it.id !in explored && it.id != current }
        val pool = fresh.ifEmpty { known.filter { it.id != current } }
        return pool.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
    }

    /**
     * "You are here": the genre the listener was last in, else the first one
     * they hearted. Stale ids from an older dataset are passed over.
     */
    fun here(graph: GenreGraph, recent: List<String>, hearted: Set<String>): GenreNode? =
        recent.firstNotNullOfOrNull { graph[it] } ?: hearted.sorted().firstNotNullOfOrNull { graph[it] }

    private const val CLOUD_SPREAD = 1.5f
    private const val MIN_CLOUD_RADIUS = 60f
}
