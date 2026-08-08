package tf.monochrome.android.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioQuality

class QobuzStreamUriTest {

    @Test
    fun `round trips every quality`() {
        for (quality in AudioQuality.entries) {
            val parsed = QobuzStreamUri.parse(QobuzStreamUri.build(4_242, quality))
            assertEquals(QobuzStreamUri.Request(4_242, quality), parsed)
        }
    }

    @Test
    fun `recognises only its own scheme`() {
        assertTrue(QobuzStreamUri.matches(QobuzStreamUri.build(1, AudioQuality.LOSSLESS)))
        assertFalse(QobuzStreamUri.matches("https://example.test/track.flac"))
        assertFalse(QobuzStreamUri.matches("file:///music/track.flac"))
        assertFalse(QobuzStreamUri.matches("content://media/external/audio/media/12"))
    }

    @Test
    fun `refuses foreign or malformed uris`() {
        assertNull(QobuzStreamUri.parse("https://example.test/track.flac"))
        assertNull(QobuzStreamUri.parse("qobuz://track/not-a-number"))
        assertNull(QobuzStreamUri.parse("qobuz://track/"))
    }

    @Test
    fun `an unknown or missing quality falls back to lossless`() {
        assertEquals(
            QobuzStreamUri.Request(77, AudioQuality.LOSSLESS),
            QobuzStreamUri.parse("qobuz://track/77?quality=PLATINUM"),
        )
        assertEquals(
            QobuzStreamUri.Request(77, AudioQuality.LOSSLESS),
            QobuzStreamUri.parse("qobuz://track/77"),
        )
    }

    @Test
    fun `tolerates extra query parameters`() {
        assertEquals(
            QobuzStreamUri.Request(9, AudioQuality.HI_RES),
            QobuzStreamUri.parse("qobuz://track/9?quality=HI_RES&foo=bar"),
        )
    }

    @Test
    fun `negative and large ids survive the round trip`() {
        assertEquals(
            QobuzStreamUri.Request(Long.MAX_VALUE, AudioQuality.HIGH),
            QobuzStreamUri.parse(QobuzStreamUri.build(Long.MAX_VALUE, AudioQuality.HIGH)),
        )
    }
}
