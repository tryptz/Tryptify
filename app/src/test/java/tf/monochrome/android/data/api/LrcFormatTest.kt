// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

class LrcFormatTest {

    @Test
    fun plainLrcIsLineTimed() {
        val lyrics = LrcFormat.parse("[ti:Song]\n[00:10.00]Hello darkness\n[00:12.5]my old friend\n[01:02]no fraction\n")!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf(10_000L, 12_500L, 62_000L), lyrics.lines.map { it.timeMs })
        assertEquals("Hello darkness", lyrics.lines[0].text)
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
    }

    @Test
    fun enhancedLrcIsWordTimed() {
        val lyrics = LrcFormat.parse("[00:10.00]<00:10.00>Hello <00:10.40>darkness<00:10.70>\n[00:12.00]<00:12.00>my <00:12.30>old <00:12.60>friend<00:13.00>\n")!!
        val first = lyrics.lines[0]
        assertEquals("Hello darkness", first.text)
        assertEquals(listOf(LyricWord(10_000, 10_400, "Hello"), LyricWord(10_400, 10_700, "darkness")), first.words)
        assertEquals(12_000L, lyrics.lines[1].timeMs)
        assertEquals(13_000L, lyrics.lines[1].words.last().endMs)
    }

    @Test
    fun offsetAndRepeatedTagsApply() {
        val lyrics = LrcFormat.parse("[offset:500]\n[00:10.00][00:30.00]Chorus line\n[00:20.00]Verse\n")!!
        assertEquals(listOf("Chorus line", "Verse", "Chorus line"), lyrics.lines.map { it.text })
        assertEquals(listOf(9_500L, 19_500L, 29_500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun millisecondFractionsAndNothingTimedAreHandled() {
        assertEquals(10_123L, LrcFormat.parse("[00:10.123]x")!!.lines[0].timeMs)
        assertNull(LrcFormat.parse("just words\nno times"))
    }

    @Test
    fun writingThenReadingGivesTheSameLyrics() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(10_000, "Hello darkness", listOf(LyricWord(10_000, 10_400, "Hello"), LyricWord(10_400, 10_700, "darkness"))),
                LyricLine(20_000, "plain line"),
            ),
            isSynced = true,
        )
        val text = LrcFormat.write(lyrics, "Song", "Artist", 65_400)
        assertTrue(text.startsWith("[ti:Song]\n[ar:Artist]\n[length:01:05]\n[re:Tryptify]\n"))
        assertTrue(text.contains("[00:10.00]<00:10.00>Hello <00:10.40>darkness<00:10.70>\n"))
        // Nine seconds of nothing: a blank stamp clears the screen.
        assertTrue(text.contains("[00:10.70]\n[00:20.00]plain line\n"))
        assertEquals(lyrics, LrcFormat.parse(text))
    }

    @Test
    fun byteOrderMarkKeepsTheFirstLine() {
        val lyrics = LrcFormat.parse("﻿[00:01.00]first\n[00:02.00]second\n")!!
        assertEquals(listOf("first", "second"), lyrics.lines.map { it.text })
    }

    @Test
    fun endStampsAreTimesNotWords() {
        val lyrics = LrcFormat.parse("[00:01.00]closed line[00:03.50]\n[00:04.00-00:06.00]with its end\n")!!
        assertEquals(listOf("closed line", "with its end"), lyrics.lines.map { it.text })
        assertEquals(listOf(1_000L, 4_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun allZeroStampsAreUntimed() {
        val lyrics = LrcFormat.parse("[00:00.00]one\n[00:00.00]two\n")!!
        assertTrue(!lyrics.isSynced)
        assertEquals(listOf("one", "two"), lyrics.lines.map { it.text })
    }

    @Test
    fun stampsRoundToCentiseconds() {
        assertEquals("00:10.40", LrcFormat.stamp(10_404))
        assertEquals("01:00.01", LrcFormat.stamp(60_005))
    }
}
