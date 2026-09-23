package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The folder and file names a download gets are what every other player and
 * file manager sees, and a name the provider silently alters is never found
 * again — so each download would make another "Album (1)". Pinned here.
 */
class DownloadPathsTest {

    private fun item(
        title: String = "Song",
        artist: String = "Artist",
        album: String? = "Album",
        albumArtist: String? = null,
        track: Int? = null,
        disc: Int? = null,
    ) = DownloadItem(
        trackId = 1L,
        title = title,
        artistName = artist,
        albumTitle = album,
        albumArtist = albumArtist,
        trackNumber = track,
        discNumber = disc,
    )

    @Test
    fun `sanitize replaces illegal characters and drops trailing dots`() {
        assertEquals("AC_DC", DownloadPaths.sanitize("AC/DC", "x"))
        assertEquals("What_ Why_", DownloadPaths.sanitize("What? Why*", "x"))
        assertEquals("Vol", DownloadPaths.sanitize("Vol. ", "x"))
        assertEquals("A B", DownloadPaths.sanitize("  A \t B ", "x"))
        assertEquals("x", DownloadPaths.sanitize(" ... ", "x"))
        assertEquals("x", DownloadPaths.sanitize(null, "x"))
    }

    @Test
    fun `sanitize caps the segment length`() {
        assertEquals(120, DownloadPaths.sanitize("a".repeat(300), "x").length)
    }

    @Test
    fun `artist folder prefers the album artist`() {
        assertEquals("Band", DownloadPaths.artistFolder(item(artist = "Band feat. Guest", albumArtist = "Band")))
        assertEquals("Solo", DownloadPaths.artistFolder(item(artist = "Solo", albumArtist = " ")))
        assertEquals("Unknown Artist", DownloadPaths.artistFolder(item(artist = "")))
    }

    @Test
    fun `a single gets a folder of its own instead of a shared one`() {
        assertEquals("Album", DownloadPaths.albumFolder(item(album = "Album")))
        assertEquals("Lone Song", DownloadPaths.albumFolder(item(title = "Lone Song", album = null)))
        assertEquals("Lone Song", DownloadPaths.albumFolder(item(title = "Lone Song", album = "")))
    }

    @Test
    fun `track file stem carries the track and disc number`() {
        assertEquals("01. Song", DownloadPaths.trackFileStem(item(track = 1)))
        assertEquals("01. Song", DownloadPaths.trackFileStem(item(track = 1, disc = 1)))
        assertEquals("2-07. Song", DownloadPaths.trackFileStem(item(track = 7, disc = 2)))
        assertEquals("112. Song", DownloadPaths.trackFileStem(item(track = 112)))
        assertEquals("Song", DownloadPaths.trackFileStem(item(track = null, disc = 2)))
        assertEquals("Song", DownloadPaths.trackFileStem(item(track = 0)))
        assertEquals("03. A_B", DownloadPaths.trackFileStem(item(title = "A/B", track = 3)))
    }

    @Test
    fun `title comes back out of a file stem`() {
        assertEquals("Song", DownloadPaths.titleFromStem("01. Song"))
        assertEquals("Song", DownloadPaths.titleFromStem("2-07. Song"))
        assertEquals("Song", DownloadPaths.titleFromStem("Song"))
        assertEquals("1999", DownloadPaths.titleFromStem("1999"))
        assertEquals("01.", DownloadPaths.titleFromStem("01."))
    }
}
