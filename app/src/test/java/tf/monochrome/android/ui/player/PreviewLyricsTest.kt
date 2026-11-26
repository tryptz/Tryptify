package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The made-up lyrics the previews sing ([PreviewLyrics]): an intro, words
 * timed back to back with a breath after each comma, lines a gap apart, and a
 * loop that starts over at the intro.
 */
class PreviewLyricsTest {
    private val lines = PreviewLyrics.timed(
        listOf("City lights are calling, I follow", "Every word I sing turns to gold", "Feel the beat tonight"),
    )

    @Test
    fun `the first line waits through an intro, and each line starts at its first word`() {
        assertEquals(PreviewLyrics.INTRO_MS, lines.first().timeMs)
        lines.forEach { line -> assertEquals(line.timeMs, line.words.first().startMs) }
        // Nothing is sung before it: the intro the lyric view shows.
        assertEquals(LyricSung.NOT_STARTED, LyricSung.at(lines, PreviewLyrics.INTRO_MS - 1, byWord = true))
    }

    @Test
    fun `words run back to back, a breath after a comma, and a gap between lines`() {
        val first = lines[0].words
        assertEquals(listOf("City", "lights", "are", "calling,", "I", "follow"), first.map { it.text })
        first.zipWithNext().forEach { (a, b) ->
            val gap = b.startMs - a.endMs
            assertEquals(a.text, if (a.text.endsWith(',')) PreviewLyrics.PAUSE_MS else 0L, gap)
        }
        lines.zipWithNext().forEach { (a, b) ->
            assertEquals(PreviewLyrics.LINE_GAP_MS, b.timeMs - a.words.last().endMs)
        }
        // Longer words are sung longer, within a sung word's range.
        assertEquals(PreviewLyrics.wordMs("I"), first[4].endMs - first[4].startMs)
        assertTrue(PreviewLyrics.wordMs("calling,") > PreviewLyrics.wordMs("I"))
        assertEquals(260L, PreviewLyrics.wordMs("a"))
        assertEquals(760L, PreviewLyrics.wordMs("x".repeat(40)))
    }

    @Test
    fun `the comma's breath is where Fade out and Keep shining differ`() {
        val calling = lines[0].words[3]
        val breath = calling.endMs + PreviewLyrics.PAUSE_MS / 2
        assertFalse(LyricSung.at(lines, breath, byWord = true).lit)
        assertTrue(LyricSung.at(lines, breath, byWord = true, hold = true).lit)
    }

    @Test
    fun `the loop runs intro to outro and starts over`() {
        val length = PreviewLyrics.lengthMs(lines)
        assertEquals(lines.last().words.last().endMs + PreviewLyrics.OUTRO_MS, length)
        assertEquals(0L, PreviewLyrics.positionAt(0, length))
        assertEquals(1_000L, PreviewLyrics.positionAt(length + 1_000, length))
        assertEquals(length - 1, PreviewLyrics.positionAt(3 * length - 1, length))
        assertEquals(0L, PreviewLyrics.positionAt(5_000, 0))
    }

    @Test
    fun `a blank text is a break with no words, and words in other scripts split at spaces`() {
        val withBreak = PreviewLyrics.timed(listOf("One two", "", "城市灯火 在呼唤， 我跟随"))
        assertTrue(withBreak[1].words.isEmpty())
        assertEquals("", withBreak[1].text)
        assertEquals(withBreak[1].timeMs + PreviewLyrics.LINE_GAP_MS * 2, withBreak[2].timeMs)
        val cjk = withBreak[2].words
        assertEquals(listOf("城市灯火", "在呼唤，", "我跟随"), cjk.map { it.text })
        // A full-width comma is a breath too.
        assertEquals(PreviewLyrics.PAUSE_MS, cjk[2].startMs - cjk[1].endMs)
    }
}
