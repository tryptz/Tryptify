// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics
import java.util.Locale

/**
 * LRC, read and written: plain `[mm:ss.xx]line` and enhanced
 * `[mm:ss.xx]<mm:ss.xx>word <mm:ss.xx>word<mm:ss.xx>`, the form a
 * TryptMania Parser server writes and the lyrics editor saves. This is the
 * one copy: the catalogue clients each kept a regex of their own, which is
 * why `[mm:ss]` with no fraction never parsed anywhere.
 */
object LrcFormat {

    // `[mm:ss.xx]`, or `[mm:ss.xx-mm:ss.xx]` with the line's end, which is read past.
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?(?:-\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?)?]""")
    private val WORD = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META = Regex("""^\[([a-zA-Z#]+):(.*)]$""")

    // A stamp closing the line (`[00:12.00]words[00:15.00]`): when it ends, not words.
    private val END_STAMP = Regex("""\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]\s*$""")

    /**
     * The lyrics in [text], or null when no line carries a time. Any `[offset:]` is applied.
     *
     * A file whose every stamp is zero was never timed, only stamped: it comes
     * back unsynced, rather than as lines that all light up at once.
     */
    fun parse(text: String): Lyrics? {
        var offsetMs = 0L
        val lines = mutableListOf<LyricLine>()
        // A byte order mark would hide the first line's stamp, and with it the line.
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            META.matchEntire(line.trim())?.let { meta ->
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@let
            }
            if (META.matchEntire(line.trim()) != null) continue
            // Tags come first; the text starts after the last one. Several
            // tags on one line mean the same words sung at each time.
            val lead = mutableListOf<MatchResult>()
            var position = 0
            while (true) {
                val tag = TAG.matchAt(line, position) ?: break
                lead.add(tag)
                position = tag.range.last + 1
            }
            if (lead.isEmpty()) continue
            // A stamp with nothing after it clears a player's screen; it is no line.
            val body = line.substring(position).replace(END_STAMP, "")
            if (body.isBlank()) continue
            for (tag in lead) lines.add(lineOf(body, msOf(tag), offsetMs))
        }
        if (lines.isEmpty()) return null
        val timed = lines.any { line -> line.timeMs > 0 || line.words.any { it.startMs > 0 } }
        if (!timed) return Lyrics(lines = lines.map { it.copy(timeMs = 0, words = emptyList()) }, isSynced = false)
        return Lyrics(lines = lines.sortedBy { it.timeMs }, isSynced = true)
    }

    private fun lineOf(body: String, start: Long, offsetMs: Long): LyricLine {
        val tags = WORD.findAll(body).toList()
        if (tags.isEmpty()) {
            return LyricLine(timeMs = (start - offsetMs).coerceAtLeast(0), text = body.trim())
        }
        // Each word runs from its tag to the next; a tag with nothing after it
        // closes the last word. The line's text is the words with their spaces.
        val words = mutableListOf<LyricWord>()
        for ((i, tag) in tags.withIndex()) {
            val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val text = body.substring(tag.range.last + 1, textEnd)
            val from = msOf(tag) - offsetMs
            val to = if (i + 1 < tags.size) msOf(tags[i + 1]) - offsetMs else from + 2_000
            if (text.isNotBlank()) words.add(LyricWord(startMs = from.coerceAtLeast(0), endMs = to.coerceAtLeast(from), text = text.trim()))
        }
        val lineText = tags.indices.joinToString("") { i ->
            val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            body.substring(tags[i].range.last + 1, textEnd)
        }.trim()
        val before = body.substring(0, tags.first().range.first).trim()
        return LyricLine(
            timeMs = (start - offsetMs).coerceAtLeast(0),
            text = if (before.isEmpty()) lineText else "$before $lineText".trim(),
            words = words,
        )
    }

    private fun msOf(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val ms = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + ms
    }

    /**
     * [lyrics] as LRC: enhanced where a line has words, plain otherwise.
     * A pause of five seconds or more gets an empty stamped line, so a
     * player clears the lyric it was showing.
     */
    fun write(
        lyrics: Lyrics,
        title: String? = null,
        artist: String? = null,
        lengthMs: Long? = null,
        by: String = "Tryptify",
    ): String = buildString {
        title?.takeIf { it.isNotBlank() }?.let { append("[ti:").append(it).append("]\n") }
        artist?.takeIf { it.isNotBlank() }?.let { append("[ar:").append(it).append("]\n") }
        lengthMs?.takeIf { it > 0 }?.let { ms ->
            val whole = (ms + 500) / 1000
            append(String.format(Locale.ROOT, "[length:%02d:%02d]\n", whole / 60, whole % 60))
        }
        append("[re:").append(by).append("]\n")
        val lines = lyrics.lines.sortedBy { it.timeMs }
        for ((k, line) in lines.withIndex()) {
            append('[').append(stamp(line.timeMs)).append(']')
            val end: Long
            if (line.words.isNotEmpty()) {
                for (word in line.words) append('<').append(stamp(word.startMs)).append('>').append(word.text).append(' ')
                setLength(length - 1)
                end = line.words.last().endMs
                append('<').append(stamp(end)).append('>')
            } else {
                append(line.text)
                end = line.timeMs
            }
            append('\n')
            val following = lines.getOrNull(k + 1)?.timeMs
            if (following != null && following - end >= 5_000) append('[').append(stamp(end)).append("]\n")
        }
    }

    /** `mm:ss.xx`, centiseconds, as every player reads. */
    fun stamp(ms: Long): String {
        val centis = (ms.coerceAtLeast(0) + 5) / 10
        val minutes = centis / 6000
        val rest = centis % 6000
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, rest / 100, rest % 100)
    }
}
