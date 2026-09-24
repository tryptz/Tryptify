package tf.monochrome.android.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPcmUriTest {
    private val trackId = "4uLU6hMCjMI75M1A2tKUQC"

    @Test
    fun `native PCM URI round trips track and duration`() {
        val encoded = SpotifyPcmUri.build("spotify:track:$trackId", 213_456L)
        val parsed = SpotifyPcmUri.parse(encoded)

        assertEquals("spotify:track:$trackId", parsed?.spotifyUri)
        assertEquals(213_456L, parsed?.durationMs)
        assertTrue(SpotifyPcmUri.matches(encoded))
    }

    @Test
    fun `malformed ids and durations are rejected`() {
        assertNull(SpotifyPcmUri.parse("spotify-pcm://track/short?durationMs=1000"))
        assertNull(SpotifyPcmUri.parse("spotify-pcm://track/$trackId?durationMs=0"))
    }
}
