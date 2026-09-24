package tf.monochrome.android.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.cache.SpotifyShadowUri

class SpotifyPcmUriTest {

    private val uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"

    @Test
    fun `round trips a track uri and duration`() {
        val built = SpotifyPcmUri.build(uri, 213_573)
        assertEquals("spotify-pcm://track/4uLU6hMCjMI75M1A2tKUQC?durationMs=213573", built)
        assertEquals(SpotifyShadowUri.Request(uri, 213_573), SpotifyPcmUri.parse(built))
    }

    @Test
    fun `pcm and shadow uris never match each other's scheme`() {
        val pcm = SpotifyPcmUri.build(uri, 1_000)
        val shadow = SpotifyShadowUri.build(uri, 1_000)
        assertTrue(SpotifyPcmUri.matches(pcm))
        assertFalse(SpotifyPcmUri.matches(shadow))
        assertFalse(SpotifyShadowUri.matches(pcm))
        assertNull(SpotifyShadowUri.parse(pcm))
        assertNull(SpotifyPcmUri.parse(shadow))
    }

    @Test
    fun `rejects bad ids and durations`() {
        assertNull(SpotifyPcmUri.parse("spotify-pcm://track/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(SpotifyPcmUri.parse("spotify-pcm://track/4uLU6hMCjMI75M1A2tKUQC?durationMs=0"))
        assertNull(SpotifyPcmUri.parse("spotify-pcm://track/short?durationMs=1000"))
    }
}
