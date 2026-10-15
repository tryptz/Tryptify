package tf.monochrome.android.audio.mp4

import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import tf.monochrome.android.audio.wav.TryptifyExtractors

/**
 * Seeking in fragmented MP4 without a `sidx`, the way Apple's ALAC files are
 * laid out, run through Media3's real extractor on real files made by ffmpeg
 * (3 s of 44.1 kHz ALAC in 0.5 s fragments).
 */
class IndexedFragmentedMp4ExtractorTest {

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("mp4/$name")!!.toURI())

    @Test
    fun `Media3 alone cannot seek in such a file, which is the bug`() {
        ExtractorHarness(fixture("alac_fragmented.m4a"), FragmentedMp4Extractor()).use { h ->
            h.readFrom(0, samples = 3)
            assertFalse(h.seekMap!!.isSeekable)
        }
    }

    @Test
    fun `indexed, it is seekable and knows its length`() {
        ExtractorHarness(fixture("alac_fragmented.m4a"), IndexedFragmentedMp4Extractor(FragmentedMp4Extractor())).use { h ->
            h.readFrom(0, samples = 3)
            val map = h.seekMap!!
            assertTrue(map.isSeekable)
            assertTrue(map is ChunkIndex)
            assertEquals(6, (map as ChunkIndex).length)
            assertEquals("duration ${map.durationUs}", 3_000_000.0, map.durationUs.toDouble(), 100_000.0)
            // It still plays from the start, as it did.
            assertEquals(0L, h.sampleTimesUs.first())
        }
    }

    @Test
    fun `a seek lands at the time asked for, forward and back`() {
        ExtractorHarness(fixture("alac_fragmented.m4a"), IndexedFragmentedMp4Extractor(FragmentedMp4Extractor())).use { h ->
            h.readFrom(0, samples = 3)
            for (target in listOf(2_000_000L, 1_200_000L, 2_700_000L, 300_000L)) {
                h.seekTo(target, samples = 3)
                val first = h.sampleTimesUs.first()
                // From the sample at or just before the time (ALAC's frames are ~93 ms).
                assertTrue("seek to $target began at $first", first in (target - 600_000)..target)
                assertTrue("seek to $target read nothing after", h.sampleTimesUs.size == 3)
            }
        }
    }

    @Test
    fun `a file with its own sidx is left to Media3`() {
        ExtractorHarness(fixture("alac_fragmented_sidx.m4a"), IndexedFragmentedMp4Extractor(FragmentedMp4Extractor())).use { h ->
            h.readFrom(0, samples = 3)
            assertTrue(h.seekMap!!.isSeekable)
            h.seekTo(2_000_000L)
            assertTrue(h.sampleTimesUs.first() in 1_400_000L..2_000_000L)
        }
    }

    @Test
    fun `an ordinary ALAC file was always seekable`() {
        ExtractorHarness(fixture("alac_24_96.m4a"), Mp4Extractor()).use { h ->
            h.readFrom(0, samples = 1)
            assertTrue(h.seekMap!!.isSeekable)
        }
    }

    @Test
    fun `a file on the device is indexed, a stream is not`() {
        // Walking every fragment first costs a moment on a file. Over HTTP it
        // was a request per fragment, or the whole file, before anything
        // played, and then the file again from the start.
        for (scheme in listOf(null, "", "file", "content", "FILE", "android.resource")) {
            assertTrue("$scheme", TryptifyExtractors.isOnDevice(scheme))
        }
        for (scheme in listOf("https", "http", "HTTPS", "rtsp")) {
            assertFalse(scheme, TryptifyExtractors.isOnDevice(scheme))
        }
    }

    @Test
    fun `only a file on the device gets the indexing wrapper`() {
        fun fragmentedOf(index: Boolean) =
            TryptifyExtractors.wrap(arrayOf(FragmentedMp4Extractor()), indexFragmentedMp4 = index).single()
        assertTrue(fragmentedOf(index = true) is IndexedFragmentedMp4Extractor)
        assertTrue(fragmentedOf(index = false) is FragmentedMp4Extractor)
    }
}
