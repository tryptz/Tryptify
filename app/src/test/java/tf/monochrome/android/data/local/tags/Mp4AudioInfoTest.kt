package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Real MP4 files made by ffmpeg (src/test/resources/mp4), read back for Track
 * info: jaudiotagger read a 5.1 AAC file as "-14" channels and 16-bit, and
 * ALAC as AAC.
 */
class Mp4AudioInfoTest {

    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("mp4/$name")
        assertNotNull("missing fixture $name", url)
        return File(url!!.toURI())
    }

    @Test
    fun `5_1 AAC is six channels of AAC-LC, with no bit depth`() {
        val info = Mp4AudioInfo.read(fixture("aac_51.m4a"))!!
        assertEquals("AAC-LC", info.codec)
        assertEquals(6, info.channels)
        assertEquals(48_000, info.sampleRateHz)
        assertNull(info.bitsPerSample)
        assertFalse(info.lossless)
        assertFalse(info.fragmented)
        assertTrue("bitrate ${info.bitrateBps}", info.bitrateBps!! in 200_000L..320_000L)
        assertEquals(1.0, info.durationSeconds!!, 0.1)
    }

    @Test
    fun `ALAC is ALAC, at its own depth and rate`() {
        val info = Mp4AudioInfo.read(fixture("alac_24_96.m4a"))!!
        assertEquals("ALAC", info.codec)
        assertTrue(info.lossless)
        assertEquals(2, info.channels)
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(24, info.bitsPerSample)
    }

    @Test
    fun `a fragmented ALAC file, as Apple's are, is read from its moov`() {
        val info = Mp4AudioInfo.read(fixture("alac_fragmented.m4a"))!!
        assertEquals("ALAC", info.codec)
        assertTrue(info.fragmented)
        assertEquals(2, info.channels)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(16, info.bitsPerSample)
    }

    @Test
    fun `Track info shows the sample entry's facts over jaudiotagger's`() {
        val wrong = FileTags(
            path = "x.m4a", sizeBytes = 1, format = "Aac", sampleRateHz = 48_000, bitsPerSample = 16,
            bitrateKbps = 255, channels = "-14", lengthSeconds = 1.0, lossless = false, pictures = 0, tags = emptyList(),
        )
        val fixed = FileTagsReader.withStream(wrong, Mp4AudioInfo.read(fixture("aac_51.m4a"))!!)
        assertEquals("AAC-LC", fixed.format)
        assertEquals("6 (5.1)", fixed.channels)
        assertNull("lossy audio has no bit depth", fixed.bitsPerSample)
    }

    @Test
    fun `the whole reader gives the right answer for each file`() {
        val aac = FileTagsReader.read(fixture("aac_51.m4a").path)!!
        assertEquals("AAC-LC", aac.format)
        assertEquals("6 (5.1)", aac.channels)
        assertNull(aac.bitsPerSample)
        val alac = FileTagsReader.read(fixture("alac_fragmented.m4a").path)!!
        assertEquals("ALAC", alac.format)
        assertEquals(true, alac.lossless)
    }

    @Test
    fun `AudioSpecificConfig names the profile and the layout`() {
        // AOT 2 (LC), 48 kHz (index 3), channel config 6 (5.1).
        val lc = AudioSpecificConfig.parse(byteArrayOf(0x11, 0xB0.toByte()), 0, 2)!!
        assertEquals("AAC-LC", lc.name)
        assertEquals(6, lc.channels)
        assertEquals(48_000, lc.sampleRate)
        // AOT 5 (HE-AAC), 24 kHz core (index 6), stereo, 48 kHz extension (index 3).
        val he = AudioSpecificConfig.parse(byteArrayOf(0x2B, 0x11, 0x88.toByte(), 0x00), 0, 4)!!
        assertEquals("HE-AAC", he.name)
        assertEquals(2, he.channels)
        assertEquals(48_000, he.sampleRate)
    }

    @Test
    fun `not an MP4 is not read`() {
        val notMp4 = File.createTempFile("notmp4", ".m4a").apply { writeText("not an mp4 at all"); deleteOnExit() }
        assertNull(Mp4AudioInfo.read(notMp4))
    }
}
