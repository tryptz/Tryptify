package tf.monochrome.android.ui.eq

import java.text.Normalizer

/**
 * The headphone list's search, forgiving of how a model is typed.
 *
 * A plain "name contains query" missed "HD 600" for "hd600", "Sennheiser HD
 * 600" for "sennheiser 600", and anything with a typo. Here, in order:
 *
 * 1. Spaces and punctuation do not count: "hd600", "HD  600" and "hd-600"
 *    all find "Sennheiser HD 600", "wh1000xm4" finds "Sony WH-1000XM4".
 * 2. Each word typed is found on its own, in any order, in the name or in
 *    the measurement's source ("hd600 oratory").
 * 3. Only when nothing matches that way: a word of letters may be misspelt
 *    (one slip up to six letters, two from seven), against the start of a
 *    word of the name, so a half-typed word with a slip is found too. Words
 *    with digits never are: "hd 660" must not find the HD 600. Short words
 *    (three letters and under) never are either: too much would match.
 *
 * Pure, so `HeadphoneSearchTest` holds it without the screen.
 */
internal object HeadphoneSearch {

    /** A searchable text, prepared once per list rather than per keystroke. */
    class Entry(val words: List<String>, val compact: String)

    class Query(val compact: String, val tokens: List<String>)

    enum class Match { NONE, EXACT, FUZZY }

    private val marks = Regex("\\p{Mn}+")

    /** Lowercase words of letters and digits, accents dropped, split at everything else. */
    fun words(text: String): List<String> {
        val plain = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (c in plain) {
            if (c.isLetterOrDigit()) {
                word.append(c)
            } else if (word.isNotEmpty()) {
                out += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    fun entry(text: String): Entry {
        val w = words(text)
        return Entry(w, w.joinToString(""))
    }

    /** The query for [text]; null when it has nothing to search for. */
    fun query(text: String): Query? {
        val w = words(text)
        if (w.isEmpty()) return null
        return Query(w.joinToString(""), w)
    }

    fun match(query: Query, name: Entry, extra: Entry? = null): Match {
        if (name.compact.contains(query.compact)) return Match.EXACT
        var fuzzy = false
        for (token in query.tokens) {
            if (name.compact.contains(token) || extra?.compact?.contains(token) == true) continue
            if (fuzzyFound(token, name) || (extra != null && fuzzyFound(token, extra))) {
                fuzzy = true
                continue
            }
            return Match.NONE
        }
        return if (fuzzy) Match.FUZZY else Match.EXACT
    }

    /**
     * [items] that match [text], in their own order: the exact matches, or
     * when there are none, the near ones. Blank [text] keeps everything.
     */
    fun <T> filter(items: List<T>, text: String, name: (T) -> Entry, extra: (T) -> Entry? = { null }): List<T> {
        val q = query(text) ?: return items
        val exact = ArrayList<T>()
        val near = ArrayList<T>()
        for (item in items) {
            when (match(q, name(item), extra(item))) {
                Match.EXACT -> exact += item
                Match.FUZZY -> near += item
                Match.NONE -> Unit
            }
        }
        return exact.ifEmpty { near }
    }

    /** How many slips a word of [length] letters may have; 0 for none. */
    fun allowedTypos(length: Int): Int = when {
        length < 4 -> 0
        length < 7 -> 1
        else -> 2
    }

    private fun fuzzyFound(token: String, entry: Entry): Boolean {
        if (token.any { !it.isLetter() }) return false
        val allowed = allowedTypos(token.length)
        if (allowed == 0) return false
        return entry.words.any { prefixDistance(token, it) <= allowed }
    }

    /**
     * The fewest single-letter edits (insert, delete, replace, or swap two
     * neighbours) turning [query] into the start of [word]: "senhe" is one
     * from "sennheiser", "sennhieser" one from it. Words much shorter than the
     * query can never be close and are skipped.
     */
    fun prefixDistance(query: String, word: String): Int {
        val n = query.length
        val m = word.length
        if (m < n - 2) return Int.MAX_VALUE
        // d[i][j]: edits between query[0, i) and word[0, j).
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (query[i - 1] == word[j - 1]) 0 else 1
                var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && query[i - 1] == word[j - 2] && query[i - 2] == word[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        // The word may go on past what was typed.
        return d[n].min()
    }
}
