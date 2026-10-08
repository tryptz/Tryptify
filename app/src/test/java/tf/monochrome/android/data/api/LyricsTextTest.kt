package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics

class LyricsTextTest {

    private fun syl(start: Long, end: Long, text: String) = TimedSyllable(start, end, text)

    @Test
    fun `apple syllables join into the words the view draws`() {
        // LyricsPlus's real shape: the syllable that ends a word carries the space.
        val words = LyricsText.groupSyllables(
            listOf(syl(31245, 31498, "for "), syl(31498, 31839, "long "), syl(31839, 31996, "e"), syl(31996, 32529, "nough")),
        )
        assertEquals(listOf("for", "long", "enough"), words.map { it.text })
        assertEquals(31839L, words[2].startMs)
        assertEquals(32529L, words[2].endMs)
    }

    @Test
    fun `a leading space on the next syllable also ends a word`() {
        val words = LyricsText.groupSyllables(listOf(syl(0, 100, "may"), syl(100, 200, "be"), syl(250, 400, " you")))
        assertEquals(listOf("maybe", "you"), words.map { it.text })
    }

    @Test
    fun `han and kana stay one word per character`() {
        val words = LyricsText.groupSyllables(listOf(syl(0, 100, "無"), syl(100, 200, "敵"), syl(200, 300, "の")))
        assertEquals(listOf("無", "敵", "の"), words.map { it.text })
        // …and their line text has no spaces put between them.
        assertEquals("無敵の", LyricsText.lineOf(0, words)?.text)
    }

    @Test
    fun `hangul keeps its own spaces`() {
        val words = LyricsText.groupSyllables(listOf(syl(0, 100, "사"), syl(100, 200, "랑 "), syl(200, 300, "해")))
        assertEquals(listOf("사랑", "해"), words.map { it.text })
    }

    @Test
    fun `credit lines and a title header are not sung`() {
        val query = LyricsQuery("Blinding Lights", "The Weeknd", durationMs = 200_000)
        val lyrics = Lyrics(
            isSynced = true,
            lines = listOf(
                LyricLine(0, "Blinding Lights - The Weeknd"),
                LyricLine(1000, "作词 : Max Martin/Oscar Holter"),
                LyricLine(1757, "Lyrics by：Max Martin"),
                LyricLine(2000, "词：Ayase"),
                LyricLine(2500, "合声编写：周杰伦"),
                LyricLine(27672, "I've been tryna call"),
            ),
        )
        assertEquals(listOf("I've been tryna call"), LyricsText.clean(lyrics, query)?.lines?.map { it.text })
    }

    @Test
    fun `a header with the artist in another script is still a header`() {
        val query = LyricsQuery("晴天", "周杰倫", durationMs = 269_000)
        val lyrics = Lyrics(
            isSynced = true,
            lines = listOf(LyricLine(0, "晴天 - 周杰伦 (Jay Chou)"), LyricLine(29_000, "故事的小黄花")),
        )
        assertEquals(listOf("故事的小黄花"), LyricsText.clean(lyrics, query)?.lines?.map { it.text })
    }

    @Test
    fun `a sung line repeating the title is not a header`() {
        val query = LyricsQuery("Hey", "Someone")
        val lyrics = Lyrics(isSynced = true, lines = listOf(LyricLine(1_000, "Hey - hey - hey"), LyricLine(3_000, "next")))
        assertEquals(2, LyricsText.clean(lyrics, query)?.lines?.size)
    }

    @Test
    fun `lyrics running past the end of the track belong to a longer cut`() {
        val query = LyricsQuery("Song", "Artist", durationMs = 180_000)
        val long = Lyrics(isSynced = true, lines = listOf(LyricLine(10_000, "first"), LyricLine(230_000, "late")))
        assertNull(LyricsText.clean(long, query))
        val fits = Lyrics(isSynced = true, lines = listOf(LyricLine(10_000, "first"), LyricLine(178_000, "last")))
        assertEquals(2, LyricsText.clean(fits, query)?.lines?.size)
    }

    @Test
    fun `credit roles are told apart from singers`() {
        // The agent names LyricsPlus gives QQ's credit lines.
        listOf("合声编写", "贝斯", "鼓", "录音助理", "录音工程", "混音工程", "作词", "Producer").forEach {
            assertEquals(it, true, LyricsText.isCreditRole(it))
        }
        listOf("v1", "Vocal 1", "Spencer", "Opeth", "周杰伦", "").forEach {
            assertEquals(it, false, LyricsText.isCreditRole(it))
        }
    }

    @Test
    fun `nothing left after cleaning is no lyrics`() {
        val query = LyricsQuery("Song", "Artist")
        assertNull(LyricsText.clean(Lyrics(isSynced = true, lines = listOf(LyricLine(0, "作曲 : Someone"))), query))
    }
}
