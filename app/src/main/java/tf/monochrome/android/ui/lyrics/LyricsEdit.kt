// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.lyrics

import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

/**
 * The lyrics editor's moves, each a new [Lyrics] from the old: the editor
 * keeps a history of them for undo. Lines stay in the order they are shown;
 * a time set out of order is the user's to see (the editor marks it), not
 * silently resorted, since the words' order is the song's.
 *
 * Times are milliseconds from the song's start. A line's words, where it
 * has them, move with the line, and the line's time is its first word's.
 */
object LyricsEdit {

    /** The line's words as typed: split on spaces, or one character each in an unspaced script. */
    fun wordsOf(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val spaced = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (spaced.size > 1 || !unspaced(trimmed)) return spaced
        return trimmed.map { it.toString() }
    }

    private fun unspaced(text: String): Boolean = text.any { ch ->
        val block = Character.UnicodeBlock.of(ch)
        block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    /** The line at [index] starting at [ms]; its words slide with it. */
    fun setLineTime(lyrics: Lyrics, index: Int, ms: Long): Lyrics = update(lyrics, index) { line ->
        val delta = ms - line.timeMs
        line.copy(timeMs = ms.coerceAtLeast(0), words = line.words.map { it.copy(startMs = (it.startMs + delta).coerceAtLeast(0), endMs = (it.endMs + delta).coerceAtLeast(0)) })
    }

    /**
     * The word at [word] of line [index] sung from [ms]: the word before it
     * ends there, and the first word's time is the line's.
     *
     * Where the words after it would then start before it, they move with
     * it, keeping their spacing: stamping a line's first word at 0:47 left
     * the rest at the 0:34 the source had, and the highlight jumped back.
     */
    fun setWordTime(lyrics: Lyrics, index: Int, word: Int, ms: Long): Lyrics = update(lyrics, index) { line ->
        if (word !in line.words.indices) return@update line
        val at = ms.coerceAtLeast(0)
        val old = line.words[word]
        val delta = at - old.startMs
        val carry = line.words.drop(word + 1).any { it.startMs < at }
        val words = line.words.mapIndexed { i, w ->
            when {
                i == word -> w.copy(startMs = at, endMs = if (carry) maxOf(old.endMs + delta, at + 1) else maxOf(w.endMs, at + 1))
                i == word - 1 -> w.copy(endMs = at.coerceAtLeast(w.startMs))
                i > word && carry -> w.copy(startMs = (w.startMs + delta).coerceAtLeast(at), endMs = (w.endMs + delta).coerceAtLeast(at))
                else -> w
            }
        }
        line.copy(timeMs = if (word == 0) at else line.timeMs, words = words)
    }

    /** The line's last word ends at [ms]: where the line stops lighting. */
    fun setLineEnd(lyrics: Lyrics, index: Int, ms: Long): Lyrics = update(lyrics, index) { line ->
        if (line.words.isEmpty()) return@update line
        val last = line.words.last()
        line.copy(words = line.words.dropLast(1) + last.copy(endMs = ms.coerceAtLeast(last.startMs)))
    }

    /** Line [index] moved by [deltaMs], words and all. */
    fun nudgeLine(lyrics: Lyrics, index: Int, deltaMs: Long): Lyrics {
        val line = lyrics.lines.getOrNull(index) ?: return lyrics
        return setLineTime(lyrics, index, line.timeMs + deltaMs)
    }

    /** Every line moved by [deltaMs]: the whole song early or late. */
    fun shiftAll(lyrics: Lyrics, deltaMs: Long): Lyrics =
        lyrics.copy(lines = lyrics.lines.indices.fold(lyrics) { acc, i -> nudgeLine(acc, i, deltaMs) }.lines)

    /**
     * The line's text as [text]. Its words, where it had them, are re-cut
     * from the new text and spread over the time the old words took; a
     * line with no words keeps none.
     */
    fun setText(lyrics: Lyrics, index: Int, text: String): Lyrics = update(lyrics, index) { line ->
        val pieces = wordsOf(text)
        if (line.words.isEmpty() || pieces.isEmpty()) return@update line.copy(text = text.trim())
        val start = line.words.first().startMs
        val end = maxOf(line.words.last().endMs, start + pieces.size * 100L)
        line.copy(text = text.trim(), words = spread(pieces, start, end))
    }

    /** The line's words timed evenly across its span, from its text: the start of word timing by hand. */
    fun splitIntoWords(lyrics: Lyrics, index: Int, endMs: Long): Lyrics = update(lyrics, index) { line ->
        val pieces = wordsOf(line.text)
        if (pieces.isEmpty()) return@update line
        val end = maxOf(endMs, line.timeMs + pieces.size * 100L)
        line.copy(words = spread(pieces, line.timeMs, end))
    }

    /** The line as line-timed only. */
    fun clearWords(lyrics: Lyrics, index: Int): Lyrics = update(lyrics, index) { it.copy(words = emptyList()) }

    /** A new line after [index] (before the first when -1), at [ms]. */
    fun insertLine(lyrics: Lyrics, index: Int, text: String, ms: Long): Lyrics {
        val at = (index + 1).coerceIn(0, lyrics.lines.size)
        val line = LyricLine(timeMs = ms.coerceAtLeast(0), text = text.trim())
        return lyrics.copy(lines = lyrics.lines.toMutableList().apply { add(at, line) }, isSynced = true)
    }

    fun deleteLine(lyrics: Lyrics, index: Int): Lyrics {
        if (index !in lyrics.lines.indices) return lyrics
        return lyrics.copy(lines = lyrics.lines.filterIndexed { i, _ -> i != index })
    }

    /**
     * A selection that exists in [lyrics]: the last line and word at most,
     * the first at least, and word zero on a line with no words (the editor
     * crashed on that line's empty range, seen on device).
     */
    fun clamp(lyrics: Lyrics?, line: Int, word: Int): Pair<Int, Int> {
        val lines = lyrics?.lines.orEmpty()
        val at = line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val words = lines.getOrNull(at)?.words?.size ?: 0
        return at to word.coerceIn(0, (words - 1).coerceAtLeast(0))
    }

    /** Lines in time order, for saving: the editor shows them as they are. */
    fun sorted(lyrics: Lyrics): Lyrics = lyrics.copy(lines = lyrics.lines.sortedBy { it.timeMs }, isSynced = true)

    /** The lines whose time is earlier than the line before: shown out of order. */
    fun outOfOrder(lyrics: Lyrics): Set<Int> =
        lyrics.lines.indices.filter { i -> i > 0 && lyrics.lines[i].timeMs < lyrics.lines[i - 1].timeMs }.toSet()

    /** Plain text, one line each, with no times: for a fresh start from pasted lyrics. */
    fun fromPlainText(text: String, stepMs: Long = 3_000): Lyrics = Lyrics(
        lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .mapIndexed { i, line -> LyricLine(timeMs = i * stepMs, text = line) },
        isSynced = true,
    )

    private fun spread(pieces: List<String>, start: Long, end: Long): List<LyricWord> {
        val total = pieces.sumOf { it.length }.coerceAtLeast(1)
        var at = start
        return pieces.mapIndexed { i, piece ->
            val next = if (i == pieces.lastIndex) end else start + (end - start) * pieces.take(i + 1).sumOf { it.length } / total
            LyricWord(startMs = at, endMs = maxOf(next, at), text = piece).also { at = maxOf(next, at) }
        }
    }

    private inline fun update(lyrics: Lyrics, index: Int, change: (LyricLine) -> LyricLine): Lyrics {
        if (index !in lyrics.lines.indices) return lyrics
        return lyrics.copy(lines = lyrics.lines.mapIndexed { i, line -> if (i == index) change(line) else line }, isSynced = true)
    }
}
