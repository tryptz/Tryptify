package tf.monochrome.android.data.api

import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter

/** One timed fragment as a source delivers it: a word, or part of one. */
data class TimedSyllable(val startMs: Long, val endMs: Long, val text: String)

/**
 * Shaping shared by the lyrics sources, so every one of them hands the player
 * the same thing TIDAL does.
 */
object LyricsText {

    /**
     * Joins syllables into the words the karaoke view draws.
     *
     * The view lays out one [LyricWord] per word and puts a space after each,
     * which is TIDAL's shape. Apple-sourced timing is per syllable — "long ",
     * "e", "nough" — with the space carried on the syllable that ends a word,
     * so drawn as delivered it reads "e nough". A word therefore runs until a
     * syllable ends in whitespace (or the next starts with it) and spans its
     * first syllable's start to its last one's end.
     *
     * Han and kana are the exception: those scripts put no space between
     * words, so joining on whitespace would make a whole line one word that
     * lights up at once. Each such character stays its own word, as NetEase
     * and Kugou already deliver them.
     */
    fun groupSyllables(syllables: List<TimedSyllable>, convertToRomaji: Boolean = false): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        var text = StringBuilder()
        var start = 0L
        var end = 0L
        fun flush() {
            var t = text.toString().trim()
            if (t.isNotEmpty()) {
                if (convertToRomaji) t = RomajiConverter.convert(t)
                words.add(LyricWord(startMs = start, endMs = maxOf(end, start), text = t))
            }
            text = StringBuilder()
        }
        syllables.forEachIndexed { i, syl ->
            if (syl.text.isEmpty()) return@forEachIndexed
            if (text.isEmpty() || syl.text.first().isWhitespace() || isUnspacedScript(syl.text.trim().firstOrNull())) {
                flush()
                start = syl.startMs
            }
            text.append(syl.text)
            end = syl.endMs
            val next = syllables.getOrNull(i + 1)?.text
            val endsWord = syl.text.last().isWhitespace() ||
                isUnspacedScript(syl.text.trim().lastOrNull()) ||
                next == null || next.firstOrNull()?.isWhitespace() == true
            if (endsWord) flush()
        }
        flush()
        return words
    }

    /** True when [text] has Han or kana in it — Chinese or Japanese. */
    fun hasUnspacedScript(text: String): Boolean = text.any { isUnspacedScript(it) }

    private fun isUnspacedScript(c: Char?): Boolean {
        if (c == null) return false
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> true
            else -> false
        }
    }

    /**
     * Credit lines that NetEase and Kugou time into the lyrics themselves —
     * "作词 : Max Martin", "Composed by：…" — which the view would otherwise
     * sing along to. A role word followed by a colon (either width) is
     * specific enough to drop wherever it appears.
     */
    private const val CREDIT_ROLES =
        """作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|製作人|监制|監製|混音|母带|母帶|和声|和聲|合声|合聲|和音|配唱|录音|錄音|""" +
            """吉他|贝斯|貝斯|鼓|键盘|鍵盤|弦乐|弦樂|打击乐|人声|统筹|統籌|企划|企劃|出品|发行|發行|OP|SP|""" +
            """lyrics(?:\s+by)?|lyricist|written\s+by|composed\s+by|composer|produced\s+by|producer|arranged\s+by|arranger|mixed\s+by|mastered\s+by|recorded\s+by"""

    /** "录音" + "工程", "合声" + "编写" … */
    private const val ROLE_SUFFIX = """(?:工程|工程师|工程師|助理|编写|編寫|师|師)?"""

    private val CREDIT_LINE = Regex("""^\s*(?:$CREDIT_ROLES)$ROLE_SUFFIX\s*[:：]""", RegexOption.IGNORE_CASE)

    /** The whole name, so a singer called "Spencer" is not the "SP" credit. */
    private val CREDIT_ROLE_NAME = Regex("""^\s*(?:$CREDIT_ROLES)$ROLE_SUFFIX\s*$""", RegexOption.IGNORE_CASE)

    /** A credit role ("录音工程", "Producer") rather than a singer's name. */
    fun isCreditRole(name: String): Boolean = name.isNotBlank() && CREDIT_ROLE_NAME.containsMatchIn(name)

    /**
     * Drops credit lines anywhere, and a leading "Title - Artist" header line
     * (Kugou opens most files with one), then rejects a result whose lyrics
     * run past the end of the track — the sign of a longer recording's
     * timings. Returns null when nothing sung is left.
     */
    fun clean(lyrics: Lyrics, query: LyricsQuery): Lyrics? {
        var lines = lyrics.lines.filterNot { CREDIT_LINE.containsMatchIn(it.text) }
        val first = lines.firstOrNull()
        if (first != null && isHeaderLine(first.text, query)) lines = lines.drop(1)
        if (lines.none { it.text.isNotBlank() }) return null
        val durationMs = query.durationMs
        if (lyrics.isSynced && durationMs != null && durationMs > 0) {
            val lastStart = lines.maxOf { it.timeMs }
            if (lastStart > durationMs + OVERRUN_TOLERANCE_MS) return null
        }
        return lyrics.copy(lines = lines)
    }

    /** Past the end by more than this, the timings belong to a longer cut. */
    private const val OVERRUN_TOLERANCE_MS = 5_000L

    /**
     * "Title - Artist" (or the reverse). Matched by shape, because the artist
     * is often spelt otherwise than the track has it — 周杰伦 (Jay Chou) for
     * 周杰倫 — and a sung line that merely repeats the title ("Hey - hey - hey")
     * has the title on both sides.
     */
    private fun isHeaderLine(text: String, query: LyricsQuery): Boolean {
        val title = LyricsMatch.baseTitle(query.title)
        if (title.isEmpty() || !text.contains(" - ")) return false
        val left = text.substringBefore(" - ")
        val right = text.substringAfter(" - ")
        fun titleOnly(side: String, other: String) =
            LyricsMatch.baseTitle(side) == title && !LyricsMatch.normalize(other).contains(title)
        if (titleOnly(left, right) || titleOnly(right, left)) return true
        val norm = LyricsMatch.normalize(text)
        return norm.contains(title) && LyricsMatch.artists(query.artist).any { norm.contains(it) }
    }

    /** True when at least one line carries per-word timing. */
    fun isWordTimed(lyrics: Lyrics?): Boolean = lyrics != null && lyrics.isSynced && lyrics.lines.any { it.words.isNotEmpty() }

    /** Line timings with no word timing. */
    fun isLineTimed(lyrics: Lyrics?): Boolean = lyrics != null && lyrics.isSynced && lyrics.lines.none { it.words.isNotEmpty() }

    /** A line built from grouped words; its text has no spaces between Han or kana characters. */
    fun lineOf(timeMs: Long, words: List<LyricWord>): LyricLine? {
        if (words.isEmpty()) return null
        val text = StringBuilder()
        words.forEachIndexed { i, w ->
            if (i > 0 && !(isUnspacedScript(text.lastOrNull()) && isUnspacedScript(w.text.firstOrNull()))) text.append(' ')
            text.append(w.text)
        }
        return LyricLine(timeMs = timeMs, text = text.toString(), words = words)
    }
}
