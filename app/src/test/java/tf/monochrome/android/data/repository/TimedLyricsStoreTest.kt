// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

/** The cache's shape: what a server once wrote still reads, and the editor writes the same. */
class TimedLyricsStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A row as a lyrics server wrote it before the app stopped asking one. */
    private val serverRow = """{"service":"tryptmania-parser","model":"small","device":"cpu","mode":"aligned","coverage":0.92,
        "host":"parser.example.app","lines":[
          {"start":10.0,"end":11.9,"estimated":false,"words":[
            {"text":"Hello ","start":10.0,"end":10.4},{"text":"darkness, ","start":10.4,"end":10.8},{"text":"my ","start":10.8,"end":11.2},
            {"text":"old ","start":11.2,"end":11.6},{"text":"friend","start":11.6,"end":11.9}]},
          {"start":14.7,"end":16.98,"estimated":true,"words":[{"text":"Because ","start":14.7,"end":15.24},{"text":"a ","start":15.24,"end":15.42}]},
          {"start":20.0,"end":20.0,"estimated":false,"words":[]}
        ],
        "lrc":"[00:10.00]<00:10.00>Hello <00:10.40>darkness, <00:10.80>my <00:11.20>old <00:11.60>friend<00:11.90>\n"}"""

    @Test
    fun aServersOldRowStillReadsWordTimedInMilliseconds() {
        val kept = TimedLyricsStore.fromCached(json, serverRow, convertToRomaji = false)!!
        val lyrics = kept.lyrics
        assertTrue(lyrics.isSynced)
        // The line with no words is not a line.
        assertEquals(2, lyrics.lines.size)
        val first = lyrics.lines[0]
        assertEquals(10_000L, first.timeMs)
        assertEquals("Hello darkness, my old friend", first.text)
        assertEquals(listOf("Hello", "darkness,", "my", "old", "friend"), first.words.map { it.text })
        assertEquals(10_400L, first.words[1].startMs)
        assertEquals(10_800L, first.words[1].endMs)
        assertEquals(14_700L, lyrics.lines[1].timeMs)
        // It counts as word-timed for the race's tiers.
        assertEquals(3, LyricsRace.tier(lyrics))
        assertTrue(kept.lrc!!.startsWith("[00:10.00]"))
    }

    @Test
    fun theEditorsRowReadsBackAsWritten() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 1_000, text = "Hello darkness", words = listOf(LyricWord(1_000, 1_400, "Hello"), LyricWord(1_400, 1_900, "darkness"))),
                // A line with no words becomes one word for the whole line.
                LyricLine(timeMs = 3_000, text = "my old friend", words = emptyList()),
            ),
            isSynced = true,
        )
        val row = TimedLyricsStore.toData(lyrics, "[00:01.00]Hello darkness\n")
        val back = TimedLyricsStore.fromCached(json, row, convertToRomaji = false)!!
        assertEquals(lyrics.lines[0], back.lyrics.lines[0])
        assertEquals("my old friend", back.lyrics.lines[1].text)
        assertEquals(3_000L, back.lyrics.lines[1].timeMs)
        assertEquals(1, back.lyrics.lines[1].words.size)
        assertEquals("[00:01.00]Hello darkness\n", back.lrc)
        assertNull(TimedLyricsStore.fromCached(json, "not json", false))
        assertNull(TimedLyricsStore.fromCached(json, """{"lines":[]}""", false))
    }

    @Test
    fun unspacedScriptsKeepTheirLinesUnspaced() {
        val ja = """{"mode":"aligned","lines":[{"start":1.0,"end":2.5,"words":[
            {"text":"夜","start":1.0,"end":1.3},{"text":"に","start":1.3,"end":1.6},{"text":"駆","start":1.6,"end":1.9},
            {"text":"け","start":1.9,"end":2.2},{"text":"る","start":2.2,"end":2.5}]}]}"""
        val lyrics = TimedLyricsStore.fromCached(json, ja, false)!!.lyrics
        assertEquals("夜に駆ける", lyrics.lines[0].text)
        assertEquals(5, lyrics.lines[0].words.size)
    }
}
