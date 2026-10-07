package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where downloads are filed (issue #121). Every download used to land flat in
 * the chosen folder with one cover.jpg for all of them, so the first album's
 * cover became every album's cover. These hold the layout that replaced it,
 * and the reading-back the Downloads screen does for files it finds on disk.
 */
class DownloadLayoutTest {

    private fun target(
        title: String = "Billie Jean",
        artist: String = "Michael Jackson",
        albumArtist: String? = null,
        album: String? = "Thriller",
        track: Int? = 6,
        disc: Int? = 1,
    ) = DownloadLayout.target(title, artist, albumArtist, album, track, disc)

    @Test
    fun `a track is filed under its artist and album, numbered`() {
        val t = target()
        assertEquals("Michael Jackson", t.artistFolder)
        assertEquals("Thriller", t.albumFolder)
        assertEquals("06. Billie Jean", t.stem)
    }

    @Test
    fun `the album artist decides the folder, so guests and compilations stay together`() {
        val t = target(artist = "Paul McCartney", albumArtist = "Michael Jackson", title = "The Girl Is Mine")
        assertEquals("Michael Jackson", t.artistFolder)
    }

    @Test
    fun `disc 2 on gets a folder of its own inside the album's`() {
        val t = target(disc = 2, track = 1)
        assertEquals("Thriller", t.albumFolder)
        assertEquals("Disc 2", t.discFolder)
        assertEquals("01. Billie Jean", t.stem)
    }

    @Test
    fun `disc 1, or no disc number, stays in the album folder`() {
        assertNull(target(disc = 1).discFolder)
        assertNull(target(disc = null).discFolder)
        assertNull(target(disc = 0).discFolder)
    }

    @Test
    fun `no album, no disc folder`() {
        assertNull(target(album = null, disc = 2).discFolder)
    }

    @Test
    fun `no track number means no prefix`() {
        assertEquals("Billie Jean", target(track = null).stem)
        assertEquals("Billie Jean", target(track = 0).stem)
    }

    @Test
    fun `a track with no album goes in its artist's folder`() {
        assertNull(target(album = null).albumFolder)
        assertNull(target(album = "  ").albumFolder)
    }

    @Test
    fun `names lose what storage refuses`() {
        assertEquals("AC_DC", DownloadLayout.sanitize("AC/DC", "x"))
        assertEquals("What_ Why_", DownloadLayout.sanitize("What? Why?", "x"))
        assertEquals("a b", DownloadLayout.sanitize("a\tb", "x"))
        assertEquals("a_b", DownloadLayout.sanitize("a\u0001b", "x"))
        // FAT drops trailing dots, so a name ending in one would not read back.
        assertEquals("Mr", DownloadLayout.sanitize("Mr.", "x"))
        assertEquals("St. Elsewhere", DownloadLayout.sanitize("St. Elsewhere", "x"))
    }

    @Test
    fun `names never start with a dot, which would hide them from the media scanner`() {
        assertEquals("38 Special", DownloadLayout.sanitize(".38 Special", "x"))
        assertEquals("And Justice for All", DownloadLayout.sanitize("...And Justice for All", "x"))
        assertEquals("5_ The Gray Chapter", DownloadLayout.sanitize(".5: The Gray Chapter", "x"))
        assertEquals("fallback", DownloadLayout.sanitize("...", "fallback"))
    }

    @Test
    fun `a name with nothing left falls back`() {
        assertEquals(DownloadLayout.UNKNOWN_ARTIST, target(artist = "...", albumArtist = null).artistFolder)
        assertEquals("fallback", DownloadLayout.sanitize("   ", "fallback"))
    }

    @Test
    fun `long names are cut to fit, by bytes and never mid-character`() {
        val cjk = "音".repeat(200) // 3 bytes each in UTF-8
        val cut = DownloadLayout.sanitize(cjk, "x")
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(66, cut.length)

        val emoji = "😀".repeat(100) // 4 bytes, and two chars, each
        val cutEmoji = DownloadLayout.sanitize(emoji, "x")
        assertEquals(50, cutEmoji.codePointCount(0, cutEmoji.length))
        assertEquals(100, cutEmoji.length) // whole pairs: no half an emoji at the end
    }

    @Test
    fun `a file at the top of the folder is read the old flat way`() {
        val d = DownloadLayout.describe(emptyList(), "Michael Jackson - Billie Jean.flac")
        assertEquals("Michael Jackson", d.artist)
        assertNull(d.album)
        assertEquals("Billie Jean", d.title)
    }

    @Test
    fun `a filed download is read back from its folders`() {
        val d = DownloadLayout.describe(listOf("Michael Jackson", "Thriller"), "06. Billie Jean.flac")
        assertEquals("Michael Jackson", d.artist)
        assertEquals("Thriller", d.album)
        assertEquals("Billie Jean", d.title)

        val disc2 = DownloadLayout.describe(listOf("Michael Jackson", "HIStory", "Disc 2"), "03. Stranger in Moscow.flac")
        assertEquals("HIStory", disc2.album)
        assertEquals("Stranger in Moscow", disc2.title)
    }

    @Test
    fun `a title that merely starts with a number keeps it`() {
        // "1999" has no ". " after the number, so it is not a track prefix.
        assertEquals("1999", DownloadLayout.describe(listOf("Prince"), "1999.flac").title)
        assertEquals("7 Rings", DownloadLayout.describe(listOf("Ariana Grande"), "7 Rings.mp3").title)
    }

    @Test
    fun `what the downloader writes, the scan reads back`() {
        val t = target(title = "Wanna Be Startin' Somethin'", track = 1, disc = 2)
        val d = DownloadLayout.describe(listOfNotNull(t.artistFolder, t.albumFolder, t.discFolder), "${t.stem}.flac")
        assertEquals("Michael Jackson", d.artist)
        assertEquals("Thriller", d.album)
        assertEquals("Wanna Be Startin' Somethin'", d.title)
    }
}
