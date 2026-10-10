package tf.monochrome.android.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as DASH in a manifest's stream. Playback and downloads both ask,
 * and a miss on the download side saves the manifest's XML as an ".mp3".
 */
class DashManifestTest {

    @Test
    fun `inline MPD XML is DASH`() {
        assertTrue(isDashManifest("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>"))
    }

    @Test
    fun `a link to an mpd is DASH, signed or not`() {
        assertTrue(isDashManifest("https://cdn.example/track/manifest.mpd"))
        assertTrue(isDashManifest("https://cdn.example/track/manifest.mpd?token=abc&exp=1"))
        assertTrue(isDashManifest("https://cdn.example/track/MANIFEST.MPD"))
        assertTrue(isDashManifest("https://cdn.example/track/manifest.mpd#t=0"))
    }

    @Test
    fun `a single file is not`() {
        assertFalse(isDashManifest("https://cdn.example/track/12345.flac"))
        assertFalse(isDashManifest("https://cdn.example/track/12345.flac?token=abc"))
        assertFalse(isDashManifest("https://cdn.example/track/12345.m4a"))
    }

    @Test
    fun `mpd in the query string is not the path`() {
        // A file served by a script whose query happens to name an .mpd.
        assertFalse(isDashManifest("https://cdn.example/get?file=12345.flac&ref=manifest.mpd"))
    }
}
