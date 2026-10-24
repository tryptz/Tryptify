// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ReplayGain's stage: exact at unity, the gain otherwise, no wrap-around, and back to exact after a glide. */
class ReplayGainProcessorTest {

    private fun processor(encoding: Int, gain: Float = 1f) = ReplayGainProcessor().apply {
        setGain(gain)
        configure(AudioFormat(48_000, 2, encoding))
        flush()
    }

    private fun shorts(vararg v: Short): ByteBuffer =
        ByteBuffer.allocateDirect(v.size * 2).order(ByteOrder.nativeOrder()).apply { v.forEach { putShort(it) }; flip() }

    private fun floats(vararg v: Float): ByteBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).apply { v.forEach { putFloat(it) }; flip() }

    private fun ReplayGainProcessor.runShorts(input: ByteBuffer): ShortArray {
        queueInput(input)
        val out = getOutput()
        return ShortArray(out.remaining() / 2) { out.getShort(out.position() + it * 2) }
    }

    private fun ReplayGainProcessor.runFloats(input: ByteBuffer): FloatArray {
        queueInput(input)
        val out = getOutput()
        return FloatArray(out.remaining() / 4) { out.getFloat(out.position() + it * 4) }
    }

    @Test
    fun `at unity 16-bit samples come through bit for bit`() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, 12345, Short.MAX_VALUE)
        assertArrayEquals(samples, processor(C.ENCODING_PCM_16BIT).runShorts(shorts(*samples)))
    }

    @Test
    fun `at unity float samples come through bit for bit, overs included`() {
        val samples = floatArrayOf(-1f, -0.000123f, 0f, 0.5f, 1f, 1.7f)
        assertArrayEquals(samples, processor(C.ENCODING_PCM_FLOAT).runFloats(floats(*samples)), 0f)
    }

    @Test
    fun `a new stream starts at its gain, with no fade in`() {
        val out = processor(C.ENCODING_PCM_16BIT, gain = 0.5f).runShorts(shorts(1000, -1000, 20000, -20000))
        assertArrayEquals(shortArrayOf(500, -500, 10000, -10000), out)
    }

    @Test
    fun `silence stays silent at any gain`() {
        val out = processor(C.ENCODING_PCM_16BIT, gain = 2f).runShorts(shorts(0, 0, 0, 0))
        assertArrayEquals(shortArrayOf(0, 0, 0, 0), out)
    }

    @Test
    fun `a boost near full scale stops at full scale rather than wrapping`() {
        val out = processor(C.ENCODING_PCM_16BIT, gain = 2f).runShorts(shorts(30000, -30000, 100, -100))
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 200, -200), out)
    }

    @Test
    fun `float is not clipped here, headroom is the later stages' to keep`() {
        val out = processor(C.ENCODING_PCM_FLOAT, gain = 2f).runFloats(floats(0.75f, -0.75f))
        assertArrayEquals(floatArrayOf(1.5f, -1.5f), out, 0f)
    }

    @Test
    fun `a change glides, and back at unity the output is exact again`() {
        val p = processor(C.ENCODING_PCM_16BIT, gain = 1f)
        p.setGain(0.5f)
        // 50 ms at 48 kHz is 2400 frames; a little over, in small buffers as a sink sends them.
        val frame = shortArrayOf(10000, 10000)
        var last = ShortArray(0)
        repeat(100) { last = p.runShorts(shorts(*ShortArray(64) { frame[it % 2] })) }
        assertTrue(last.all { it == 5000.toShort() })
        p.setGain(1f)
        repeat(100) { p.runShorts(shorts(*ShortArray(64) { frame[it % 2] })) }
        val samples = shortArrayOf(-7, 3, 32000, -32000)
        assertArrayEquals(samples, p.runShorts(shorts(*samples)))
    }

    @Test
    fun `the glide is gradual, not a step`() {
        val p = processor(C.ENCODING_PCM_16BIT, gain = 1f)
        p.setGain(0.5f)
        val out = p.runShorts(shorts(*ShortArray(64) { 10000 }))
        assertTrue(out.first() > 9900)
        assertTrue(out.last() in 5001..9999)
    }

    @Test
    fun `other encodings are left alone`() {
        val p = ReplayGainProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)))
        p.flush()
        assertFalse(p.isActive)
    }

    @Test
    fun `nonsense gains are taken as unity`() {
        val p = ReplayGainProcessor()
        p.setGain(Float.NaN)
        assertEquals(1f, p.gain)
        p.setGain(-2f)
        assertEquals(1f, p.gain)
    }
}
