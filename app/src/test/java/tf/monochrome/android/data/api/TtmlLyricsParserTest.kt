package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlLyricsParserTest {

    // Trimmed from the AMLL database's file for NetEase song 1406633327, plus
    // an lrc.red-style split word ("e" "nough") and a backing-vocal span.
    private val wordTimed = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
          <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
          <body dur="3:14.571"><div begin="27.173" end="3:14.571">
            <p begin="27.173" end="28.516" ttm:agent="v1"><span begin="27.173" end="27.407">I&apos;ve</span> <span begin="27.407" end="27.510">been</span> <span begin="27.510" end="27.899">tryna</span> <span begin="27.899" end="28.516">call</span><span ttm:role="x-translation" xml:lang="zh-CN">我一直心存向往</span></p>
            <p begin="31.166" end="32.075"><span begin="31.166" end="31.529">long</span> <span begin="31.529" end="31.700">e</span><span begin="31.700" end="32.075">nough</span></p>
            <p begin="1:05.000" end="1:08.000"><span begin="1:05.000" end="1:05.500">know</span><span ttm:role="x-bg"><span begin="1:05.600" end="1:06.000">(back</span> <span begin="1:06.000" end="1:06.400">to)</span></span></p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `word-timed ttml becomes karaoke lines`() {
        val lyrics = TtmlLyricsParser.parse(wordTimed)!!
        assertTrue(lyrics.isSynced)
        assertEquals(3, lyrics.lines.size)

        val first = lyrics.lines[0]
        assertEquals(27173L, first.timeMs)
        assertEquals("I've been tryna call", first.text)
        assertEquals(listOf("I've", "been", "tryna", "call"), first.words.map { it.text })
        assertEquals(27899L, first.words[3].startMs)
        assertEquals(28516L, first.words[3].endMs)
    }

    @Test
    fun `syllables with no space between them are one word`() {
        val line = TtmlLyricsParser.parse(wordTimed)!!.lines[1]
        assertEquals(listOf("long", "enough"), line.words.map { it.text })
        assertEquals(31529L, line.words[1].startMs)
        assertEquals(32075L, line.words[1].endMs)
    }

    @Test
    fun `backing vocals are kept as their own words and translations are dropped`() {
        val lyrics = TtmlLyricsParser.parse(wordTimed)!!
        assertEquals(listOf("know", "(back", "to)"), lyrics.lines[2].words.map { it.text })
        assertEquals(65000L, lyrics.lines[2].timeMs)
        assertFalse(lyrics.lines.any { it.text.contains("我") })
    }

    @Test
    fun `line-timed ttml keeps line timing only`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:12.500" end="00:15.000">First line</p>
            <p begin="00:15.000" end="00:18.000">Second line</p>
            </div></body></tt>"""
        val lyrics = TtmlLyricsParser.parse(ttml)!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf(12500L, 15000L), lyrics.lines.map { it.timeMs })
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `untimed ttml is plain`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p>Just words</p></div></body></tt>"""
        val lyrics = TtmlLyricsParser.parse(ttml)!!
        assertFalse(lyrics.isSynced)
        assertEquals("Just words", lyrics.lines.single().text)
    }

    @Test
    fun `time formats`() {
        assertEquals(27395L, TtmlLyricsParser.parseTime("27.395"))
        assertEquals(201570L, TtmlLyricsParser.parseTime("3:21.570"))
        assertEquals(3723400L, TtmlLyricsParser.parseTime("1:02:03.4"))
        assertEquals(12500L, TtmlLyricsParser.parseTime("12.5s"))
        assertEquals(500L, TtmlLyricsParser.parseTime("500ms"))
        assertNull(TtmlLyricsParser.parseTime(""))
        assertNull(TtmlLyricsParser.parseTime("abc"))
    }

    @Test
    fun `a document type declaration is refused`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <tt><body><div><p begin="1.0">&x;</p></div></body></tt>"""
        assertNull(TtmlLyricsParser.parse(xxe))
    }

    @Test
    fun `garbage is no lyrics`() {
        assertNull(TtmlLyricsParser.parse("not xml at all"))
        assertNull(TtmlLyricsParser.parse("""<tt><body></body></tt>"""))
    }
}
