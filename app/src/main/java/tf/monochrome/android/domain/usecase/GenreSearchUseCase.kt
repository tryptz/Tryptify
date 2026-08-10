package tf.monochrome.android.domain.usecase

import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.domain.model.GenreNode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Find the genre someone means from what they typed.
 *
 * 771 genres is well past the number you can put in a row and expect anyone to
 * scroll, and the ones a listener wants are rarely the ones a static row would
 * pick. Typing is the only interface that reaches all of them.
 *
 * The graph already knows how to resolve a name — `dnb`, `d&b`, `drum n bass`
 * and 2,184 MusicBrainz spellings all land on the right node — so an exact hit
 * is delegated to it rather than reimplemented. What this adds is the partial
 * case, which is most of typing: "har" while you are still reaching for
 * hardstyle, "liquid" for a genre whose real name is liquid drum & bass, or
 * "techo" because the phone's keyboard is small.
 */
@Singleton
class GenreSearchUseCase @Inject constructor(
    private val genreGraph: GenreGraphRepository,
) {
    /**
     * Genres matching [query], best first.
     *
     * A blank query is not an empty result — it is the question "what is there?",
     * answered with the genres most people listen to, so the row is useful
     * before anyone types anything.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<GenreNode> {
        val graph = genreGraph.graph
        val exact = query.takeIf { it.isNotBlank() }?.let { graph.resolve(it) }
        return rankGenres(graph.allGenres, query, limit, exactId = exact?.id)
    }

    companion object {
        const val DEFAULT_LIMIT = 24
    }
}

/**
 * Score every genre against [query] and return the best [limit].
 *
 * Separated from the use case so it can be tested against the real 771-node
 * dataset without Android in the way. [exactId] is the graph's own verdict,
 * folded in as the strongest possible signal — it knows about aliases and the
 * MusicBrainz vocabulary, which raw string matching here does not.
 */
internal fun rankGenres(
    nodes: List<GenreNode>,
    query: String,
    limit: Int,
    exactId: String? = null,
): List<GenreNode> {
    val needle = fold(query)
    if (needle.isEmpty()) {
        // Nothing typed: the most-listened genres, which is the most useful
        // thing a row can show before it knows anything about the person.
        return nodes.sortedByDescending { it.reach ?: 0 }.take(limit)
    }

    return nodes
        .mapNotNull { node ->
            val score = scoreGenre(node, needle, exactId)
            if (score <= 0) null else node to score
        }
        // Popularity breaks ties, so "har" leads with hardstyle rather than
        // whichever obscure genre happens to sort first alphabetically.
        .sortedWith(
            compareByDescending<Pair<GenreNode, Int>> { it.second }
                .thenByDescending { ln(1.0 + (it.first.reach ?: 0)) }
                .thenBy { it.first.name.length }
        )
        .take(limit)
        .map { it.first }
}

private fun scoreGenre(node: GenreNode, needle: String, exactId: String?): Int {
    if (node.id == exactId) return 1_000

    val names = buildList {
        add(node.name)
        addAll(node.aka)
        add(node.id.replace('-', ' '))
    }.map { fold(it) }.filter { it.isNotEmpty() }

    var best = 0
    for (name in names) {
        // The scoring order is the order of confidence, and the gaps between
        // tiers are wide enough that popularity can reorder within a tier but
        // never promote a weaker kind of match over a stronger one.
        val score = when {
            name == needle -> 900
            name.startsWith(needle) -> 700
            name.split(' ').any { it.startsWith(needle) } -> 600
            name.contains(needle) -> 400
            needle.length >= 4 && withinOneEdit(name, needle) -> 250
            else -> 0
        }
        if (score > best) best = score
    }

    // Every word typed appears somewhere in the name, in any order — this is
    // what makes "drum bass" find drum & bass and "hard uk" find UK hardcore.
    if (best == 0) {
        val words = needle.split(' ').filter { it.isNotEmpty() }
        if (words.size > 1 && names.any { name -> words.all { name.contains(it) } }) {
            best = 350
        }
    }
    return best
}

/**
 * Whether [needle] is within one edit of [name], or of a word or prefix of it.
 *
 * Gated to needles of four characters or more by the caller: at three, nearly
 * every genre is one edit from every other and the row fills with noise.
 */
private fun withinOneEdit(name: String, needle: String): Boolean {
    if (editDistanceAtMostOne(name, needle)) return true
    // The start of a longer name, so a typo still matches "hardstyle" when the
    // node is called "hardstyle something".
    for (length in intArrayOf(needle.length - 1, needle.length, needle.length + 1)) {
        if (length in 1..name.length && editDistanceAtMostOne(name.take(length), needle)) return true
    }
    // Or any single word of a multi-word name.
    return name.split(' ').any { editDistanceAtMostOne(it, needle) }
}

/**
 * Damerau distance of at most one: a substitution, an insertion, a deletion,
 * **or a transposition**.
 *
 * The transposition case is the reason this isn't plain Levenshtein. On a phone
 * keyboard swapping two adjacent letters is the most common typo there is —
 * "hardstlye" for hardstyle — and to Levenshtein that is two edits, so a
 * distance-one check would miss exactly the mistake people make most.
 */
private fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (a == b) return true
    val shorter = minOf(a.length, b.length)
    if (maxOf(a.length, b.length) - shorter > 1) return false

    var i = 0
    while (i < shorter && a[i] == b[i]) i++
    if (i == shorter) return true

    if (a.length == b.length) {
        if (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i]) {
            return a.substring(i + 2) == b.substring(i + 2)
        }
        return a.substring(i + 1) == b.substring(i + 1)
    }
    return if (a.length < b.length) {
        a.substring(i) == b.substring(i + 1)
    } else {
        a.substring(i + 1) == b.substring(i)
    }
}

/** Lowercase, alphanumerics and single spaces — the same folding the graph uses. */
private fun fold(raw: String): String = buildString {
    for (ch in raw.lowercase()) {
        if (ch.isLetterOrDigit()) append(ch)
        else if (isNotEmpty() && last() != ' ') append(' ')
    }
}.trim()
