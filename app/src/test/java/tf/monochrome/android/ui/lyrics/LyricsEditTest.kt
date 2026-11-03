// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

class LyricsEditTest {

    private val timed = Lyrics(
        lines = listOf(
            LyricLine(10_000, "Hello darkness", listOf(LyricWord(10_000, 10_400, "Hello"), LyricWord(10_400, 10_700, "darkness"))),
            LyricLine(20_000, "plain line"),
        ),
        isSynced = true,
    )

    @Test
    fun aLineMovesWithItsWords() {
        val moved = LyricsEdit.setLineTime(timed, 0, 11_000)
        assertEquals(11_000L, moved.lines[0].timeMs)
        assertEquals(listOf(11_000L, 11_400L), moved.lines[0].words.map { it.startMs })
        assertEquals(11_700L, moved.lines[0].words[1].endMs)
        assertEquals(timed.lines[1], moved.lines[1])
    }

    @Test
    fun aWordsTimeClosesTheWordBefore() {
        val tapped = LyricsEdit.setWordTime(timed, 0, 1, 10_500)
        assertEquals(10_500L, tapped.lines[0].words[0].endMs)
        assertEquals(10_500L, tapped.lines[0].words[1].startMs)
        val first = LyricsEdit.setWordTime(timed, 0, 0, 9_800)
        assertEquals(9_800L, first.lines[0].timeMs)
    }

    @Test
    fun stampingAWordLateCarriesTheWordsAfterIt() {
        // As seen on device: the source had the line at 0:34, the first word was stamped at 0:47.
        val early = Lyrics(
            lines = listOf(LyricLine(34_000, "the darkest hour", listOf(LyricWord(34_000, 34_300, "the"), LyricWord(34_300, 34_600, "darkest"), LyricWord(34_600, 35_000, "hour")))),
            isSynced = true,
        )
        val stamped = LyricsEdit.setWordTime(early, 0, 0, 47_760)
        assertEquals(47_760L, stamped.lines[0].timeMs)
        assertEquals(listOf(47_760L, 48_060L, 48_360L), stamped.lines[0].words.map { it.startMs })
        // Words already after the stamp stay where they were.
        val later = LyricsEdit.setWordTime(timed, 0, 0, 10_100)
        assertEquals(10_400L, later.lines[0].words[1].startMs)
    }

    @Test
    fun nudgeAndShiftNeverGoBelowZero() {
        assertEquals(9_900L, LyricsEdit.nudgeLine(timed, 0, -100).lines[0].timeMs)
        val shifted = LyricsEdit.shiftAll(timed, -15_000)
        assertEquals(listOf(0L, 5_000L), shifted.lines.map { it.timeMs })
    }

    @Test
    fun newTextKeepsTheLinesSpan() {
        val edited = LyricsEdit.setText(timed, 0, "Hello darkness my old friend")
        assertEquals(5, edited.lines[0].words.size)
        assertEquals(10_000L, edited.lines[0].words.first().startMs)
        assertEquals(10_700L, edited.lines[0].words.last().endMs)
        assertTrue(edited.lines[0].words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        // A plain line stays plain.
        assertTrue(LyricsEdit.setText(timed, 1, "still plain").lines[1].words.isEmpty())
    }

    @Test
    fun splittingAndClearingWords() {
        val split = LyricsEdit.splitIntoWords(timed, 1, 22_000)
        assertEquals(listOf("plain", "line"), split.lines[1].words.map { it.text })
        assertEquals(22_000L, split.lines[1].words.last().endMs)
        assertTrue(LyricsEdit.clearWords(split, 1).lines[1].words.isEmpty())
        assertEquals(listOf("夜", "に", "駆", "け", "る"), LyricsEdit.wordsOf("夜に駆ける"))
    }

    @Test
    fun aSelectionAlwaysExists() {
        // A tap on a plain line (no words) crashed the editor on an empty range (seen on device).
        assertEquals(1 to 0, LyricsEdit.clamp(timed, 1, 3))
        assertEquals(0 to 1, LyricsEdit.clamp(timed, 0, 7))
        assertEquals(1 to 0, LyricsEdit.clamp(timed, 9, 9))
        assertEquals(0 to 0, LyricsEdit.clamp(timed, -1, -1))
        assertEquals(0 to 0, LyricsEdit.clamp(Lyrics(isSynced = true), 2, 2))
        assertEquals(0 to 0, LyricsEdit.clamp(null, 2, 2))
    }

    @Test
    fun linesComeAndGoAndTellWhenOutOfOrder() {
        val more = LyricsEdit.insertLine(timed, 0, "between", 15_000)
        assertEquals(listOf("Hello darkness", "between", "plain line"), more.lines.map { it.text })
        val early = LyricsEdit.setLineTime(more, 1, 5_000)
        assertEquals(setOf(1), LyricsEdit.outOfOrder(early))
        assertEquals(listOf(5_000L, 10_000L, 20_000L), LyricsEdit.sorted(early).lines.map { it.timeMs })
        assertEquals(2, LyricsEdit.deleteLine(more, 1).lines.size)
        assertEquals(listOf(0L, 3_000L), LyricsEdit.fromPlainText("one\n\ntwo\n").lines.map { it.timeMs })
    }
}
