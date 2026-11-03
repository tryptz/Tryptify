package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioQuality

class DeezerQualityTest {

    @Test
    fun `codes are ones the endpoint accepts`() {
        // /api/deezer/download takes Qobuz's ids, and Deezer formats by name.
        val accepted = setOf("5", "6", "7", "27", "FLAC_24", "FLAC", "MP3_320", "MP3_128")
        assertTrue(DeezerQuality.Tier.entries.all { it.code in accepted })
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
    fun `high quality is mp3 320 by name and standard is mp3 128`() {
        // As Deezer's own picker: High Quality 320 kbps, Standard 128 kbps.
        assertEquals(DeezerQuality.Tier.MP3_320, DeezerQuality.tierFor(AudioQuality.HIGH))
        assertEquals("MP3_320", DeezerQuality.Tier.MP3_320.code)
        assertEquals(DeezerQuality.Tier.MP3_128, DeezerQuality.tierFor(AudioQuality.LOW))
        assertEquals("5", DeezerQuality.Tier.MP3_128.code)
    }

    @Test
    fun `a refused tier falls to the lossy ones below it`() {
        // A server from before format names refuses MP3_320: High Quality lands on 128.
        assertEquals(
            listOf(DeezerQuality.Tier.MP3_320, DeezerQuality.Tier.MP3_128),
            DeezerQuality.ladder(DeezerQuality.Tier.MP3_320),
        )
        assertEquals(
            listOf(DeezerQuality.Tier.FLAC_HI_RES, DeezerQuality.Tier.MP3_320, DeezerQuality.Tier.MP3_128),
            DeezerQuality.ladder(DeezerQuality.Tier.FLAC_HI_RES),
        )
        assertEquals(listOf(DeezerQuality.Tier.MP3_128), DeezerQuality.ladder(DeezerQuality.Tier.MP3_128))
    }

    @Test
    fun `badge follows the catalogue flags`() {
        assertEquals("LOSSLESS", DeezerQuality.badgeFor(hires = false, maximumBitDepth = 16))
        assertEquals("HI_RES_LOSSLESS", DeezerQuality.badgeFor(hires = true, maximumBitDepth = 24))
        assertNull(DeezerQuality.badgeFor(hires = false, maximumBitDepth = null))
    }
}
