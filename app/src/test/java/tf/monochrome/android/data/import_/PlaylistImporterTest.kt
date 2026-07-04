package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistImporterTest {

    @Test
    fun `extracts id from standard open spotify url`() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImporter.extractSpotifyPlaylistId(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            ),
        )
    }

    @Test
    fun `extracts id from url with query params`() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImporter.extractSpotifyPlaylistId(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123&pt=xyz",
            ),
        )
    }

    @Test
    fun `extracts id from intl url`() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImporter.extractSpotifyPlaylistId(
                "https://open.spotify.com/intl-fr/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x",
            ),
        )
    }

    @Test
    fun `extracts id from spotify uri`() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImporter.extractSpotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"),
        )
    }

    @Test
    fun `rejects non playlist urls`() {
        assertNull(
            PlaylistImporter.extractSpotifyPlaylistId(
                "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy",
            ),
        )
        assertNull(PlaylistImporter.extractSpotifyPlaylistId("https://music.youtube.com/playlist?list=PLx"))
        assertNull(PlaylistImporter.extractSpotifyPlaylistId("not a url"))
    }
}
