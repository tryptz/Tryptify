package tf.monochrome.android.data.playlistfix

import tf.monochrome.android.domain.model.Track
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The song a playlist row says it is — its title, artist and length as stored —
 * which is what a repaired or regenerated row has to go on being. Built from the
 * row, never from whatever its id happens to play today.
 */
data class SongTarget(
    val title: String,
    val artist: String,
    val durationSeconds: Int = 0,
    val album: String? = null,
)

/**
 * Decides whether a catalogue search hit is the same song as a [SongTarget].
 *
 * The playlist importer used to take a search's first hit, and a search for
 * "Purity Steve Dekay" ranks the original mix, the radio mix and somebody's
 * remix in whatever order the service likes. A hit is accepted here only when
 * all of these agree:
 *
 *  - **Title.** Compared after folding case, accents, punctuation and "&", and
 *    with featured-artist credits taken out ("(feat. X)", "ft. X").
 *  - **Version.** "(Space Club Mix)", "— Radio Mix" and "- Live" are versions;
 *    two different ones are two different songs. Labels that name no different
 *    recording — remaster years, "Original Mix", "Album Version", "Deluxe" —
 *    are ignored. Services disagree on whether to show a version at all (TIDAL's
 *    search results carry none; Qobuz appends it after an em dash), so a version
 *    on one side only is accepted when the lengths agree.
 *  - **Artist.** One of the row's credited artists is one of the hit's.
 *  - **Length.** Within a few seconds when both are known.
 *
 * Among the hits that pass, the closest wins and ties go to the service's own
 * order. Nothing here touches the network; it is pure so it can be tested.
 */
object TrackMatcher {

    /** Minimum title similarity, after versions and credits are taken out. */
    const val MIN_TITLE = 0.85

    /** Minimum similarity between the best pair of credited artists. */
    const val MIN_ARTIST = 0.8

    /** Lengths this close are the same recording. */
    private const val SAME_LENGTH_S = 3

    /** Score of a match whose length can't be compared. */
    private const val UNKNOWN_LENGTH_SCORE = 0.6

    /**
     * How well [candidate] matches [target], from 0 to about 1, or null when it
     * is not the same song.
     */
    fun score(target: SongTarget, candidate: Track): Double? {
        val wanted = parseTitle(target.title)
        val found = parseTitle(candidate.title)
        if (wanted.base.isEmpty() || found.base.isEmpty()) return null

        val title = similarity(wanted.base, found.base)
        if (title < MIN_TITLE) return null

        val artist = artistScore(target.artist, candidate)
        if (artist < MIN_ARTIST) return null

        val lengthDiff = lengthDiff(target.durationSeconds, candidate.duration)
        val length = when {
            lengthDiff == null -> UNKNOWN_LENGTH_SCORE
            lengthDiff > maxLengthDiff(target.durationSeconds) -> return null
            lengthDiff <= SAME_LENGTH_S -> 1.0
            lengthDiff <= 8 -> 0.8
            else -> 0.5
        }

        val version = versionFactor(wanted.versions, found.versions, lengthDiff) ?: return null

        val album = target.album?.takeIf { it.isNotBlank() }?.let { wantedAlbum ->
            val foundAlbum = candidate.album?.title.orEmpty()
            if (foundAlbum.isNotBlank() && parseTitle(wantedAlbum).base == parseTitle(foundAlbum).base) 0.05 else 0.0
        } ?: 0.0

        return (0.45 * title + 0.35 * artist + 0.2 * length) * version + album
    }

    /** True when [candidate] is the same song as [target]. */
    fun matches(target: SongTarget, candidate: Track): Boolean = score(target, candidate) != null

    /**
     * The best match for [target] among [candidates], or null when none is the
     * same song. Equal scores keep the service's own order.
     */
    fun best(target: SongTarget, candidates: List<Track>): Track? {
        var best: Track? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val s = score(target, candidate) ?: continue
            if (s > bestScore) {
                best = candidate
                bestScore = s
            }
        }
        return best
    }

    /**
     * Search queries for [target], most specific first: the title with its
     * version and the first credited artist, then — only when the title has a
     * version — the bare title and artist, for services that index the version
     * apart from the title.
     */
    fun queries(target: SongTarget): List<String> {
        val artist = splitArtistsRaw(target.artist).firstOrNull().orEmpty()
        val withoutCredits = stripCredits(target.title)
        val full = withoutCredits.replace(BRACKETS_OR_DASHES, " ").collapseSpaces()
        val bare = splitVersion(withoutCredits.replace(BRACKETED, " ").collapseSpaces()).first.collapseSpaces()
        return listOf("$full $artist", "$bare $artist")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ── Titles ────────────────────────────────────────────────────────────

    /** A title folded for comparison: the name, and the versions that matter. */
    internal data class TitleParts(val base: String, val versions: Set<String>)

    internal fun parseTitle(raw: String): TitleParts {
        val versions = mutableListOf<String>()
        var rest = raw
        // Bracketed parts: a credit ("feat. X") is dropped, anything else is a version.
        BRACKETED.findAll(raw).forEach { m ->
            val inner = m.groupValues[1].trim()
            if (!CREDIT_START.containsMatchIn(inner)) versions += inner
        }
        rest = rest.replace(BRACKETED, " ")
        // Unbracketed credit: "Song feat. X" — everything from the credit on.
        rest = rest.replace(INLINE_CREDIT, " ")
        // "Song — Radio Mix", "Song - Live": a version only when it reads like one,
        // so "Part 1 - The Beginning" keeps its subtitle.
        val (name, suffix) = splitVersion(rest)
        if (suffix != null) versions += suffix
        return TitleParts(
            base = fold(name),
            versions = versions.mapNotNull { versionKey(it) }.toSet(),
        )
    }

    /** Splits a trailing " - Version" off [title] when it reads like a version. */
    private fun splitVersion(title: String): Pair<String, String?> {
        val m = DASH_SUFFIX.find(title) ?: return title to null
        val suffix = m.groupValues[1].trim()
        val words = fold(suffix).split(' ')
        val isVersion = words.any { it in VERSION_WORDS || YEAR.matches(it) } ||
            CREDIT_START.containsMatchIn(suffix)
        if (!isVersion) return title to null
        val name = title.substring(0, m.range.first)
        // A credit after the dash is dropped, like a bracketed one.
        return name to suffix.takeUnless { CREDIT_START.containsMatchIn(it) }
    }

    /**
     * The words of a version that tell recordings apart, or null when it names
     * none: "Remastered 2011", "Original Mix" and "Album Version" all say "the
     * usual recording", while "Radio Mix" and "Space Club Mix" do not.
     */
    internal fun versionKey(raw: String): String? {
        val folded = fold(raw)
        if (folded.isEmpty()) return null
        // "From the Motion Picture …" credits a source, not a recording.
        if (folded.startsWith("from ")) return null
        val words = folded.split(' ').filter { w ->
            w !in NOISE_WORDS && !YEAR.matches(w) && !ORDINAL.matches(w)
        }
        val telling = words.filter { it !in GENERIC_WORDS }
        return telling.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(" ")
    }

    /**
     * How much two sets of versions agree, as a factor on the score, or null
     * when they make two different songs.
     */
    private fun versionFactor(wanted: Set<String>, found: Set<String>, lengthDiff: Int?): Double? {
        if (wanted == found) return 1.0
        val sameLength = lengthDiff != null && lengthDiff <= SAME_LENGTH_S
        if (wanted.isEmpty() || found.isEmpty()) {
            // One side shows no version. The same length says it is the same cut;
            // with no length to go on it is only a weak match.
            return when {
                sameLength -> 0.8
                lengthDiff == null -> 0.7
                else -> null
            }
        }
        // Both name a version. They must be the same words, give or take one,
        // and then only when the lengths agree ("Radio Edit" vs "Radio Mix").
        val w = wanted.flatMap { it.split(' ') }.toSet()
        val f = found.flatMap { it.split(' ') }.toSet()
        val overlap = (w intersect f).size.toDouble() / (w union f).size
        return if (overlap >= 0.5 && sameLength) 0.85 else null
    }

    // ── Artists ───────────────────────────────────────────────────────────

    private fun artistScore(wantedRaw: String, candidate: Track): Double {
        val wanted = splitArtists(wantedRaw)
        if (wanted.isEmpty()) return 1.0
        val names = buildList {
            candidate.artists.forEach { addAll(splitArtists(it.name)) }
            candidate.artist?.name?.let { addAll(splitArtists(it)) }
        }.distinct()
        if (names.isEmpty()) return 0.0
        if (fold(wantedRaw) == fold(candidate.displayArtist)) return 1.0
        var best = 0.0
        for (w in wanted) for (n in names) best = max(best, nameSimilarity(w, n))
        return best
    }

    private fun nameSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val ta = a.split(' ').filter { it != "the" }
        val tb = b.split(' ').filter { it != "the" }
        if (ta == tb && ta.isNotEmpty()) return 1.0
        return similarity(a, b)
    }

    /** Folded artist names credited in [raw] ("A, B & C feat. D" → a, b, c, d). */
    internal fun splitArtists(raw: String): List<String> =
        splitArtistsRaw(raw).map { fold(it) }.filter { it.isNotEmpty() }.distinct()

    private fun splitArtistsRaw(raw: String): List<String> =
        raw.split(ARTIST_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    // ── Text ──────────────────────────────────────────────────────────────

    /**
     * Lower case, accents off Latin letters, "&" and "+" as "and", apostrophes
     * dropped and every other mark a space. Letters of every script are kept,
     * and so are the marks that change a kana or hangul syllable.
     */
    internal fun fold(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var previous = ' '
        for (ch in decomposed) {
            val type = Character.getType(ch)
            when {
                type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() -> {
                    // é → e, but が stays が: a dakuten is part of the letter.
                    if (previous.code >= 0x250) out.append(ch)
                }
                ch == '&' || ch == '+' -> out.append(" and ")
                ch == '\'' || ch == '’' || ch == '`' -> Unit
                Character.isLetterOrDigit(ch) -> out.append(ch.lowercaseChar())
                else -> out.append(' ')
            }
            if (Character.isLetterOrDigit(ch)) previous = ch
        }
        return out.toString().collapseSpaces()
    }

    /** 1.0 for equal strings, falling with edit distance and with words not shared. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val edit = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val dice = 2.0 * (ta intersect tb).size / (ta.size + tb.size)
        return max(edit, dice)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun lengthDiff(a: Int, b: Int): Int? = if (a > 0 && b > 0) abs(a - b) else null

    /** 15 s, or 5% of a long mix — a 9-minute track's masters drift further. */
    private fun maxLengthDiff(seconds: Int): Int = max(15, seconds / 20)

    private fun stripCredits(title: String): String =
        title.replace(BRACKETED) { m -> if (CREDIT_START.containsMatchIn(m.groupValues[1])) " " else m.value }
            .replace(INLINE_CREDIT, " ")

    private fun String.collapseSpaces(): String = trim().replace(SPACES, " ")

    private val SPACES = Regex("\\s+")
    private val BRACKETED = Regex("""[(\[{（【]([^)\]}）】]*)[)\]}）】]""")
    private val BRACKETS_OR_DASHES = Regex("""[()\[\]{}（）【】]|\s[-–—]\s""")
    private val DASH_SUFFIX = Regex("""\s[-–—]\s(.+)$""")
    private val CREDIT_START = Regex("""^\s*(feat\.?|ft\.?|featuring|with)\s""", RegexOption.IGNORE_CASE)
    private val INLINE_CREDIT = Regex("""\s(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    // No "/": it would split AC/DC, and nothing credits two artists with it.
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|;|&|\+|\s(?:x|vs\.?|feat\.?|ft\.?|featuring|with|and)\s)\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR = Regex("""(19|20)\d\d""")
    private val ORDINAL = Regex("""\d+(st|nd|rd|th)""")

    /** Words that mark a dash suffix as a version rather than part of the title. */
    private val VERSION_WORDS = setOf(
        "mix", "remix", "rmx", "edit", "version", "live", "acoustic", "instrumental",
        "demo", "remaster", "remastered", "extended", "radio", "club", "dub", "vip",
        "bootleg", "rework", "cover", "karaoke", "sped", "slowed", "reprise",
        "unplugged", "session", "sessions", "orchestral", "acapella", "cappella",
        "mono", "stereo", "original", "edition", "deluxe", "bonus", "anniversary", "mixed",
    )

    /** Words that say "the usual recording" and tell nothing apart. */
    private val NOISE_WORDS = setOf(
        "remaster", "remastered", "remastering", "digitally", "digital", "deluxe",
        "edition", "expanded", "anniversary", "bonus", "track", "explicit", "clean",
        "original", "album", "mono", "stereo", "version", "ver", "lp", "single",
    )

    /** Words that only mean something next to another ("Radio Mix", not "Mix"). */
    private val GENERIC_WORDS = setOf("mix", "the", "a", "an", "and")
}
