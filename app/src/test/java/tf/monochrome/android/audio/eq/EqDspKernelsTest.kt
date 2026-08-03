package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM tests for the EQ DSP kernels — no Android/media3 on the classpath,
 * so coefficient work iterates in milliseconds.
 */
class EqDspKernelsTest {

    private fun goertzel(x: FloatArray, start: Int, end: Int, freq: Double, fs: Double): Double {
        val w = 2.0 * PI * freq / fs
        val c = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in start until end) {
            val s0 = x[i] + c * s1 - s2
            s2 = s1; s1 = s0
        }
        val n = end - start
        return sqrt((s1 * s1 + s2 * s2 - c * s1 * s2).coerceAtLeast(0.0)) / (n / 2.0)
    }

    @Test
    fun `peaking biquad hits its gain at center`() {
        val n = 32768
        val fs = 48000.0
        val bq = EqBiquad()
        bq.configure(EqBiquadType.PEAKING, fs, 1000.0, 1.4, 6.0)
        val x = FloatArray(n) { sin(2.0 * PI * 1000.0 * it / fs).toFloat() }
        bq.processBlock(x, n)
        val gainDb = 20.0 * kotlin.math.log10(goertzel(x, n / 2, n, 1000.0, fs))
        assertEquals(6.0, gainDb, 0.1)
    }

    @Test
    fun `degenerate configure falls back to passthrough`() {
        val bq = EqBiquad()
        bq.configure(EqBiquadType.PEAKING, 48000.0, Double.NaN, 1.0, 6.0)
        val x = floatArrayOf(0.25f, -0.5f, 1f)
        bq.processBlock(x, 3)
        assertEquals(0.25f, x[0], 1e-6f)
        assertEquals(-0.5f, x[1], 1e-6f)
    }

    @Test
    fun `resampler round-trip is transparent in the audio band`() {
        val n = 16384
        val fs = 48000.0
        for (factor in intArrayOf(2, 4)) {
            val rs = ChannelResampler()
            rs.prepare(fs, factor)
            val input = FloatArray(n) { sin(2.0 * PI * 1000.0 * it / fs).toFloat() }
            val hi = FloatArray(n * factor)
            val out = FloatArray(n)
            rs.upsample(input, hi, n)
            rs.downsample(hi, out, n)
            val amp = goertzel(out, n / 2, n, 1000.0, fs)
            assertEquals("factor $factor", 1.0, amp, 0.01)
        }
    }

    @Test
    fun `resampler images are suppressed`() {
        // Zero-stuffing a 1 kHz sine at 4x creates images at 23, 25, 47... kHz
        // of the 192 kHz stream; the anti-image filter must crush them.
        val n = 16384
        val fs = 48000.0
        val factor = 4
        val rs = ChannelResampler()
        rs.prepare(fs, factor)
        val input = FloatArray(n) { sin(2.0 * PI * 1000.0 * it / fs).toFloat() }
        val hi = FloatArray(n * factor)
        rs.upsample(input, hi, n)
        val osRate = fs * factor
        val image = goertzel(hi, n * factor / 2, n * factor, 47000.0, osRate)
        val fund = goertzel(hi, n * factor / 2, n * factor, 1000.0, osRate)
        assertTrue("fundamental present (${fund})", abs(fund - 1.0) < 0.05)
        assertTrue("47 kHz image below -40 dB (was ${20 * kotlin.math.log10(image)})",
            image < 0.01)
    }
}
