package tf.monochrome.android.domain.model

import kotlin.random.Random

/**
 * Discover's two daily genres: today's discovery, and the genre of the day.
 *
 * Both exist to give the page a reason to be opened tomorrow. A feed that
 * reshuffles on every visit has no "today" in it — nothing on it is new in a
 * way anyone could notice — so these change once a day and only once a day, and
 * the listener can tell.
 *
 * They are deliberately different kinds of pick. **Today's discovery** is close:
 * a genre one step from the ones the listener already keeps, that they have not
 * been to yet, so it lands as "I'd like this" rather than as homework. **The
 * genre of the day** is wider: anything on the map with a researched history,
 * shown with that history, so it is something to read as well as to play.
 *
 * Pure, so the picking can be tested against the real graph, and seeded by the
 * day, so it is the same pick on every launch of the same day without having to
 * be stored — the view model still stores today's discovery, because playing it
 * marks it explored and a pick that recomputed would vanish the moment it was
 * used.
 */
object DailyDiscovery {

    /** How many of the closest candidates the day rotates through. */
    const val ROTATION = 7

    /** How many of the best-known genres with a history the spotlight draws on. */
    const val SPOTLIGHT_POOL = 60

    /** At most this many of the listener's genres are used as anchors. */
    const val MAX_ANCHORS = 8

    /** How weak a link to the listener's genres may be and still count as "next door". */
    private const val NEAR_FLOOR = 0.3f

    /**
     * Today's genre, and the listener's genres it sits beside.
     *
     * [via] is empty when the pick did not come from the listener's genres — a
     * new listener, or one who has already explored everything around theirs —
     * and the page then says it is somewhere they have not been, rather than
     * claiming a link that isn't there.
     */
    data class Pick(val genre: GenreNode, val via: List<GenreNode>)

    /**
     * Today's discovery.
     *
     * [anchors] are the listener's own genres, most important first (hearted,
     * then recently played). Candidates are their neighbours within two steps,
     * strong links only, never a genre already [explored] or one of the anchors
     * themselves, scored by how many anchors point at them and how strongly.
     * The day picks among the best [ROTATION], so it is not always the single
     * top candidate — which would be the same genre until it was played.
     *
     * [avoid] is the previous pick, so two days in a row never show the same
     * genre even when the listener did not play it.
     *
     * With nothing near the anchors, the pick is a well-known genre the listener
     * has not explored, from anywhere on the map. Null only for an empty graph.
     */
    fun todaysGenre(
        graph: GenreGraph,
        anchors: List<String>,
        explored: Set<String>,
        day: Long,
        avoid: Set<String> = emptySet(),
    ): Pick? {
        val ranked = nearby(graph, anchors, explored + avoid)
        if (ranked.isNotEmpty()) {
            val pool = ranked.take(ROTATION)
            val chosen = pool[Random(seed(day, SALT_TODAY)).nextInt(pool.size)]
            return Pick(chosen.node, chosen.via)
        }
        val wide = wellKnown(graph, exclude = explored + avoid + anchors)
            .ifEmpty { wellKnown(graph, exclude = avoid) }
            .take(SPOTLIGHT_POOL)
        if (wide.isEmpty()) return null
        return Pick(wide[Random(seed(day, SALT_TODAY)).nextInt(wide.size)], emptyList())
    }

    /**
     * Which of [anchors] a stored pick sits beside, strongest first.
     *
     * Today's pick is stored, but why it was picked is not — it is re-derived,
     * so a pick that is still today's after the listener's genres changed says
     * what is true now.
     */
    fun explain(graph: GenreGraph, genreId: String, anchors: List<String>): List<GenreNode> =
        anchors.take(MAX_ANCHORS)
            .mapNotNull { anchor ->
                graph.neighbours(anchor, maxHops = 2, floor = NEAR_FLOOR)
                    .firstOrNull { it.node.id == genreId }
                    ?.let { related -> graph[anchor]?.let { it to related.weight } }
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(2)

    /**
     * The genre of the day: a genre with a researched history ([withHistory]),
     * other than [exclude] (today's discovery, so the page never offers the
     * same genre twice).
     *
     * Drawn from the listener's wider neighbourhood — three steps out — when
     * that holds enough documented genres to rotate through, and from the best
     * known documented genres on the whole map otherwise. Ranked by how many
     * people tag a genre, because a spotlight on something nobody has heard of
     * is a paragraph nobody finishes.
     */
    fun spotlight(
        graph: GenreGraph,
        withHistory: Set<String>,
        anchors: List<String>,
        exclude: Set<String>,
        day: Long,
    ): GenreNode? {
        if (withHistory.isEmpty()) return null
        val around = anchors.take(MAX_ANCHORS)
            .flatMap { graph.neighbours(it, maxHops = 3, floor = 0.12f) }
            .map { it.node }
            .filter { it.id in withHistory && it.id !in exclude && it.id !in anchors }
            .distinctBy { it.id }
        val pool = if (around.size >= ROTATION) {
            around.sortedWith(byReach).take(SPOTLIGHT_POOL)
        } else {
            wellKnown(graph, exclude = exclude)
                .filter { it.id in withHistory }
                .take(SPOTLIGHT_POOL)
        }
        if (pool.isEmpty()) return null
        return pool[Random(seed(day, SALT_SPOTLIGHT)).nextInt(pool.size)]
    }

    private class Candidate(val node: GenreNode, var score: Float, val links: MutableList<Pair<GenreNode, Float>>) {
        val via: List<GenreNode> get() = links.sortedByDescending { it.second }.map { it.first }.take(2)
    }

    private fun nearby(graph: GenreGraph, anchors: List<String>, exclude: Set<String>): List<Candidate> {
        val anchorSet = anchors.toSet()
        val byId = LinkedHashMap<String, Candidate>()
        for (anchorId in anchors.take(MAX_ANCHORS)) {
            val anchor = graph[anchorId] ?: continue
            for (related in graph.neighbours(anchorId, maxHops = 2, floor = NEAR_FLOOR)) {
                val id = related.node.id
                if (id in anchorSet || id in exclude) continue
                // A family root ("Rock", "Electronic") is a direction, not a
                // discovery: everybody has heard of it and it plays as anything.
                if (related.node.parents.isEmpty()) continue
                val candidate = byId.getOrPut(id) { Candidate(related.node, 0f, ArrayList()) }
                candidate.score += related.weight
                candidate.links.add(anchor to related.weight)
            }
        }
        return byId.values.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.node.reach ?: -1 }
                .thenBy { it.node.id },
        )
    }

    /** Genres people have heard of, best known first, roots left out. */
    private fun wellKnown(graph: GenreGraph, exclude: Set<String>): List<GenreNode> =
        graph.allGenres
            .filter { it.parents.isNotEmpty() && it.reach != null && it.id !in exclude }
            .sortedWith(byReach)

    private val byReach = compareByDescending<GenreNode> { it.reach ?: -1 }.thenBy { it.id }

    // Two salts, so the two picks of one day are independent draws rather than
    // the same index into two lists.
    private const val SALT_TODAY = 0x5EED_0001L
    private const val SALT_SPOTLIGHT = 0x5EED_0002L

    private fun seed(day: Long, salt: Long): Long = day * 0x9E3779B97F4A7C15uL.toLong() xor salt
}
