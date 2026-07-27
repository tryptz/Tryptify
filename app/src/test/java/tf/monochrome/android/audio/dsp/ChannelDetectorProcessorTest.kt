package tf.monochrome.android.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure-JVM tests for the passive channel detector tap. */
class ChannelDetectorProcessorTest {

    private fun floatBuffer(vararg frames: FloatArray): ByteBuffer {
        val ch = frames[0].size
        val buf = ByteBuffer.allocateDirect(frames.size * ch * 4).order(ByteOrder.nativeOrder())
        for (frame in frames) for (s in frame) buf.putFloat(s)
        buf.flip()
        return buf
    }

    @Test
    fun `pass-through leaves audio byte-identical`() {
        val p = ChannelDetectorProcessor()
        p.configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT))
        p.flush()
        val frames = arrayOf(
            floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f),
            floatArrayOf(0.7f, -0.8f, 0.9f, -1.0f, 0.11f, -0.12f),
        )
        val input = floatBuffer(*frames)
        val expected = ByteArray(input.remaining())
        input.duplicate().get(expected)

        p.queueInput(input)
        assertEquals(0, input.remaining()) // fully consumed
        val out = p.getOutput()
        val actual = ByteArray(out.remaining())
        out.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `configure passes the format through unchanged`() {
        val p = ChannelDetectorProcessor()
        val fmt = AudioFormat(96000, 16, C.ENCODING_PCM_FLOAT)
        assertEquals(fmt, p.configure(fmt))
        assertTrue(p.isActive)
    }

    @Test
    fun `flush publishes the detected format`() {
        val p = ChannelDetectorProcessor()
        assertNull(p.state.value)
        p.configure(AudioFormat(48000, 16, C.ENCODING_PCM_FLOAT))
        p.flush()
        val s = p.state.value
        assertNotNull(s)
        assertEquals(16, s!!.channelCount)
        assertEquals("9.1.6", s.layoutName)
        assertEquals(48000, s.sampleRate)
        assertTrue(s.isFloat)
        assertEquals("TBR", s.channelNames.last())
        // No subscriber yet — every channel floored.
        assertTrue(s.peaksDb.all { it <= ChannelDetectorProcessor.ACTIVE_THRESHOLD_DB })
    }

    @Test
    fun `metering tracks the loud channel only while acquired`() {
        val p = ChannelDetectorProcessor()
        p.configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT))
        p.flush()

        // Not acquired: no peak scan.
        p.queueInput(floatBuffer(floatArrayOf(0f, 0f, 0f, 0f, 0.9f, 0f)))
        p.getOutput()
        assertTrue(p.currentPeaks().all { it == 0f })

        p.acquire()
        try {
            p.queueInput(floatBuffer(floatArrayOf(0f, 0f, 0f, 0f, 0.9f, 0f)))
            p.getOutput()
            val peaks = p.currentPeaks()
            // ≤ one 0.72× decay tick may land between queueInput and here.
            assertTrue("BL peak ${peaks[4]}", peaks[4] in 0.6f..0.91f)
            for (c in intArrayOf(0, 1, 2, 3, 5)) assertEquals(0f, peaks[c], 0f)
        } finally {
            p.release()
        }
    }

    @Test
    fun `pcm16 metering normalizes to full scale`() {
        val p = ChannelDetectorProcessor()
        p.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.acquire()
        try {
            val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            buf.putShort(16384) // FL at half scale
            buf.putShort(0)
            buf.flip()
            p.queueInput(buf)
            p.getOutput()
            val peaks = p.currentPeaks()
            // ≤ one 0.72× decay tick may land between queueInput and here.
            assertTrue("FL peak ${peaks[0]}", peaks[0] in 0.35f..0.51f)
            assertEquals(0f, peaks[1], 0f)
        } finally {
            p.release()
        }
    }

    @Test
    fun `reset clears the published state`() {
        val p = ChannelDetectorProcessor()
        p.configure(AudioFormat(48000, 8, C.ENCODING_PCM_FLOAT))
        p.flush()
        assertNotNull(p.state.value)
        assertEquals("7.1", p.state.value!!.layoutName)
        p.reset()
        assertNull(p.state.value)
    }

    @Test
    fun `layout names and channel orders`() {
        assertEquals("5.1", ChannelDetectorProcessor.layoutName(6))
        assertEquals("7.1", ChannelDetectorProcessor.layoutName(8))
        assertEquals("9.1.6", ChannelDetectorProcessor.layoutName(16))
        assertEquals("12 ch", ChannelDetectorProcessor.layoutName(12))
        assertEquals(
            listOf(
                "FL", "FR", "FC", "LFE", "BL", "BR", "BLC", "BRC",
                "SL", "SR", "TFL", "TFR", "TSL", "TSR", "TBL", "TBR",
            ),
            ChannelDetectorProcessor.channelNames(16),
        )
        assertEquals(List(12) { "Ch ${it + 1}" }, ChannelDetectorProcessor.channelNames(12))
    }
}
