package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Nothing reaches the DAC louder than its level, whatever the samples hold.
 *
 * The packers clamp after the gain, so a value far past full scale (16-bit
 * samples read as float, a filter blowing up) still came out at full scale:
 * a burst at a song change, at any volume. Bounded before the gain, the DAC
 * gets at most [SOURCE_HEADROOM] (+12 dB) over the level set.
 */
class SafeSampleTest {

    private val garbage = listOf(1e30f, -1e30f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 3.4e38f)

    @Test
    fun `garbage in a crossfade tail used to reach the DAC at full scale`() {
        // -14 dB, the level in the log: a tail of 1e30 rounded to the top.
        val unguarded = int16ToSubslotSample(0, 0.2f, 1e30f * 0.2f, 3)
        assertEquals(8_388_607, unguarded)
    }

    @Test
    fun `a tail is held to the headroom over its gain whatever it holds`() {
        val gain = 0.2f
        for (t in garbage) {
            val out = int16ToSubslotSample(0, gain, safeSourceSample(t) * gain, 3)
            assertTrue("$t gave $out", abs(out) <= SOURCE_HEADROOM * gain * 8_388_607f + 1)
            val outFloat = floatToSubslotSample(0f * gain + safeSourceSample(t) * gain, 3)
            assertTrue("$t gave $outFloat", abs(outFloat) <= SOURCE_HEADROOM * gain * 8_388_607f + 1)
        }
        assertEquals(0f, safeSourceSample(Float.NaN), 0f)
    }

    @Test
    fun `a float stream keeps 12 dB of room and no more`() {
        val gain = 0.2f
        for (x in garbage) {
            val out = floatToSubslotSample(safeSourceSample(x) * gain, 3)
            assertTrue("$x gave $out", abs(out) <= SOURCE_HEADROOM * gain * 8_388_607f + 1)
        }
        // Real audio, an EQ boost included, is untouched.
        for (x in listOf(0f, 0.5f, -1f, 1.4f, -1.9f, 3.5f, -3.9f)) assertEquals(x, safeSourceSample(x), 0f)
    }

    @Test
    fun `an EQ boosted tail is not clipped at full scale before its level`() {
        // The tail runs its own copy of the DSP chain. A +3.5 dB peak at half
        // volume fits the DAC; held at full scale first, it came out flattened
        // at 0.5 through every crossfade.
        val tailGain = 0.5f
        val boosted = 1.5f
        assertEquals(
            floatToSubslotSample(0.75f, 3),
            floatToSubslotSample(safeSourceSample(boosted) * tailGain, 3),
        )
        assertEquals(
            Math.round(0.75f * 8_388_607f),
            int16ToSubslotSample(0, 1f, safeSourceSample(boosted) * tailGain, 3),
        )
        for (t in listOf(0f, 0.3f, -0.99f, 1f, 1.4f, -1.9f)) assertEquals(t, safeSourceSample(t), 0f)
    }

    @Test
    fun `the headroom is 12 dB`() {
        assertEquals(12.04f, (20 * kotlin.math.log10(SOURCE_HEADROOM.toDouble())).toFloat(), 0.01f)
    }

    @Test
    fun `a 10 dB boost at -12 dB plays clean`() {
        // Held at +6 dB, its peaks were flattened before the level was applied.
        val boosted = 3.16f // +10 dB
        val level = 0.25f // -12 dB
        assertEquals(
            floatToSubslotSample(boosted * level, 3),
            floatToSubslotSample(safeSourceSample(boosted) * level, 3),
        )
        assertTrue(floatToSubslotSample(safeSourceSample(boosted) * level, 3) < 8_388_607)
    }

    @Test
    fun `at the starting level a burst never reaches full scale`() {
        // Exclusive mode starts at -24 dB: whatever the stream holds, the DAC
        // gets about -12 dB of full scale there at most, a quarter of it.
        val startingLevel = 0.0631f // -24 dB
        val ceiling = SOURCE_HEADROOM * startingLevel
        assertTrue("ceiling $ceiling", ceiling < 0.26f) // -11.7 dB: nowhere near full scale
        for (x in garbage + listOf(5f, -100f, 32_767f)) {
            val out = abs(floatToSubslotSample(safeSourceSample(x) * startingLevel, 3))
            assertTrue("$x gave $out", out <= ceiling * 8_388_607f + 1)
        }
    }

    @Test
    fun `what is not audio is told apart`() {
        assertTrue(looksLikeAudio(0.9f, SOURCE_HEADROOM))
        assertTrue(looksLikeAudio(1.6f, SOURCE_HEADROOM))
        assertTrue(looksLikeAudio(-1.9f, SOURCE_HEADROOM))
        for (x in garbage) assertFalse("$x", looksLikeAudio(x, SOURCE_HEADROOM))
    }
}
