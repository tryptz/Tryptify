package tf.monochrome.android.data.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SilentWavTest {

    @Test
    fun `payload is whole 44_1k stereo 16-bit frames for the duration`() {
        // One second = 44,100 frames x 4 bytes.
        assertEquals(176_400L, SilentWav.dataSize(1_000))
        assertEquals(0L, SilentWav.dataSize(0))
        assertEquals(0L, SilentWav.dataSize(-5))
        assertEquals(0L, SilentWav.dataSize(1_000) % 4)
        assertEquals(SilentWav.HEADER_SIZE + 176_400L, SilentWav.totalSize(1_000))
    }

    @Test
    fun `absurd durations are clamped under the 4 GiB RIFF limit`() {
        val size = SilentWav.totalSize(Long.MAX_VALUE)
        assertTrue(size < 0xFFFF_FFFFL)
    }

    @Test
    fun `header is a canonical PCM RIFF-WAVE header`() {
        val dataSize = SilentWav.dataSize(2_500)
        val header = SilentWav.header(dataSize)
        assertEquals(SilentWav.HEADER_SIZE, header.size)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals((36 + dataSize).toInt(), buf.getInt(4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, buf.getInt(16))
        assertEquals(1.toShort(), buf.getShort(20))             // PCM
        assertEquals(2.toShort(), buf.getShort(22))             // stereo
        assertEquals(44_100, buf.getInt(24))
        assertEquals(176_400, buf.getInt(28))                   // byte rate
        assertEquals(4.toShort(), buf.getShort(32))             // block align
        assertEquals(16.toShort(), buf.getShort(34))            // bits per sample
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(dataSize.toInt(), buf.getInt(40))
    }

    @Test
    fun `fill serves the header then silence, from any offset`() {
        val header = SilentWav.header(SilentWav.dataSize(1_000))

        // Straddling the header/payload boundary, into a dirty buffer.
        val target = ByteArray(40) { 0x7F }
        SilentWav.fill(header, position = 30, target = target, offset = 5, length = 30)
        assertArrayEquals(header.copyOfRange(30, 44), target.copyOfRange(5, 19))
        assertTrue(target.copyOfRange(19, 35).all { it == 0.toByte() })
        // Bytes outside [offset, offset + length) are untouched.
        assertTrue(target.copyOfRange(0, 5).all { it == 0x7F.toByte() })
        assertTrue(target.copyOfRange(35, 40).all { it == 0x7F.toByte() })

        // Entirely inside the payload: all zero.
        val deep = ByteArray(16) { 1 }
        SilentWav.fill(header, position = 10_000, target = deep, offset = 0, length = 16)
        assertTrue(deep.all { it == 0.toByte() })

        // Entirely inside the header.
        val head = ByteArray(8)
        SilentWav.fill(header, position = 0, target = head, offset = 0, length = 8)
        assertArrayEquals(header.copyOfRange(0, 8), head)
    }
}
