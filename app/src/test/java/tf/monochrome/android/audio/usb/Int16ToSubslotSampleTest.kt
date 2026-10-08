package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A 16-bit sample → the integer a 24-bit USB subslot carries, the way a 16-bit
 * song reaches a DAC with a 24-bit alt.
 *
 * Two properties carry the change. At 0 dB the DAC must get the song bit for
 * bit, zero-padded, or "bit-perfect" is a lie. Below 0 dB the level must be
 * applied with the extra 8 bits, or this is no better than the 16-bit path it
 * replaces, which rounded a -24 dB setting into the song's own bottom 4 bits.
 */
class Int16ToSubslotSampleTest {

    @Test
    fun `unity gain is the sample zero-padded, bit for bit`() {
        for (s in listOf(-32_768, -12_345, -256, -1, 0, 1, 255, 256, 12_345, 32_767)) {
            assertEquals("sample $s", s shl 8, int16ToSubslotSample(s, 1f, 0f, 3))
            assertEquals("sample $s", s shl 16, int16ToSubslotSample(s, 1f, 0f, 4))
        }
    }

    @Test
    fun `every 16-bit value survives unity exactly`() {
        for (s in -32_768..32_767) {
            val out = int16ToSubslotSample(s, 1f, 0f, 3)
            if (out != s shl 8) throw AssertionError("sample $s came out $out")
        }
    }

    @Test
    fun `a four-byte subslot is left-justified`() {
        val out = int16ToSubslotSample(12_345, 1f, 0f, 4)
        assertEquals(0, out and 0xFF)
        assertEquals(int16ToSubslotSample(12_345, 1f, 0f, 3), out shr 8)
    }

    @Test
    fun `minus 24 dB keeps the bits a 16-bit path would round away`() {
        val gain = 10f.pow(-24f / 20f)
        // 1..15 at 16 bits: at -24 dB (x0.063) a 16-bit path rounds every one
        // of them to 0 or 1. At 24 bits each stays distinct and in scale.
        val outs = (1..15).map { int16ToSubslotSample(it, gain, 0f, 3) }
        assertEquals(15, outs.toSet().size)
        for ((i, out) in outs.withIndex()) {
            val exact = (i + 1) * 256 * gain
            assertTrue("sample ${i + 1}: $out vs $exact", abs(out - exact) <= 0.5f)
        }
    }

    @Test
    fun `the level is rounded, not truncated`() {
        // -1 at half gain is -128 exactly; 3 at a third is 256 exactly; and a
        // value just under a step rounds up to it rather than down.
        assertEquals(-128, int16ToSubslotSample(-1, 0.5f, 0f, 3))
        assertEquals((3 * 256 * (1f / 3f)).roundToInt(), int16ToSubslotSample(3, 1f / 3f, 0f, 3))
        assertEquals(256, int16ToSubslotSample(1, 0.999f, 0f, 3))
    }

    @Test
    fun `a crossfade tail mixes in on the float scale and clamps`() {
        // Silence plus a half-scale tail is half of 24-bit full scale.
        assertEquals((0.5f * 8_388_607f).roundToInt(), int16ToSubslotSample(0, 1f, 0.5f, 3))
        // Full scale plus a tail clamps instead of wrapping to the other sign.
        assertEquals(8_388_607, int16ToSubslotSample(32_767, 1f, 0.5f, 3))
        assertEquals(-8_388_608, int16ToSubslotSample(-32_768, 1f, -0.5f, 3))
    }

    @Test
    fun `silence is zero at both widths`() {
        assertEquals(0, int16ToSubslotSample(0, 1f, 0f, 3))
        assertEquals(0, int16ToSubslotSample(0, 0.25f, 0f, 4))
    }
}
