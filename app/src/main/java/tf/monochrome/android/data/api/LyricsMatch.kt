package tf.monochrome.android.data.api

import java.text.Normalizer
import kotlin.math.abs

/**
 * What every metadata-searched lyrics source is asked for. [artist] is the
 * credit as the catalogue shows it ("Queen, David Bowie"); the matchers split
 * it themselves.
 */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
) {
    val durationSeconds: Int? get() = durationMs?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
}

/**
 * The matching rules shared by every lyrics source that finds its song by
 * search rather than by id.
 *
 * Two kinds of miss come out of search. Too strict, and "Song — Radio Edit" or
 * "Queen, David Bowie" finds nothing. Too loose, and the closest runtime wins
 * even when it is a live take 28 s longer — lyrics that are right but never in
 * time. A timed result for another recording is worse than none, because a
 * later source might have the right one, so a known runtime that disagrees by
 * more than [DURATION_TOLERANCE_MS] rejects the candidate outright.
 */
object LyricsMatch {
    /**
     * Masters of one recording differ by a second or two of lead-out; beyond
     * that it is a different edit, and its timings land early or late.
     */
    const val DURATION_TOLERANCE_MS = 3_000L

    /**
     * Words that make a recording a different performance or mix. A candidate
     * carrying one the track does not is another version, whatever its title.
     */
    private val VERSION_MARKERS = listOf(
        "live", "remix", "instrumental", "karaoke", "acoustic", "cover", "sped up", "speed up",
        "slowed", "nightcore", "reverb", "demo", "edit", "mix", "version", "unplugged", "acapella",
        "a cappella", "tribute", "orchestral", "piano", "现场", "伴奏", "翻唱",
    )

    private val FEAT_PAREN = Regex("""\s*[(\[](?:feat\.?|ft\.?|featuring|with|prod\.?)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val TRAILING_TAG = Regex("""\s*[(\[][^)\]]*[)\]]\s*$""")
    private val VERSION_DASH = Regex(
        """\s+-\s+[^-]*\b(?:remaster(?:ed)?|remix|edit|version|live|mono|stereo|mix|demo|deluxe|bonus|single|radio)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|、|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bx\b|\band\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)
    private val COMBINING = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Titles to search with, most specific first: as given, then without
     * Qobuz's em-dash version, a " - Remastered 2011" tail, featured artists
     * and a trailing tag. Catalogues disagree on all of these, and each
     * source stores only one spelling.
     */
    fun titleVariants(title: String): List<String> {
        val raw = title.trim()
        val noEmDash = raw.substringBefore(" — ").trim()
        val noDash = VERSION_DASH.replace(noEmDash, "").trim()
        val noFeat = FEAT_TAIL.replace(FEAT_PAREN.replace(noDash, ""), "").trim()
        val noTag = TRAILING_TAG.replace(noFeat, "").trim()
        return linkedSetOf(raw, noEmDash, noDash, noFeat, noTag).filter { it.isNotBlank() }
    }

    /** The title with every version decoration removed — what two spellings of one song share. */
    fun baseTitle(title: String): String = normalize(titleVariants(title).last())

    /** Lowercase, accents and punctuation folded away, so "ROSALÍA" and "Rosalia" compare equal. */
    fun normalize(text: String): String =
        COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")
            .lowercase()
            .replace(NON_WORD, " ")
            .trim()

    /** Every artist in a credit, normalised: "Queen, David Bowie" → [queen, david bowie]. */
    fun artists(credit: String): List<String> =
        credit.split(ARTIST_SPLIT).map { normalize(it) }.filter { it.isNotBlank() }

    /** The lead artist as typed, for sources that search better on one name than a credit list. */
    fun primaryArtist(credit: String): String =
        credit.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: credit.trim()

    fun titleMatches(queryTitle: String, candidateTitle: String?): Boolean {
        if (candidateTitle.isNullOrBlank()) return false
        val q = baseTitle(queryTitle)
        val c = baseTitle(candidateTitle)
        if (q.isEmpty() || c.isEmpty()) return false
        if (q == c) return true
        // Containment in whole words only, and only when the shorter side is
        // long enough to mean something: "Love" is not "Lovely", nor 晴天 晴天娃娃.
        val shorter = minOf(q.length, c.length)
        return shorter >= 4 && (" $q ".contains(" $c ") || " $c ".contains(" $q "))
    }

    fun artistMatches(queryCredit: String, candidateCredit: String?): Boolean {
        if (candidateCredit.isNullOrBlank()) return false
        val q = artists(queryCredit)
        val c = artists(candidateCredit)
        return q.any { a -> c.any { b -> a == b || (minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a))) } }
    }

    /** Unknown on either side counts as agreeing — only a known disagreement rejects. */
    fun durationMatches(trackMs: Long?, candidateMs: Long?): Boolean {
        if (trackMs == null || trackMs <= 0 || candidateMs == null || candidateMs <= 0) return true
        return abs(trackMs - candidateMs) <= DURATION_TOLERANCE_MS
    }

    /** True when [candidateTitle] names a live take, remix, cover … that [queryTitle] does not. */
    fun isOtherVersion(queryTitle: String, candidateTitle: String?): Boolean {
        if (candidateTitle.isNullOrBlank()) return false
        val q = " ${normalize(queryTitle)} "
        val c = " ${normalize(candidateTitle)} "
        return VERSION_MARKERS.any { marker ->
            val m = " ${normalize(marker)} "
            c.contains(m) && !q.contains(m)
        }
    }

    /**
     * The candidate that is this song, or null when none is. A candidate must
     * share the title, must not be another version, and must have a runtime
     * that agrees when both are known. Then either the artist or a known,
     * agreeing runtime has to confirm it — the runtime alone covers a credit
     * spelt in another script (周杰倫 / 周杰伦). Among the survivors, an exact
     * title and an artist match outrank a closer runtime.
     */
    fun <T> pick(
        query: LyricsQuery,
        candidates: List<T>,
        title: (T) -> String?,
        artist: (T) -> String?,
        durationMs: (T) -> Long?,
    ): T? {
        val target = query.durationMs?.takeIf { it > 0 }
        val queryBase = baseTitle(query.title)
        return candidates
            .asSequence()
            .filter { titleMatches(query.title, title(it)) }
            .filter { !isOtherVersion(query.title, title(it)) }
            .filter { durationMatches(target, durationMs(it)) }
            .map { c ->
                val artistOk = artistMatches(query.artist, artist(c))
                val runtimeKnown = target != null && (durationMs(c) ?: 0L) > 0L
                Triple(c, artistOk, runtimeKnown)
            }
            .filter { (_, artistOk, runtimeKnown) -> artistOk || runtimeKnown }
            .sortedWith(
                compareByDescending<Triple<T, Boolean, Boolean>> { baseTitle(title(it.first).orEmpty()) == queryBase }
                    .thenByDescending { it.second }
                    .thenBy { (c, _, _) ->
                        val d = durationMs(c)
                        if (target == null || d == null || d <= 0) Long.MAX_VALUE else abs(d - target)
                    },
            )
            .firstOrNull()
            ?.first
    }
}
