package tf.monochrome.android.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyShadowUriTest {

    private val uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"

    @Test
    fun `round trips a track uri and its duration`() {
        val parsed = SpotifyShadowUri.parse(SpotifyShadowUri.build(uri, 213_573))
        assertEquals(SpotifyShadowUri.Request(uri, 213_573), parsed)
    }

    @Test
    fun `recognises only its own scheme`() {
        assertTrue(SpotifyShadowUri.matches(SpotifyShadowUri.build(uri, 1_000)))
        assertFalse(SpotifyShadowUri.matches("qobuz://track/42?quality=LOSSLESS"))
        assertFalse(SpotifyShadowUri.matches("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertFalse(SpotifyShadowUri.matches(uri))
    }

    @Test
    fun `refuses a missing, zero or malformed duration`() {
        assertNull(SpotifyShadowUri.parse("spotify-shadow://track/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(SpotifyShadowUri.parse("spotify-shadow://track/4uLU6hMCjMI75M1A2tKUQC?durationMs=0"))
        assertNull(SpotifyShadowUri.parse("spotify-shadow://track/4uLU6hMCjMI75M1A2tKUQC?durationMs=long"))
    }

    @Test
    fun `refuses an id that is not a spotify track id`() {
        assertNull(SpotifyShadowUri.parse("spotify-shadow://track/short?durationMs=1000"))
        assertNull(SpotifyShadowUri.parse("spotify-shadow://track/4uLU6hMCjMI75M1A2tKU-C?durationMs=1000"))
        assertNull(SpotifyShadowUri.parse("spotify-shadow://track/?durationMs=1000"))
    }

    @Test
    fun `track id validation matches spotify's 22-char base62`() {
        assertTrue(SpotifyShadowUri.isTrackId("4uLU6hMCjMI75M1A2tKUQC"))
        assertFalse(SpotifyShadowUri.isTrackId("4uLU6hMCjMI75M1A2tKUQ"))
        assertFalse(SpotifyShadowUri.isTrackId("4uLU6hMCjMI75M1A2tKUQC1"))
        assertFalse(SpotifyShadowUri.isTrackId("4uLU6hMCjMI75M1A2tKU_C"))
    }
}
