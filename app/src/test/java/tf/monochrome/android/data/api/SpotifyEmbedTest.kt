package tf.monochrome.android.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify's public embed page, read the way a pasted playlist link is read.
 * The fixtures are real embed pages (src/test/resources/spotify), trimmed to
 * the fields read and with the page's session token removed.
 */
class SpotifyEmbedTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun page(name: String) =
        javaClass.classLoader!!.getResource("spotify/$name")!!.readText()

    @Test
    fun `a playlist's name and songs come from its embed`() {
        val playlist = SpotifyEmbed.parse(page("embed_all_out_2000s_short.html"), json)!!
        assertEquals("All Out 2000s", playlist.name)
        val first = playlist.tracks.first()
        assertEquals("Oops!...I Did It Again", first.title)
        assertEquals("Britney Spears", first.artist)
        assertEquals("", first.album)
        assertTrue(first.durationMs > 100_000)
        assertTrue(playlist.coverUrl!!.startsWith("https://i.scdn.co/image/"))
    }

    @Test
    fun `several artists are written plainly, without no-break spaces`() {
        val playlist = SpotifyEmbed.parse(page("embed_all_out_2000s_short.html"), json)!!
        val marmalade = playlist.tracks.first { it.title.startsWith("Lady Marmalade") }
        assertEquals("Christina Aguilera, Lil' Kim, Mýa, P!nk", marmalade.artist)
        assertFalse(playlist.tracks.any { ' ' in it.artist })
    }

    @Test
    fun `a podcast episode in a playlist is skipped`() {
        val playlist = SpotifyEmbed.parse(page("embed_all_out_2000s_short.html"), json)!!
        assertEquals(7, playlist.tracks.size)
        assertFalse(playlist.tracks.any { it.title == "A podcast episode" })
    }

    @Test
    fun `the embed lists at most 100, and the page says how many there are`() {
        val playlist = SpotifyEmbed.parse(page("embed_all_out_80s.html"), json)!!
        assertEquals("All Out 80s", playlist.name)
        assertEquals(SpotifyEmbed.EMBED_LIMIT, playlist.tracks.size)
        assertEquals(150, SpotifyEmbed.songCount(page("playlist_page_head.html")))
    }

    @Test
    fun `a missing or private playlist is no playlist`() {
        assertNull(SpotifyEmbed.parse(page("embed_missing.html"), json))
        assertNull(SpotifyEmbed.parse("<html>not an embed</html>", json))
    }

    @Test
    fun `links in every form Spotify hands out`() {
        val id = "37i9dQZF1DX4UtSsGT1Sbe"
        assertEquals(id, SpotifyEmbed.playlistId("https://open.spotify.com/playlist/$id"))
        assertEquals(id, SpotifyEmbed.playlistId("https://open.spotify.com/playlist/$id?si=a1b2c3d4e5f6"))
        assertEquals(id, SpotifyEmbed.playlistId("https://open.spotify.com/intl-de/playlist/$id"))
        assertEquals(id, SpotifyEmbed.playlistId("spotify:playlist:$id"))
        assertEquals(id, SpotifyEmbed.playlistId("Check this out! https://open.spotify.com/playlist/$id?si=x"))
        assertNull(SpotifyEmbed.playlistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyEmbed.playlistId("https://open.spotify.com/playlist/${id}EXTRA"))
        assertEquals("https://spotify.link/AbC123xyz", SpotifyEmbed.shortLink("listen: https://spotify.link/AbC123xyz"))
        assertTrue(SpotifyEmbed.looksImportable("https://spotify.link/AbC123xyz"))
        assertFalse(SpotifyEmbed.looksImportable("https://music.youtube.com/playlist?list=PL123"))
    }
}
