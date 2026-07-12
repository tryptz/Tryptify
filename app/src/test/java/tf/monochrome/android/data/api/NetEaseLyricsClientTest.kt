package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseLyricsClientTest {

    @Test
    fun `parseYrc extracts line timing and per-word timing`() {
        // Synthetic yrc line: starts at 1000ms, two words with explicit
        // offsets/durations. Word text carries the separating space, as
        // NetEase's real payloads do.
        val raw = "[1000,900](1000,300,0)Foo (1300,400,0)bar "
        val lines = NetEaseLyricsClient.parseYrc(raw, convertToRomaji = false)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals(1000L, line.timeMs)
        assertEquals("Foo bar", line.text)
        assertEquals(2, line.words.size)
        assertEquals(1000L, line.words[0].startMs)
        assertEquals(1300L, line.words[0].endMs)
        assertEquals(1300L, line.words[1].startMs)
        assertEquals(1700L, line.words[1].endMs)
    }

    @Test
    fun `parseYrc skips metadata and malformed lines`() {
        val raw = buildString {
            appendLine("""{"t":0,"c":[{"tx":"credits"}]}""")
            appendLine("[2000,500](2000,500,0)Solo ")
            appendLine("not a lyric line at all")
        }
        val lines = NetEaseLyricsClient.parseYrc(raw, convertToRomaji = false)
        assertEquals(1, lines.size)
        assertEquals(2000L, lines.single().timeMs)
        assertEquals("Solo", lines.single().text)
    }

    @Test
    fun `parseYrc returns nothing for blank input`() {
        assertTrue(NetEaseLyricsClient.parseYrc("", convertToRomaji = false).isEmpty())
    }

    @Test
    fun `parseLrc extracts line-level timing`() {
        val raw = "[00:01.50]First line\n[00:03.25]Second line"
        val lines = NetEaseLyricsClient.parseLrc(raw, convertToRomaji = false)

        assertEquals(2, lines.size)
        assertEquals(1500L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(3250L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test
    fun `parseLrc drops blank-text lines`() {
        val raw = "[00:00.00]\n[00:01.00]Real line"
        val lines = NetEaseLyricsClient.parseLrc(raw, convertToRomaji = false)
        assertEquals(1, lines.size)
        assertEquals("Real line", lines.single().text)
    }
}
