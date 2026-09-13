package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Float PCM → the integer a USB Audio Type I subslot carries.
 *
 * Worth pinning because every way to get this wrong still plays, so none of it
 * shows up as a crash or a silent stream: a bad scale is distortion at full
 * level, a missing left-shift is 48 dB of attenuation on a 4-byte DAC, and a
 * sign error is noise. The only way to see it is to look at the numbers.
 */
class FloatToSubslotSampleTest {

    private val fullScale24 = 8_388_607 // 2^23 - 1

    @Test
    fun `full scale maps to the top of the 24-bit range`() {
        assertEquals(fullScale24, floatToSubslotSample(1f, 3))
        assertEquals(-fullScale24, floatToSubslotSample(-1f, 3))
    }

    @Test
    fun `silence is zero at every width`() {
        assertEquals(0, floatToSubslotSample(0f, 2))
        assertEquals(0, floatToSubslotSample(0f, 3))
        assertEquals(0, floatToSubslotSample(0f, 4))
    }

    @Test
    fun `overs are clamped rather than wrapped`() {
        // Wrapping would turn a loud passage into full-scale noise of the
        // opposite sign, which is the single worst failure here.
        assertEquals(fullScale24, floatToSubslotSample(1.5f, 3))
        assertEquals(-fullScale24, floatToSubslotSample(-1.5f, 3))
        assertEquals(fullScale24, floatToSubslotSample(Float.MAX_VALUE, 3))
        assertEquals(-fullScale24, floatToSubslotSample(-Float.MAX_VALUE, 3))
    }

    @Test
    fun `NaN becomes silence, not garbage`() {
        assertEquals(0, floatToSubslotSample(Float.NaN, 3))
    }

    @Test
    fun `a four-byte subslot is left-justified`() {
        // bSubslotSize=4 with bBitResolution=24 carries the sample in the top
        // three bytes, low byte zero — the S32_LE mapping snd-usb-audio uses.
        assertEquals(fullScale24 shl 8, floatToSubslotSample(1f, 4))
        assertEquals(0, floatToSubslotSample(1f, 4) and 0xFF)
    }

    @Test
    fun `a two-byte subslot keeps the top 16 bits`() {
        assertEquals(fullScale24 shr 8, floatToSubslotSample(1f, 2))
        // 0x7FFF: still full scale for a 16-bit stream.
        assertEquals(32_767, floatToSubslotSample(1f, 2))
    }

    @Test
    fun `widths are pure shifts of each other`() {
        // The scale happens once, in 24-bit; width only decides which bytes
        // are emitted. If these ever diverge, one of the three paths is
        // applying its own gain.
        for (v in listOf(-0.75f, -0.25f, 0.1f, 0.5f, 0.99f)) {
            val at24 = floatToSubslotSample(v, 3)
            assertEquals(at24 shl 8, floatToSubslotSample(v, 4))
            assertEquals(at24 shr 8, floatToSubslotSample(v, 2))
        }
    }

    @Test
    fun `half scale is half of full scale`() {
        // Guards the scale factor itself: an off-by-one-bit error here is a
        // 6 dB level change that is easy to mistake for a volume setting.
        assertEquals(fullScale24 / 2, floatToSubslotSample(0.5f, 3))
    }
}
