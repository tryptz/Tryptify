package tf.monochrome.android.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioQuality

class DeezerStreamUriTest {

    @Test
    fun `round trips every quality`() {
        for (quality in AudioQuality.entries) {
            val parsed = DeezerStreamUri.parse(DeezerStreamUri.build(4_242, quality))
            assertEquals(DeezerStreamUri.Request(4_242, quality), parsed)
        }
    }

    @Test
    fun `recognises only its own scheme`() {
        assertTrue(DeezerStreamUri.matches(DeezerStreamUri.build(1, AudioQuality.LOSSLESS)))
        assertFalse(DeezerStreamUri.matches("https://example.test/track.flac"))
        assertFalse(DeezerStreamUri.matches("file:///music/track.flac"))
        assertFalse(DeezerStreamUri.matches("content://media/external/audio/media/12"))
    }

    @Test
    fun `refuses foreign or malformed uris`() {
        assertNull(DeezerStreamUri.parse("https://example.test/track.flac"))
        assertNull(DeezerStreamUri.parse("deezer://track/not-a-number"))
        assertNull(DeezerStreamUri.parse("deezer://track/"))
    }

    @Test
    fun `an unknown or missing quality falls back to lossless`() {
        assertEquals(
            DeezerStreamUri.Request(77, AudioQuality.LOSSLESS),
            DeezerStreamUri.parse("deezer://track/77?quality=PLATINUM"),
        )
        assertEquals(
            DeezerStreamUri.Request(77, AudioQuality.LOSSLESS),
            DeezerStreamUri.parse("deezer://track/77"),
        )
    }

    @Test
    fun `tolerates extra query parameters`() {
        assertEquals(
            DeezerStreamUri.Request(9, AudioQuality.HI_RES),
            DeezerStreamUri.parse("deezer://track/9?quality=HI_RES&foo=bar"),
        )
    }

    @Test
    fun `negative and large ids survive the round trip`() {
        assertEquals(
            DeezerStreamUri.Request(Long.MAX_VALUE, AudioQuality.HIGH),
            DeezerStreamUri.parse(DeezerStreamUri.build(Long.MAX_VALUE, AudioQuality.HIGH)),
        )
    }

    @Test
    fun `never claims a qobuz uri or the other way round`() {
        // Deezer and Qobuz ids share one number range, so the scheme is the
        // only thing telling the two caches apart.
        assertFalse(DeezerStreamUri.matches(QobuzStreamUri.build(5, AudioQuality.LOSSLESS)))
        assertFalse(QobuzStreamUri.matches(DeezerStreamUri.build(5, AudioQuality.LOSSLESS)))
    }
}
