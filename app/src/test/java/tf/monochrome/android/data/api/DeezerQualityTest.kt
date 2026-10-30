package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioQuality

class DeezerQualityTest {

    @Test
    fun `codes are ones the endpoint accepts`() {
        // /api/deezer/download rejects anything but 5, 6, 7 and 27, as Qobuz does.
        assertTrue(DeezerQuality.Tier.entries.all { it.code in setOf("5", "6", "7", "27") })
    }

    @Test
    fun `hi-res asks for deezer's 24-bit file on every track`() {
        // The catalogue never says which tracks have a hi-res master; the server
        // falls back to CD FLAC for the rest.
        assertEquals(DeezerQuality.Tier.FLAC_HI_RES, DeezerQuality.tierFor(AudioQuality.HI_RES))
        assertEquals("27", DeezerQuality.Tier.FLAC_HI_RES.code)
        assertEquals(24, DeezerQuality.Tier.FLAC_HI_RES.bitDepth)
        assertEquals(192.0, DeezerQuality.Tier.FLAC_HI_RES.sampleRateKhz, 0.0)
    }

    @Test
    fun `lossless asks for cd flac`() {
        assertEquals(DeezerQuality.Tier.FLAC_CD, DeezerQuality.tierFor(AudioQuality.LOSSLESS))
        assertEquals("6", DeezerQuality.Tier.FLAC_CD.code)
    }

    @Test
    fun `lossy settings ask for the mp3 the endpoint serves for 5, which is 128`() {
        assertEquals(DeezerQuality.Tier.MP3_128, DeezerQuality.tierFor(AudioQuality.LOW))
        assertEquals(DeezerQuality.Tier.MP3_128, DeezerQuality.tierFor(AudioQuality.HIGH))
        assertEquals("5", DeezerQuality.Tier.MP3_128.code)
        assertEquals(128, DeezerQuality.Tier.MP3_128.bitrateKbps)
    }

    @Test
    fun `badge follows the catalogue flags`() {
        assertEquals("LOSSLESS", DeezerQuality.badgeFor(hires = false, maximumBitDepth = 16))
        assertEquals("HI_RES_LOSSLESS", DeezerQuality.badgeFor(hires = true, maximumBitDepth = 24))
        assertNull(DeezerQuality.badgeFor(hires = false, maximumBitDepth = null))
    }
}
