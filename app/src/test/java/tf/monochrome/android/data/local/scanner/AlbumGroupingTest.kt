package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tf.monochrome.android.data.local.scanner.AlbumGrouping.Facts

class AlbumGroupingTest {

    private val beatles = "Monochrome /The Beatles/1"

    private fun track(
        album: String? = "1",
        albumArtist: String? = "The Beatles",
        artist: String? = "The Beatles",
        year: Int? = 2000,
        folder: String = beatles,
    ) = Facts(album, albumArtist, artist, year, folder)

    @Test
    fun `fully tagged tracks group as before`() {
        val keys = AlbumGrouping.keys(listOf(track(), track()))
        assertEquals(MediaScanner.buildAlbumGroupingKey("1", "The Beatles", 2000), keys[0])
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun `a single without album artist or year joins its album`() {
        // "She Loves You", downloaded as a single next to the album it is on.
        val keys = AlbumGrouping.keys(listOf(track(), track(albumArtist = null, year = null)))
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun `a featured-artist track without album artist joins the album in its folder`() {
        val keys = AlbumGrouping.keys(
            listOf(track(), track(albumArtist = null, artist = "The Beatles, Billy Preston", year = 2000))
        )
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun `a missing year takes the album's most common year`() {
        val keys = AlbumGrouping.keys(listOf(track(year = 2000), track(year = 2000), track(year = 2015), track(year = null)))
        assertEquals(keys[0], keys[3])
    }

    @Test
    fun `the same title in two years stays two releases`() {
        val keys = AlbumGrouping.keys(listOf(track(year = 2000), track(year = 2015)))
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `the same title by two album artists stays apart`() {
        val keys = AlbumGrouping.keys(
            listOf(
                track(album = "Greatest Hits", albumArtist = "Queen", artist = "Queen", folder = "Music"),
                track(album = "Greatest Hits", albumArtist = "ABBA", artist = "ABBA", folder = "Music"),
            )
        )
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `in a flat folder, another artist's same-titled album is not borrowed`() {
        // Download/ holds Queen's "Greatest Hits" and an ABBA track tagged
        // with the same album title but no album artist or year.
        val keys = AlbumGrouping.keys(
            listOf(
                track(album = "Greatest Hits", albumArtist = "Queen", artist = "Queen", year = 1981, folder = "Download"),
                track(album = "Greatest Hits", albumArtist = null, artist = "ABBA", year = null, folder = "Download"),
            )
        )
        assertNotEquals(keys[0], keys[1])
        assertEquals(MediaScanner.buildAlbumGroupingKey("Greatest Hits", "ABBA", null), keys[1])
    }

    @Test
    fun `a compilation's single joins it through the track artists`() {
        val keys = AlbumGrouping.keys(
            listOf(
                track(album = "Now 50", albumArtist = "Various Artists", artist = "Robyn", year = 2001, folder = "Download"),
                track(album = "Now 50", albumArtist = null, artist = "Robyn", year = null, folder = "Download"),
            )
        )
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun `an album artist is only borrowed within the same folder`() {
        val keys = AlbumGrouping.keys(
            listOf(
                track(album = "Greatest Hits", albumArtist = "Queen", artist = "Queen", year = null, folder = "Queen/Greatest Hits"),
                track(album = "Greatest Hits", albumArtist = null, artist = "ABBA", year = null, folder = "ABBA/Greatest Hits"),
            )
        )
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `tracks without an album tag keep their own key`() {
        val untagged = track(album = null, albumArtist = null, artist = "Someone", year = null)
        val keys = AlbumGrouping.keys(listOf(track(), untagged))
        assertEquals(MediaScanner.buildAlbumGroupingKey(null, "Someone", null), keys[1])
    }
}
