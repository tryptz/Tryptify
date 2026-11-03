package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.api.ServiceQuality.Setting
import tf.monochrome.android.domain.model.AudioQuality

class ServiceQualityTest {

    @Test
    fun `each service offers only the tiers it can send`() {
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.LOW),
            ServiceQuality.options(ApiService.TIDAL, Setting.WIFI).map { it.quality },
        )
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.HIGH),
            ServiceQuality.options(ApiService.QOBUZ, Setting.CELLULAR).map { it.quality },
        )
        assertEquals(
            // Deezer's own picker: HiFi, High Quality, Standard.
            listOf(AudioQuality.HI_RES, AudioQuality.HIGH, AudioQuality.LOW),
            ServiceQuality.options(ApiService.DEEZER, Setting.DOWNLOAD).map { it.quality },
        )
        assertTrue(ServiceQuality.options(ApiService.APPLE, Setting.WIFI).isEmpty())
    }

    @Test
    fun `TIDAL downloads have no AAC 96, which the download route never sends`() {
        val download = ServiceQuality.options(ApiService.TIDAL, Setting.DOWNLOAD).map { it.quality }
        assertFalse(AudioQuality.LOW in download)
        assertEquals(AudioQuality.HIGH, ServiceQuality.coerce(ApiService.TIDAL, Setting.DOWNLOAD, AudioQuality.LOW))
        assertEquals(AudioQuality.LOW, ServiceQuality.coerce(ApiService.TIDAL, Setting.CELLULAR, AudioQuality.LOW))
    }

    @Test
    fun `the lossy tier is named for the codec each service sends`() {
        assertEquals("AAC 320 kbps", ServiceQuality.option(ApiService.TIDAL, Setting.WIFI, AudioQuality.HIGH)?.label)
        assertEquals("MP3 320 kbps", ServiceQuality.option(ApiService.QOBUZ, Setting.WIFI, AudioQuality.HIGH)?.label)
        assertEquals("MP3 320 kbps", ServiceQuality.option(ApiService.DEEZER, Setting.WIFI, AudioQuality.HIGH)?.label)
        assertEquals("MP3 128 kbps", ServiceQuality.option(ApiService.DEEZER, Setting.CELLULAR, AudioQuality.LOW)?.label)
    }

    @Test
    fun `a carried-over value lands on the nearest tier the service offers`() {
        // Hi-Res on Deezer stays Hi-Res: its server serves 24-bit FLAC since 2026-10-30.
        assertEquals(AudioQuality.HI_RES, ServiceQuality.coerce(ApiService.DEEZER, Setting.WIFI, AudioQuality.HI_RES))
        // The old global Low on Qobuz, whose lowest is MP3 320; Deezer has Standard.
        assertEquals(AudioQuality.HIGH, ServiceQuality.coerce(ApiService.QOBUZ, Setting.CELLULAR, AudioQuality.LOW))
        assertEquals(AudioQuality.LOW, ServiceQuality.coerce(ApiService.DEEZER, Setting.CELLULAR, AudioQuality.LOW))
        // CD FLAC chosen on Deezer before: Deezer's picker has none, the nearer higher is HiFi.
        assertEquals(AudioQuality.HI_RES, ServiceQuality.coerce(ApiService.DEEZER, Setting.WIFI, AudioQuality.LOSSLESS))
        // Offered values are left alone.
        assertEquals(AudioQuality.HI_RES, ServiceQuality.coerce(ApiService.QOBUZ, Setting.DOWNLOAD, AudioQuality.HI_RES))
        assertEquals(AudioQuality.HI_RES, ServiceQuality.option(ApiService.DEEZER, Setting.WIFI, AudioQuality.HI_RES)?.quality)
    }

    @Test
    fun `Apple is not described here and keeps its value`() {
        assertNull(ServiceQuality.option(ApiService.APPLE, Setting.DOWNLOAD, AudioQuality.HI_RES))
        assertEquals(AudioQuality.LOW, ServiceQuality.coerce(ApiService.APPLE, Setting.DOWNLOAD, AudioQuality.LOW))
    }

    @Test
    fun `every option says what it delivers`() {
        ServiceQuality.services.forEach { service ->
            Setting.entries.forEach { setting ->
                ServiceQuality.options(service, setting).forEach { option ->
                    assertTrue("$service ${option.quality} label", option.label.isNotBlank())
                    assertTrue("$service ${option.quality} detail", option.detail != 0)
                }
            }
        }
    }
}
