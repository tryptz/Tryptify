package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The crossfade tail's meeting point with the USB stream. The main sink can
 * be short-written by the DAC, so nothing it peeks may be lost or played
 * twice — only what it consumes moves on.
 */
class UsbCrossfadeMixTest {

    private fun floats(vararg v: Float): ByteBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).apply {
            v.forEach { putFloat(it) }
            flip()
        }

    private fun mix(capacitySeconds: Float = 0.5f) = UsbCrossfadeMix(capacitySeconds).apply {
        setConsumer(rate = 48000, channels = 2)
        open(channels = 2)
        playing = true
    }

    @Test
    fun `peek does not take, consume does`() {
        val m = mix()
        m.offer(floats(1f, -1f, 2f, -2f, 3f, -3f))
        val dst = FloatArray(4)
        assertEquals(2, m.peek(dst, 2, 2))
        assertEquals(listOf(1f, -1f, 2f, -2f), dst.toList())
        // The DAC took one frame of the two: the second comes round again.
        m.consume(1, 48000)
        assertEquals(2, m.peek(dst, 2, 2))
        assertEquals(listOf(2f, -2f, 3f, -3f), dst.toList())
        assertEquals(2, m.pending())
    }

    @Test
    fun `past what is queued the tail is silence`() {
        val m = mix()
        m.offer(floats(0.5f, 0.5f))
        val dst = FloatArray(6) { 9f }
        assertEquals(1, m.peek(dst, 3, 2))
        assertEquals(listOf(0.5f, 0.5f, 0f, 0f, 0f, 0f), dst.toList())
    }

    @Test
    fun `a full mix pushes back on the tail`() {
        val m = UsbCrossfadeMix(capacitySeconds = 0.01f).apply {
            setConsumer(48000, 2)
            open(2)
            playing = true
        }
        // Capacity floors at 1024 frames; offer 2000.
        val big = ByteBuffer.allocateDirect(2000 * 8).order(ByteOrder.nativeOrder())
        repeat(4000) { big.putFloat(0.1f) }
        big.flip()
        assertEquals(1024, m.offer(big))
        assertEquals(976 * 8, big.remaining())
        assertEquals(0, m.offer(big))
        m.consume(100, 48000)
        assertEquals(100, m.offer(big))
    }

    @Test
    fun `a paused tail buffers but is not heard`() {
        val m = mix()
        m.playing = false
        m.offer(floats(1f, 1f))
        val dst = FloatArray(2)
        assertEquals(0, m.peek(dst, 1, 2))
        assertEquals(1, m.pending())
        m.playing = true
        assertEquals(1, m.peek(dst, 1, 2))
    }

    @Test
    fun `the tail's clock is what the DAC took, in heard time`() {
        val m = mix()
        val second = ByteBuffer.allocateDirect(24000 * 8).order(ByteOrder.nativeOrder())
        repeat(48000) { second.putFloat(0f) }
        second.flip()
        m.offer(second)
        m.consume(12000, 48000)
        assertEquals(250_000L, m.playedUs())
        // After the DAC switches to 96 kHz, a frame is half as long.
        m.consume(12000, 96000)
        assertEquals(375_000L, m.playedUs())
    }

    @Test
    fun `channels the stream lacks are dropped, ones the tail lacks are silent`() {
        val m = mix()
        m.offer(floats(1f, 2f))
        val mono = FloatArray(1)
        m.peek(mono, 1, 1)
        assertEquals(1f, mono[0], 0f)
        val wide = FloatArray(4) { 9f }
        m.peek(wide, 1, 4)
        assertEquals(listOf(1f, 2f, 0f, 0f), wide.toList())
    }

    @Test
    fun `closing drops what was left, so it never reaches the next song`() {
        val m = mix()
        m.offer(floats(1f, 1f))
        m.close()
        assertFalse(m.isOpen)
        assertEquals(0, m.peek(FloatArray(2), 1, 2))
        m.open(2)
        assertTrue(m.isOpen)
        assertEquals(0, m.pending())
    }
}
