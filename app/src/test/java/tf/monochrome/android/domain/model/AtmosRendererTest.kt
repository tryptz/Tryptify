package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Atmos renderer domain infrastructure: Atmos detection,
 * codec capability, channel-layout mapping and renderer-profile defaults.
 */
class AtmosRendererTest {

    // ---- isDolbyAtmos ------------------------------------------------------

    @Test
    fun `JOC extension flag is authoritative`() {
        // A confirmed JOC extension is Atmos regardless of the text.
        assertTrue(isDolbyAtmos(hasJocExtension = true, title = "Plain Song"))
        // A confirmed absence overrides even an "Atmos" phrase in the title.
        assertFalse(isDolbyAtmos(hasJocExtension = false, title = "Song (Dolby Atmos)"))
    }

    @Test
    fun `atmos or joc mime type is detected`() {
        assertTrue(isDolbyAtmos(mimeType = "audio/eac3-joc"))
        assertTrue(isDolbyAtmos(mimeType = "audio/vnd.dolby.atmos"))
        assertFalse(isDolbyAtmos(mimeType = "audio/eac3")) // plain DD+ is not Atmos
    }

    @Test
    fun `dolby atmos phrase in metadata is detected`() {
        assertTrue(isDolbyAtmos(version = "Dolby Atmos"))
        assertTrue(isDolbyAtmos(title = "Track (dolby  atmos)"))
        assertTrue(isDolbyAtmos(albumVersion = "Dolby Atmos Edition"))
        assertTrue(isDolbyAtmos(albumTitle = "Some Album (Dolby Atmos)"))
    }

    @Test
    fun `bare atmos or plain text is not detected`() {
        assertFalse(isDolbyAtmos(title = "Atmosphere")) // missing "dolby"
        assertFalse(isDolbyAtmos(version = "Remastered"))
        assertFalse(isDolbyAtmos())
    }

    // ---- isAtmosCapableCodec ----------------------------------------------

    @Test
    fun `ec3 family is atmos-capable by codec, mime or extension`() {
        assertTrue(isAtmosCapableCodec(codec = "EC-3"))
        assertTrue(isAtmosCapableCodec(codec = "E-AC-3 JOC"))
        assertTrue(isAtmosCapableCodec(mimeType = "audio/eac3"))
        assertTrue(isAtmosCapableCodec(fileExtension = ".ec3"))
        assertTrue(isAtmosCapableCodec(fileExtension = "eac3"))
    }

    @Test
    fun `lossless and lossy stereo codecs are not atmos-capable`() {
        assertFalse(isAtmosCapableCodec(codec = "FLAC"))
        assertFalse(isAtmosCapableCodec(codec = "ALAC"))
        assertFalse(isAtmosCapableCodec(mimeType = "audio/mpeg"))
        assertFalse(isAtmosCapableCodec(fileExtension = ".wav"))
        assertFalse(isAtmosCapableCodec())
    }

    // ---- ChannelLayout -----------------------------------------------------

    @Test
    fun `channel count maps to the nearest known layout`() {
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromChannelCount(null))
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromChannelCount(1))
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromChannelCount(2))
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelLayout.fromChannelCount(6))
        assertEquals(ChannelLayout.SURROUND_7_1, ChannelLayout.fromChannelCount(8))
        assertEquals(ChannelLayout.ATMOS_7_1_4, ChannelLayout.fromChannelCount(12))
        assertEquals(ChannelLayout.ATMOS_7_1_4, ChannelLayout.fromChannelCount(16))
    }

    @Test
    fun `layout channel counts match the native VBAP bed layouts`() {
        assertEquals(2, ChannelLayout.STEREO.channelCount)
        assertEquals(6, ChannelLayout.SURROUND_5_1.channelCount)
        assertEquals(8, ChannelLayout.SURROUND_7_1.channelCount)
        assertEquals(12, ChannelLayout.ATMOS_7_1_4.channelCount)
    }

    // ---- RendererProfile ---------------------------------------------------

    @Test
    fun `default profile is safe passthrough stereo`() {
        val p = RendererProfile.DEFAULT
        assertEquals(RendererMode.PASSTHROUGH, p.mode)
        assertEquals(ChannelLayout.STEREO, p.layout)
        assertEquals(null, p.hrtfProfileId)
    }

    @Test
    fun `renderer mode default is passthrough`() {
        assertEquals(RendererMode.PASSTHROUGH, RendererMode.DEFAULT)
    }
}
