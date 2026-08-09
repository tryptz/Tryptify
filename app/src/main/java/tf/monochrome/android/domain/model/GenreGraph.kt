package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * One genre, as the app understands it.
 *
 * [bpm] of `0..0` is the deliberate "tempo says nothing useful here" marker —
 * free jazz, drone, most classical — not missing data. Readers must treat it as
 * "don't filter on tempo" rather than as a range.
 */
@Serializable
data class GenreNode(
    val id: String,
    val name: String,
    val family: String = "",
    val parents: List<String> = emptyList(),
    val aka: List<String> = emptyList(),
    val bpm: List<Int> = emptyList(),
    val era: List<Int?> = emptyList(),
    /** Arousal, 0..1 — how activating this is. */
    val energy: Float = 0.5f,
    /** Pleasantness, 0..1 — dark and tense at 0, bright and euphoric at 1. */
    val valence: Float = 0.5f,
    val moods: List<String> = emptyList(),
    /**
     * Baked map position, in layout units — absolute, not polar.
     *
     * The map is a dozen radial clusters, one per family, arranged so the
     * families that share the most genres end up adjacent and the fusions lean
     * into the gaps between them. That can't be expressed as an angle around a
     * single origin, so the build script emits coordinates and the renderer
     * only scales and centres them.
     */
    val x: Float = 0f,
    val y: Float = 0f,
    /** Depth in the parent forest, 0 at a root. Drives dot size and labelling. */
    val ring: Int = 0,
    /** Sideways neighbours as `[id, weight]`; parent/child links are implicit. */
    val near: List<List<@Serializable(with = IdOrWeightSerializer::class) IdOrWeight>> = emptyList(),
) {
    val bpmLow: Int get() = bpm.getOrNull(0) ?: 0
    val bpmHigh: Int get() = bpm.getOrNull(1) ?: 0
    val hasTempo: Boolean get() = bpmLow > 0 && bpmHigh >= bpmLow

    /** Search terms for this genre, most specific first. */
    fun queries(): List<String> = (listOf(name) + aka).distinct()

    /** Sideways neighbours as `id to weight`, strongest first. */
    fun nearEdges(): List<Pair<String, Float>> = near.mapNotNull { entry ->
        val id = entry.getOrNull(0)?.asId() ?: return@mapNotNull null
        val weight = entry.getOrNull(1)?.asWeight() ?: return@mapNotNull null
        id to weight
    }
}

/**
 * A named listening context, and the genres that serve it.
 *
 * The weights are "how central is this genre to this mood" — the feed builder
 * takes from the top and works outward as the adventure knob rises, so the
 * ordering here is what a listener sees first.
 */
@Serializable
data class MoodProfile(
    val id: String,
    val label: String,
    val bpm: List<Int> = emptyList(),
    val energy: List<Float> = emptyList(),
    val genres: List<List<@Serializable(with = IdOrWeightSerializer::class) IdOrWeight>> = emptyList(),
) {
    val energyLow: Float get() = energy.getOrNull(0) ?: 0f
    val energyHigh: Float get() = energy.getOrNull(1) ?: 1f
}

@Serializable
data class GenreFamily(val id: String, val name: String)

/**
 * The bundled genre knowledge graph.
 *
 * Two structures the app didn't have before: a *vocabulary* (what counts as a
 * genre name, including every spelling a listener might type) and a *topology*
 * (what sits next to what, and how far away). Between them they turn "Late
 * night" from an opaque search string into a set of genres with tempo and
 * energy bounds, and turn "dnb" into drum & bass rather than a literal search
 * for the letters d-n-b.
 *
 * Built once from the asset and held for the process lifetime — it is ~80 KB of
 * JSON and entirely immutable, so there is nothing to invalidate.
 */
@Serializable
data class GenreGraphData(
    val version: Int = 1,
    val attribution: String = "",
    val families: List<GenreFamily> = emptyList(),
    val genres: List<GenreNode> = emptyList(),
    val moods: List<MoodProfile> = emptyList(),
)

/** A genre reachable from some starting point, with how strongly it relates. */
data class RelatedGenre(val node: GenreNode, val weight: Float, val hops: Int)

class GenreGraph(private val data: GenreGraphData, private val vocabulary: Map<String, String?>) {

    private val byId: Map<String, GenreNode> = data.genres.associateBy { it.id }

    /**
     * Every spelling that identifies a genre, folded to a comparison key.
     *
     * Built from names, ids and aliases. First writer wins, which is why the
     * dataset validator refuses duplicate aliases — otherwise which genre
     * "juke" meant would depend on list order.
     */
    private val aliasIndex: Map<String, String> = buildMap {
        for (node in data.genres) {
            for (label in listOf(node.name, node.id.replace('-', ' ')) + node.aka) {
                putIfAbsent(fold(label), node.id)
                putIfAbsent(tighten(label), node.id)
            }
        }
    }

    /** MusicBrainz names that aren't curated, mapped to their nearest curated node. */
    private val vocabularyIndex: Map<String, String> = buildMap {
        for ((name, target) in vocabulary) {
            if (target != null && target in byId) putIfAbsent(fold(name), target)
        }
    }

    private val moodsById: Map<String, MoodProfile> = data.moods.associateBy { it.id }

    val allGenres: List<GenreNode> get() = data.genres
    val moods: List<MoodProfile> get() = data.moods
    val attribution: String get() = data.attribution
    val size: Int get() = data.genres.size

    operator fun get(id: String): GenreNode? = byId[id]

    fun mood(id: String): MoodProfile? = moodsById[id]

    /**
     * Resolve free text to a genre.
     *
     * In descending confidence: an exact fold of a curated name or alias, the
     * same with spacing removed ("drumandbass"), a MusicBrainz name mapped to
     * its nearest curated node, then the longest curated name that is a
     * trailing word-run of the input — so "progressive psytrance" finds
     * psytrance and "uk hip hop" finds hip-hop. Returns null rather than
     * guessing: a wrong genre is worse than none, because everything
     * downstream treats a hit as fact.
     */
    fun resolve(text: String): GenreNode? {
        val folded = fold(text)
        if (folded.isEmpty()) return null

        aliasIndex[folded]?.let { return byId[it] }
        aliasIndex[tighten(text)]?.let { return byId[it] }
        vocabularyIndex[folded]?.let { return byId[it] }

        val words = folded.split(' ')
        if (words.size > 1) {
            // Longest tail first: "drum and bass" must beat "bass".
            for (start in 1 until words.size) {
                val tail = words.subList(start, words.size).joinToString(" ")
                aliasIndex[tail]?.let { return byId[it] }
                vocabularyIndex[tail]?.let { return byId[it] }
            }
        }
        return null
    }

    /** True when this text names a genre we know, curated or merely recognised. */
    fun isGenreTerm(text: String): Boolean = resolve(text) != null

    /**
     * Genres within [maxHops] of [id], strongest first, excluding the start.
     *
     * Edges are traversed in three directions with different costs, because
     * they mean different things: a child is very close to its parent (a fan of
     * techno usually accepts hard techno), a parent is close to its child but
     * broader, and a sideways neighbour is as close as its authored weight
     * says. Weights multiply along a path, so two hops through weak edges ranks
     * below one hop through a strong one — which is exactly the behaviour the
     * adventure knob needs.
     */
    fun neighbours(id: String, maxHops: Int = 1, floor: Float = 0.15f): List<RelatedGenre> {
        val start = byId[id] ?: return emptyList()
        val best = HashMap<String, RelatedGenre>()
        var frontier = listOf(RelatedGenre(start, 1f, 0))

        repeat(maxHops.coerceAtLeast(0)) {
            val next = ArrayList<RelatedGenre>()
            for (current in frontier) {
                for ((neighbourId, edge) in edgesFrom(current.node)) {
                    val node = byId[neighbourId] ?: continue
                    if (node.id == start.id) continue
                    val weight = current.weight * edge
                    if (weight < floor) continue
                    val existing = best[node.id]
                    if (existing == null || existing.weight < weight) {
                        val related = RelatedGenre(node, weight, current.hops + 1)
                        best[node.id] = related
                        next.add(related)
                    }
                }
            }
            frontier = next
            if (frontier.isEmpty()) return@repeat
        }

        return best.values.sortedWith(compareByDescending<RelatedGenre> { it.weight }.thenBy { it.node.id })
    }

    /**
     * The genres a mood asks for, expanded outward by [maxHops].
     *
     * A mood's own list always outranks anything reached by walking, so
     * widening the search adds to the tail rather than reshuffling the head —
     * turning the knob up should show you more, not something else.
     */
    fun genresForMood(moodId: String, maxHops: Int = 0, limit: Int = 12): List<RelatedGenre> {
        val mood = moodsById[moodId] ?: return emptyList()
        val out = LinkedHashMap<String, RelatedGenre>()

        for (entry in mood.genres) {
            val gid = entry.getOrNull(0)?.asId() ?: continue
            val weight = entry.getOrNull(1)?.asWeight() ?: continue
            byId[gid]?.let { out[gid] = RelatedGenre(it, weight, 0) }
        }
        if (maxHops > 0) {
            for (seed in out.values.toList()) {
                for (related in neighbours(seed.node.id, maxHops)) {
                    val scaled = related.copy(weight = related.weight * seed.weight * 0.8f)
                    val existing = out[related.node.id]
                    if (existing == null) out[related.node.id] = scaled
                }
            }
        }
        return out.values.sortedWith(
            compareBy<RelatedGenre> { it.hops }.thenByDescending { it.weight }
        ).take(limit)
    }

    /** Parent chain to a family root, nearest first. Empty when [id] is unknown. */
    fun ancestors(id: String): List<GenreNode> {
        val out = ArrayList<GenreNode>()
        val seen = HashSet<String>()
        var frontier = byId[id]?.parents.orEmpty()
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (pid in frontier) {
                if (!seen.add(pid)) continue
                val node = byId[pid] ?: continue
                out.add(node)
                next.addAll(node.parents)
            }
            frontier = next
        }
        return out
    }

    private fun edgesFrom(node: GenreNode): List<Pair<String, Float>> = buildList {
        for (entry in node.near) {
            val id = entry.getOrNull(0)?.asId() ?: continue
            val weight = entry.getOrNull(1)?.asWeight() ?: continue
            add(id to weight)
        }
        // A parent is broader than its child, so stepping up loses a little
        // more than stepping down does.
        for (parent in node.parents) add(parent to PARENT_EDGE)
        for (child in childrenOf(node.id)) add(child to CHILD_EDGE)
    }

    private val childIndex: Map<String, List<String>> by lazy {
        buildMap<String, MutableList<String>> {
            for (node in data.genres) {
                for (parent in node.parents) getOrPut(parent) { ArrayList() }.add(node.id)
            }
        }
    }

    private fun childrenOf(id: String): List<String> = childIndex[id].orEmpty()

    /** Direct descendants of [id], for the map's collapse/expand. */
    fun children(id: String): List<GenreNode> =
        childIndex[id].orEmpty().mapNotNull { byId[it] }.sortedBy { it.name }

    /** Family roots — the genres with no parent. */
    val roots: List<GenreNode> by lazy {
        data.genres.filter { it.parents.isEmpty() }.sortedBy { it.name }
    }

    fun family(id: String): GenreFamily? = data.families.firstOrNull { it.id == id }

    companion object {
        private const val PARENT_EDGE = 0.75f
        private const val CHILD_EDGE = 0.85f

        val EMPTY = GenreGraph(GenreGraphData(), emptyMap())

        /**
         * Comparison key: lowercase, `&` spelled out, punctuation collapsed.
         *
         * `&` becomes "and" before punctuation is stripped so "d&b", "d and b"
         * and "drum & bass" all reduce consistently.
         */
        fun fold(text: String): String = text
            .lowercase()
            .replace("&", " and ")
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() && it !in STOPWORDS }
            .joinToString(" ")

        /** As [fold], with spacing removed — matches "drumandbass" to "drum and bass". */
        fun tighten(text: String): String = fold(text).replace(" ", "")

        private val STOPWORDS = setOf("music", "style", "styles", "genre")
    }
}

/**
 * Union type for the `[id, weight]` pairs in the asset.
 *
 * The dataset stores heterogeneous JSON arrays because a pair reads far better
 * than `{"id":…,"weight":…}` repeated four thousand times; this is the smallest
 * thing that lets kotlinx decode them without inventing a wrapper object per
 * edge.
 */
@Serializable(with = IdOrWeightSerializer::class)
data class IdOrWeight(val text: String? = null, val number: Float? = null) {
    fun asId(): String? = text
    fun asWeight(): Float? = number ?: text?.toFloatOrNull()
}

object IdOrWeightSerializer : kotlinx.serialization.KSerializer<IdOrWeight> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "IdOrWeight", kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): IdOrWeight {
        val input = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: return IdOrWeight(text = decoder.decodeString())
        val element = input.decodeJsonElement()
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive
            ?: return IdOrWeight()
        return if (primitive.isString) IdOrWeight(text = primitive.content)
        else IdOrWeight(number = primitive.content.toFloatOrNull())
    }

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: IdOrWeight,
    ) {
        value.text?.let { encoder.encodeString(it) } ?: encoder.encodeFloat(value.number ?: 0f)
    }
}

@Serializable
data class GenreVocabulary(
    val version: Int = 1,
    val attribution: String = "",
    val names: Map<String, String?> = emptyMap(),
)
