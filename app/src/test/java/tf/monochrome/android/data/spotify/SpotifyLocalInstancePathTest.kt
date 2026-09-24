package tf.monochrome.android.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The localhost proxy attaches the user's Spotify token to whatever it
 * forwards, so what it forwards has to stay inside `/v1`.
 */
class SpotifyLocalInstancePathTest {

    private fun path(vararg s: String) = SpotifyLocalInstance.upstreamPath(s.toList())

    @Test
    fun `ordinary Web API paths pass through`() {
        assertEquals("playlists/37i9dQZF1DXcBWIGoYBM5M/tracks", path("playlists", "37i9dQZF1DXcBWIGoYBM5M", "tracks"))
        assertEquals("search", path("search"))
    }

    @Test
    fun `segments that climb or collapse are refused`() {
        assertNull(path("..", "me"))
        assertNull(path("albums", ".", "x"))
        assertNull(path("albums", "", "x"))
        assertNull(path())
    }

    @Test
    fun `decoded separators are encoded again rather than obeyed`() {
        assertEquals("albums/a%2F..%2Fme", path("albums", "a/../me"))
        assertEquals("albums/a%3Fx=1", path("albums", "a?x=1"))
    }
}
