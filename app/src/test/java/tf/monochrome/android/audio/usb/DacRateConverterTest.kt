package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The fallback for a DAC whose clock cannot run the song's rate: which rate
 * it is run at instead, and that the conversion keeps the song's length and
 * level and stays out of the way otherwise.
 */
class DacRateConverterTest {

    private fun discrete(vararg hz: Int) = hz.map { ClockRateRange(0, it, it, 0) }

    @Test
    fun `a 48 kHz family DAC plays CD rate at 48`() {
        // The Nothing Ear (1): 48 and 96 kHz only.
        assertEquals(48_000, DacRateConverter.pickRate(44_100, discrete(48_000, 96_000)))
    }

    @Test
    fun `the lowest rate at or above the song's wins`() {
        assertEquals(96_000, DacRateConverter.pickRate(88_200, discrete(48_000, 96_000, 192_000)))
    }

    @Test
    fun `a song above every rate goes down to the highest`() {
        assertEquals(96_000, DacRateConverter.pickRate(176_400, discrete(48_000, 96_000)))
    }

    @Test
    fun `a rate the DAC has is not a refusal`() {
        // It failed for another reason; another rate would not start either.
        assertNull(DacRateConverter.pickRate(48_000, discrete(48_000, 96_000)))
        assertNull(DacRateConverter.pickRate(44_100, listOf(ClockRateRange(1, 8_000, 192_000, 1))))
    }

    @Test
    fun `nothing advertised picks nothing`() {
        assertNull(DacRateConverter.pickRate(44_100, emptyList()))
    }

    @Test
    fun `a continuous range is searched at its own resolution`() {
        // 48 to 192 kHz in steps of 48: 44.1 is below it, 88.2 between steps.
        val range = listOf(ClockRateRange(1, 48_000, 192_000, 48_000))
        assertEquals(48_000, DacRateConverter.pickRate(44_100, range))
        assertEquals(96_000, DacRateConverter.pickRate(88_200, range))
    }

    @Test
    fun `only 16-bit and float are converted`() {
        assertTrue(DacRateConverter.supports(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)))
        assertTrue(DacRateConverter.supports(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)))
        assertFalse(DacRateConverter.supports(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT)))
        assertFalse(DacRateConverter.supports(AudioFormat(44_100, 17, C.ENCODING_PCM_FLOAT)))
    }

    @Test
    fun `off until a target is set, and at the song's own rate`() {
        val c = DacRateConverter()
        val fmt = AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioFormat.NOT_SET, c.configure(fmt))
        assertFalse(c.isActive)
        c.targetRate = 44_100
        assertEquals(AudioFormat.NOT_SET, c.configure(fmt))
        assertFalse(c.isActive)
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `integer PCM wider than 16 bits is declined`() {
        val c = DacRateConverter()
        c.targetRate = 48_000
        c.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
    }

    @Test
    fun `the chain reports the DAC's rate, and passes through when off`() {
        val c = DacRateConverter()
        val chain = AudioProcessorChain(listOf(c), log = {})
        val fmt = AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(fmt, chain.configure(fmt))
        assertFalse(chain.anyActive())
        c.targetRate = 48_000
        assertEquals(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT), chain.configure(fmt))
        assertTrue(chain.anyActive())
    }

    @Test
    fun `a second of the song is a second at the DAC, at the same level`() {
        val out = convert(44_100, 48_000, 1_000.0, 44_100)
        // A second in is a second out, give or take the kernel's delay.
        assertEquals(48_000.0, out.size.toDouble(), 64.0)
        // A 1 kHz tone at -6 dBFS keeps its level.
        val settled = out.copyOfRange(out.size / 4, out.size)
        val rms = sqrt(settled.sumOf { it.toDouble() * it } / settled.size)
        assertEquals(0.5 / sqrt(2.0), rms, 0.005)
    }

    @Test
    fun `silence stays silence`() {
        val out = convert(44_100, 48_000, 0.0, 44_100)
        assertTrue(out.all { abs(it) < 1e-6f })
    }

    @Test
    fun `16-bit converts too`() {
        val c = DacRateConverter()
        c.targetRate = 48_000
        assertEquals(
            AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT),
            c.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)),
        )
        c.flush()
        val frames = 4_410
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { buf.putShort(0) }
        buf.flip()
        c.queueInput(buf)
        val produced = c.output.remaining() / 4
        assertEquals(frames * 48_000.0 / 44_100, produced.toDouble(), 64.0)
    }

    /** Runs a stereo float sine at -6 dBFS through; returns the left channel. */
    private fun convert(from: Int, to: Int, freq: Double, inFrames: Int): FloatArray {
        val c = DacRateConverter()
        c.targetRate = to
        c.configure(AudioFormat(from, 2, C.ENCODING_PCM_FLOAT))
        c.flush()
        val chunk = 1024
        val out = ArrayList<Float>(inFrames * 2)
        val buf = ByteBuffer.allocateDirect(chunk * 8).order(ByteOrder.nativeOrder())
        var n = 0
        while (n < inFrames) {
            val count = minOf(chunk, inFrames - n)
            buf.clear()
            for (i in 0 until count) {
                val s = (0.5 * sin(2.0 * PI * freq * (n + i) / from)).toFloat()
                buf.putFloat(s); buf.putFloat(s)
            }
            buf.flip()
            c.queueInput(buf)
            val o = c.output
            while (o.remaining() >= 8) { out.add(o.getFloat()); o.getFloat() }
            n += count
        }
        return out.toFloatArray()
    }
}
