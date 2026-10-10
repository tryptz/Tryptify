package tf.monochrome.android.domain.model

import kotlin.random.Random

/** The genre galaxy's ways around: somewhere new, and where you are. */
object GenreGalaxy {

    /** How many of the best-known genres "Surprise me" draws from. */
    const val SURPRISE_POOL = 200

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
}
