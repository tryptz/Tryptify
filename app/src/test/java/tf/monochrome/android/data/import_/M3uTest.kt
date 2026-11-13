package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Reading `.m3u` / `.m3u8` files (issue #155): the text out of the bytes, and the songs out of the text. */
class M3uTest {

    // ── Parsing ─────────────────────────────────────────────────────────────

    @Test
    fun `a plain list of paths is its songs in order`() {
        val playlist = M3u.parse("/music/a.mp3\n/music/b.flac\n")
        assertNull(playlist.title)
        assertEquals(listOf(M3uEntry("/music/a.mp3"), M3uEntry("/music/b.flac")), playlist.entries)
    }

    @Test
    fun `an extended list carries each song's length and text`() {
        val playlist = M3u.parse(
            """
            #EXTM3U
            #EXTINF:354,Queen - Bohemian Rhapsody
            Queen/A Night at the Opera/11 Bohemian Rhapsody.flac
            #EXTINF:183,Daft Punk - Around the World
            ../Daft Punk/Homework/07 Around the World.flac
            """.trimIndent()
        )
        assertEquals(
            listOf(
                M3uEntry("Queen/A Night at the Opera/11 Bohemian Rhapsody.flac", "Queen - Bohemian Rhapsody", 354),
                M3uEntry("../Daft Punk/Homework/07 Around the World.flac", "Daft Punk - Around the World", 183),
            ),
            playlist.entries,
        )
    }

    @Test
    fun `an EXTINF describes the next song only`() {
        val playlist = M3u.parse("#EXTINF:10,A - One\none.mp3\ntwo.mp3\n")
        assertEquals(listOf(M3uEntry("one.mp3", "A - One", 10), M3uEntry("two.mp3")), playlist.entries)
    }

    @Test
    fun `Windows, Unix and old Mac line endings all split lines`() {
        val expected = listOf(M3uEntry("a.mp3"), M3uEntry("b.mp3"), M3uEntry("c.mp3"))
        assertEquals(expected, M3u.parse("a.mp3\r\nb.mp3\r\nc.mp3\r\n").entries)
        assertEquals(expected, M3u.parse("a.mp3\nb.mp3\nc.mp3").entries)
        assertEquals(expected, M3u.parse("a.mp3\rb.mp3\rc.mp3").entries)
    }

    @Test
    fun `blank lines, comments and unknown directives are skipped`() {
        val playlist = M3u.parse("#EXTM3U\n\n   \n# made by hand\n#EXTALB:Homework\n#EXTART:Daft Punk\n  a.mp3  \n")
        assertEquals(listOf(M3uEntry("a.mp3")), playlist.entries)
    }

    @Test
    fun `the PLAYLIST line names the playlist`() {
        assertEquals("Road trip", M3u.parse("#EXTM3U\n#PLAYLIST: Road trip \na.mp3").title)
        assertNull(M3u.parse("#EXTM3U\n#PLAYLIST:\na.mp3").title)
    }

    @Test
    fun `lengths are whole seconds, and none for a stream's -1 or a zero`() {
        fun lengthOf(extinf: String) = M3u.parse("$extinf\na.mp3").entries.single().durationSeconds
        assertEquals(200, lengthOf("#EXTINF:200,x"))
        assertEquals(200, lengthOf("#EXTINF:199.6,x"))
        assertEquals(200, lengthOf("#extinf:200,x"))
        assertNull(lengthOf("#EXTINF:-1,x"))
        assertNull(lengthOf("#EXTINF:0,x"))
        assertNull(lengthOf("#EXTINF:,x"))
        // An IPTV list's attributes after the length.
        assertNull(lengthOf("#EXTINF:-1 tvg-id=\"a\" group-title=\"b\",x"))
        assertEquals(12, lengthOf("#EXTINF:12 tvg-id=\"a\",x"))
    }

    @Test
    fun `the text is everything after the first comma`() {
        fun textOf(extinf: String) = M3u.parse("$extinf\na.mp3").entries.single().display
        assertEquals("Earth, Wind & Fire - September", textOf("#EXTINF:215,Earth, Wind & Fire - September"))
        assertEquals("September", textOf("#EXTINF:215,  September  "))
        assertNull(textOf("#EXTINF:215,"))
        assertNull(textOf("#EXTINF:215"))
    }

    @Test
    fun `a song is named by its text, else by its file`() {
        assertEquals("Queen - Bohemian Rhapsody", M3uEntry("/m/11.flac", "Queen - Bohemian Rhapsody").label)
        assertEquals("11 Bohemian Rhapsody.flac", M3uEntry("/m/Queen/11 Bohemian Rhapsody.flac").label)
        assertEquals("song.mp3", M3uEntry("D:\\Music\\song.mp3").label)
        assertEquals("song.mp3", M3uEntry("song.mp3", "  ").label)
    }

    @Test
    fun `a file that is not text is refused`() {
        // An audio file picked by mistake: bytes with NULs in them.
        assertThrows(IllegalArgumentException::class.java) { M3u.parse("ID3\u0000\u0000\u0003\n\u0000a") }
    }

    @Test
    fun `an empty file has no songs`() {
        assertEquals(emptyList<M3uEntry>(), M3u.parse("").entries)
        assertEquals(emptyList<M3uEntry>(), M3u.parse("#EXTM3U\n").entries)
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    @Test
    fun `a playlist is written as an extended M3U`() {
        val text = M3u.write(
            "Road trip",
            listOf(
                M3uEntry("/storage/emulated/0/Music/Queen/11 Bohemian Rhapsody.flac", "Queen - Bohemian Rhapsody", 354),
                M3uEntry("/storage/emulated/0/Music/loose.mp3"),
            ),
        )
        assertEquals(
            "#EXTM3U\n" +
                "#PLAYLIST:Road trip\n" +
                "#EXTINF:354,Queen - Bohemian Rhapsody\n" +
                "/storage/emulated/0/Music/Queen/11 Bohemian Rhapsody.flac\n" +
                "/storage/emulated/0/Music/loose.mp3\n",
            text,
        )
    }

    @Test
    fun `what is written reads back the same`() {
        val entries = listOf(
            M3uEntry("/storage/emulated/0/Music/Beyoncé/Déjà Vu.mp3", "Beyoncé - Déjà Vu", 240),
            M3uEntry("/storage/1A2B-3C4D/音楽/宇多田ヒカル/First Love.flac", "宇多田ヒカル - First Love", 258),
            M3uEntry("/storage/emulated/0/Music/Earth, Wind & Fire/September.mp3", "Earth, Wind & Fire - September", 215),
            M3uEntry("/storage/emulated/0/Music/no tags.ogg"),
        )
        val back = M3u.parse(M3u.decode(M3u.write("Mix, the first", entries).toByteArray(Charsets.UTF_8)))
        assertEquals("Mix, the first", back.title)
        assertEquals(entries, back.entries)
    }

    @Test
    fun `a song with text but no length is written with -1, and reads back with none`() {
        val text = M3u.write(null, listOf(M3uEntry("/m/a.mp3", "A - One"), M3uEntry("/m/b.mp3", null, 30)))
        assertEquals("#EXTM3U\n#EXTINF:-1,A - One\n/m/a.mp3\n#EXTINF:30,\n/m/b.mp3\n", text)
        assertEquals(listOf(M3uEntry("/m/a.mp3", "A - One"), M3uEntry("/m/b.mp3", null, 30)), M3u.parse(text).entries)
    }

    @Test
    fun `a line break in a name cannot start a line of its own`() {
        val text = M3u.write("Two\nlines", listOf(M3uEntry("/m/a.mp3", "A -\r\nOne", 10)))
        assertEquals("#EXTM3U\n#PLAYLIST:Two lines\n#EXTINF:10,A -  One\n/m/a.mp3\n", text)
        assertEquals(1, M3u.parse(text).entries.size)
    }

    @Test
    fun `an empty playlist and a blank name are still a valid file`() {
        assertEquals("#EXTM3U\n", M3u.write(null, emptyList()))
        assertEquals("#EXTM3U\n", M3u.write("   ", listOf(M3uEntry("  "))))
        assertEquals(emptyList<M3uEntry>(), M3u.parse(M3u.write("", emptyList())).entries)
    }

    @Test
    fun `the text is artist - title, or the title alone`() {
        assertEquals("Queen - Bohemian Rhapsody", M3u.displayOf("Queen", "Bohemian Rhapsody"))
        assertEquals("Bohemian Rhapsody", M3u.displayOf("  ", " Bohemian Rhapsody "))
        assertEquals("Bohemian Rhapsody", M3u.displayOf(null, "Bohemian Rhapsody"))
    }

    // ── Decoding ────────────────────────────────────────────────────────────

    private val accented = "#EXTM3U\nBeyoncé/Déjà Vu.mp3\n"

    @Test
    fun `UTF-8 is read as UTF-8, with or without its mark`() {
        assertEquals(accented, M3u.decode(accented.toByteArray(Charsets.UTF_8)))
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + accented.toByteArray(Charsets.UTF_8)
        assertEquals(accented, M3u.decode(marked))
    }

    @Test
    fun `an old Windows playlist keeps its accents`() {
        // Windows-1252: é is the single byte E9, which is not valid UTF-8 here.
        assertEquals(accented, M3u.decode(accented.toByteArray(charset("windows-1252"))))
    }

    @Test
    fun `UTF-16 with a mark is read either way round`() {
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + accented.toByteArray(Charsets.UTF_16LE)
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + accented.toByteArray(Charsets.UTF_16BE)
        assertEquals(accented, M3u.decode(little))
        assertEquals(accented, M3u.decode(big))
    }

    @Test
    fun `text in other scripts survives`() {
        val text = "#EXTINF:240,宇多田ヒカル - First Love\n音楽/宇多田ヒカル/First Love.flac\n"
        assertEquals(text, M3u.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `plain ASCII and empty bytes decode`() {
        assertEquals("a.mp3\n", M3u.decode("a.mp3\n".toByteArray(Charsets.US_ASCII)))
        assertEquals("", M3u.decode(ByteArray(0)))
    }
}
