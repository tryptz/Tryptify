package tf.monochrome.android.audio.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Integer PCM → float, for the widths the exclusive chain has to widen.
 *
 * Worth pinning because every way to get this wrong still plays. A dropped
 * sign bit is full-scale noise on negative samples, a byte-order slip is
 * noise everywhere, and a wrong divisor is a quiet or clipping stream — none
 * of which throws, and all of which sound like "the DAC is broken".
 */
@OptIn(UnstableApi::class)
class ToFloatPcmTest {

    private fun le(vararg bytes: Int): ByteBuffer =
        ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            bytes.forEach { put(it.toByte()) }
            flip()
        }

    @Test
    fun `24-bit zero is silence`() {
        assertEquals(0f, readSample(le(0x00, 0x00, 0x00), 3), 0f)
    }

    @Test
    fun `24-bit positive full scale is just under one`() {
        // 0x7FFFFF = 8388607, one step below the 8388608 divisor.
        assertEquals(0.99999988f, readSample(le(0xFF, 0xFF, 0x7F), 3), 1e-7f)
    }

    @Test
    fun `24-bit negative full scale is exactly minus one`() {
        // 0x800000 = -8388608. The divisor is 2^23, so this lands on -1.0
        // exactly rather than overshooting it.
        assertEquals(-1f, readSample(le(0x00, 0x00, 0x80), 3), 0f)
    }

    @Test
    fun `24-bit minus one LSB is a tiny negative, not a huge one`() {
        // 0xFFFFFF is -1 as a 24-bit signed value. Reading the high byte
        // without sign extension would make this +0.9999999 instead.
        assertEquals(-1f / 8_388_608f, readSample(le(0xFF, 0xFF, 0xFF), 3), 0f)
    }

    @Test
    fun `24-bit is little endian`() {
        // 0x000001 = 1, NOT 0x010000 = 65536.
        assertEquals(1f / 8_388_608f, readSample(le(0x01, 0x00, 0x00), 3), 0f)
    }

    @Test
    fun `32-bit spans the same range`() {
        assertEquals(0f, readSample(le(0, 0, 0, 0), 4), 0f)
        assertEquals(-1f, readSample(le(0x00, 0x00, 0x00, 0x80), 4), 0f)
        assertEquals(-1f / 2_147_483_648f, readSample(le(0xFF, 0xFF, 0xFF, 0xFF), 4), 0f)
    }

    @Test
    fun `only the widths that need widening are claimed`() {
        assertEquals(3, bytesPerSample(C.ENCODING_PCM_24BIT))
        assertEquals(4, bytesPerSample(C.ENCODING_PCM_32BIT))
        // Already handled by the chain — must stay untouched, or this
        // processor would needlessly widen every ordinary track.
        assertEquals(0, bytesPerSample(C.ENCODING_PCM_16BIT))
        assertEquals(0, bytesPerSample(C.ENCODING_PCM_FLOAT))
    }

    @Test
    fun `consecutive samples advance by their stride`() {
        val two = le(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0)
        assertEquals(0.5f, readSample(two, 3), 0f)
        assertEquals(-0.5f, readSample(two, 3), 0f)
    }
}
