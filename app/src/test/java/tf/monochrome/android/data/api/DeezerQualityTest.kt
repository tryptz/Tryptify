package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.AudioQuality

class DeezerQualityTest {

    @Test
    fun `codes are the ones the endpoint accepts`() {
        // /api/deezer/download rejects anything but these, as Qobuz does.
        assertEquals(listOf("5", "6", "7", "27"), DeezerQuality.Tier.entries.map { it.code })
    }

    @Test
    fun `lossy settings ask for mp3 320`() {
        assertEquals(DeezerQuality.Tier.MP3_320, DeezerQuality.tierFor(AudioQuality.LOW))
        assertEquals(DeezerQuality.Tier.MP3_320, DeezerQuality.tierFor(AudioQuality.HIGH))
    }

    @Test
    fun `hi-res is asked for only when the track claims it`() {
        assertEquals(DeezerQuality.Tier.FLAC_CD, DeezerQuality.tierFor(AudioQuality.HI_RES, hires = false))
        assertEquals(DeezerQuality.Tier.FLAC_HI_RES, DeezerQuality.tierFor(AudioQuality.HI_RES, hires = true))
        assertEquals(DeezerQuality.Tier.FLAC_CD, DeezerQuality.tierFor(AudioQuality.LOSSLESS, hires = true))
    }

    @Test
    fun `a cd-quality track offers mp3 and cd flac only`() {
        assertEquals(
            listOf(DeezerQuality.Tier.MP3_320, DeezerQuality.Tier.FLAC_CD),
            DeezerQuality.available(hires = false),
        )
    }

    @Test
    fun `badge follows the catalogue flags`() {
        assertEquals("LOSSLESS", DeezerQuality.badgeFor(hires = false, maximumBitDepth = 16))
        assertEquals("HI_RES_LOSSLESS", DeezerQuality.badgeFor(hires = true, maximumBitDepth = 24))
        assertNull(DeezerQuality.badgeFor(hires = false, maximumBitDepth = null))
    }
}
