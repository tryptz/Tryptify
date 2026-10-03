package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTrimmingAudioProcessorTest {

    // Stereo float: 8 bytes per frame. Frame i holds (i, -i) so every output
    // frame says which input frame it was.
    private val floatStereo = AudioFormat(96_000, 2, C.ENCODING_PCM_FLOAT)

    private fun frames(from: Int, count: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder())
        for (i in from until from + count) {
            b.putFloat(i.toFloat()); b.putFloat(-i.toFloat())
        }
        b.flip()
        return b
    }

    private fun framesOf(out: ByteBuffer): List<Int> {
        val list = mutableListOf<Int>()
        while (out.remaining() >= 8) {
            list += out.getFloat().toInt(); out.getFloat()
        }
        return list
    }

    private fun process(p: PcmTrimmingAudioProcessor, input: ByteBuffer): List<Int> {
        p.queueInput(input)
        return framesOf(p.output)
    }

    private fun configured(start: Int, end: Int): PcmTrimmingAudioProcessor {
        val p = PcmTrimmingAudioProcessor()
        p.setTrimFrameCount(start, end)
        p.configure(floatStereo)
        p.flush()
        return p
    }

    @Test
    fun `counts every frame it drops, delay and padding`() {
        val p = configured(start = 3, end = 4)
        process(p, frames(0, 10))
        assertEquals(3L, p.trimmedFrames)
        // Next track: the held tail is the padding, dropped at its flush.
        p.setTrimFrameCount(0, 0)
        p.configure(floatStereo)
        p.flush()
        assertEquals(7L, p.trimmedFrames)
    }

    @Test
    fun `trims the encoder delay from the start of a float stream`() {
        val p = configured(start = 3, end = 0)
        assertTrue(p.isActive)
        assertTrue(process(p, frames(0, 10)) == (3 until 10).toList())
    }

    @Test
    fun `holds back the padding so the next track drops it`() {
        val p = configured(start = 0, end = 4)
        val out = process(p, frames(0, 10)) + process(p, frames(10, 10))
        // The last four frames seen are held: they may be padding.
        assertTrue(out == (0 until 16).toList())
        // Next track: reconfigure + flush discards the held tail.
        p.configure(floatStereo)
        p.flush()
        assertTrue(process(p, frames(100, 2)).isEmpty())
    }

    @Test
    fun `a flush without a configure is a seek and does not trim again`() {
        val p = configured(start = 5, end = 0)
        process(p, frames(0, 10))
        p.flush()
        assertTrue(process(p, frames(50, 4)) == (50 until 54).toList())
    }

    @Test
    fun `a start trim longer than one buffer spans buffers`() {
        val p = configured(start = 12, end = 0)
        assertTrue(process(p, frames(0, 10)).isEmpty())
        assertTrue(process(p, frames(10, 10)) == (12 until 20).toList())
    }

    @Test
    fun `inactive with nothing to trim`() {
        val p = configured(start = 0, end = 0)
        assertFalse(p.isActive)
    }

    @Test
    fun `sixteen bit frames trim by their own size`() {
        val p = PcmTrimmingAudioProcessor()
        p.setTrimFrameCount(2, 0)
        p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val input = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 4) { input.putShort(i.toShort()); input.putShort(i.toShort()) }
        input.flip()
        p.queueInput(input)
        val out = p.output
        val got = ShortArray(out.remaining() / 2) { out.getShort() }
        assertArrayEquals(shortArrayOf(2, 2, 3, 3), got)
    }
}
