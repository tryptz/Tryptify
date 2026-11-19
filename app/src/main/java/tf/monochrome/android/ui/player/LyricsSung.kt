package tf.monochrome.android.ui.player

import tf.monochrome.android.domain.model.LyricLine

/**
 * What is being sung at a moment of the song, for the lyrics' light (the god
 * rays and what the glass letters catch of them): which line, which word, and
 * whether anything is being sung at all. Kept apart from the drawing so it can
 * be held to numbers ([LyricSungTest]).
 *
 * The light is on only while something is sung. It used to rest on the last
 * word until the next one, and before the first line it had no line at all,
 * which the rays took for "every lyric shines": the whole page of lyrics
 * streamed light through a song's intro (seen on device).
 */
internal object LyricSung {
    /**
     * [line] is the line whose turn it is, -1 before the first. [word] is the
     * word the light follows in it, -1 when it follows the line (and before the
     * line's first word). [lit] says whether that line or word is being sung
     * now, or is over (or not begun) and dark.
     */
    data class Now(val line: Int, val word: Int, val lit: Boolean) {
        /** One number for the place the light is at: a new one is a move. */
        val place: Long get() = line.toLong() shl 32 or (word.toLong() and 0xFFFFFFFFL)
    }

    /** Nothing has been sung yet. */
    val NOT_STARTED = Now(line = -1, word = -1, lit = false)

    /**
     * What is sung at [positionMs] of the song. With [byWord] the light
     * follows the words of a word-timed line: on from each word's start to its
     * end, off between words. Otherwise it follows the line: on from its first
     * word to the end of its last.
     *
     * A line with no word timings has no end of its own, so it is sung until
     * the next line starts. An empty one (an instrumental break, drawn as ♪)
     * is not sung at all.
     */
    fun at(lines: List<LyricLine>, positionMs: Long, byWord: Boolean): Now {
        val index = lines.indexOfLast { it.timeMs <= positionMs }
        if (index < 0) return NOT_STARTED
        val line = lines[index]
        val words = line.words
        if (words.isEmpty()) return Now(index, -1, lit = line.text.isNotBlank())
        if (!byWord) {
            val lit = positionMs >= words.first().startMs && positionMs < lineEndMs(lines, index)
            return Now(index, -1, lit)
        }
        val word = words.indexOfLast { it.startMs <= positionMs }
        return Now(index, word, lit = word >= 0 && positionMs < wordEndMs(lines, index, word))
    }

    /**
     * When word [word] of line [index] stops being sung. A word with no
     * length (a line timed as one word, or a source that gives only starts)
     * lasts until the next word starts, and the last such word of a line until
     * the next line does: there is nothing else to end it by.
     */
    fun wordEndMs(lines: List<LyricLine>, index: Int, word: Int): Long {
        val words = lines[index].words
        val w = words[word]
        if (w.endMs > w.startMs) return w.endMs
        return words.getOrNull(word + 1)?.startMs ?: lines.getOrNull(index + 1)?.timeMs ?: Long.MAX_VALUE
    }

    /** When line [index] stops being sung: the latest end among its words. */
    private fun lineEndMs(lines: List<LyricLine>, index: Int): Long =
        lines[index].words.indices.maxOf { wordEndMs(lines, index, it) }
}
