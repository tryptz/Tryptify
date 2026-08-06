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
    fun `matched peaking lands on the analog prototype across the top octave`() {
        val fs = 48000.0
        fun analogDb(f: Double, f0: Double, q: Double, gDb: Double): Double {
            val a = Math.pow(10.0, gDb / 40.0)
            val x = f / f0
            val re = 1.0 - x * x
            val num = sqrt(re * re + (x * a / q) * (x * a / q))
            val den = sqrt(re * re + (x / (a * q)) * (x / (a * q)))
            return 20.0 * kotlin.math.log10(num / den)
        }
        fun matchedDb(probe: Double, f0: Double, q: Double, gDb: Double): Double {
            val n = 32768
            val bq = EqBiquad()
            bq.configure(EqBiquadType.PEAKING, fs, f0, q, gDb, matched = true)
            val x = FloatArray(n) { sin(2.0 * PI * probe * it / fs).toFloat() }
            bq.processBlock(x, n)
            return 20.0 * kotlin.math.log10(goertzel(x, n / 2, n, probe, fs))
        }
        // Boost: +12 dB at 18 kHz, probed across the audible top octave.
        for (probe in doubleArrayOf(10000.0, 15000.0, 17000.0, 20000.0)) {
            val err = abs(matchedDb(probe, 18000.0, 1.0, 12.0) - analogDb(probe, 18000.0, 1.0, 12.0))
            assertTrue("probe $probe err $err", err < 0.5)
        }
        // Center gain is exact, boost and cut.
        assertEquals(12.0, matchedDb(18000.0, 18000.0, 1.0, 12.0), 0.05)
        assertEquals(-8.0, matchedDb(15000.0, 15000.0, 2.0, -8.0), 0.05)
        // Low-frequency bands stay numerically sane and match RBJ territory.
        assertEquals(6.0, matchedDb(100.0, 100.0, 1.0, 6.0), 0.1)
    }

    @Test
    fun `tournament shelves beat or match RBJ against the analog prototype`() {
        val fs = 48000.0
        fun analogShelfDb(f: Double, f0: Double, q: Double, gDb: Double, high: Boolean): Double {
            val a = Math.pow(10.0, gDb / 40.0)
            val x = f / f0
            val im = sqrt(a) / q * x
            val num: Double; val den: Double
            if (high) {
                num = kotlin.math.hypot(1.0 - a * x * x, im)
                den = kotlin.math.hypot(a - x * x, im)
            } else {
                num = kotlin.math.hypot(a - x * x, im)
                den = kotlin.math.hypot(1.0 - a * x * x, im)
            }
            return 20.0 * kotlin.math.log10(a * num / den)
        }
        fun digitalDb(c: DoubleArray, f: Double): Double {
            val w = 2.0 * PI * f / fs
            val nr = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val ni = c[1] * sin(w) + c[2] * sin(2 * w)
            val dr = 1.0 + c[3] * cos(w) + c[4] * cos(2 * w)
            val di = c[3] * sin(w) + c[4] * sin(2 * w)
            return 10.0 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        data class Case(val f0: Double, val q: Double, val g: Double, val high: Boolean)
        val cases = listOf(
            Case(10000.0, 0.707, 12.0, true),   // RBJ errs ~1.0-1.3 dB here
            Case(10000.0, 0.707, -12.0, true),
            Case(18000.0, 0.707, 8.0, true),    // extreme corner: RBJ ~2.3 dB
            Case(14000.0, 1.0, 10.0, true),
            Case(105.0, 0.707, 4.0, false),     // classic AutoEQ low shelf
            Case(8000.0, 0.707, -8.0, false),
        )
        val probes = DoubleArray(12) { 40.0 * Math.pow(fs * 0.475 / 40.0, it / 11.0) }
        for (case in cases) {
            val c = matchedShelfCoefficients(fs, case.f0, case.q, case.g, case.high)
            assertTrue("coefficients for $case", c != null)
            val worst = probes.maxOf {
                abs(digitalDb(c!!, it) - analogShelfDb(it, case.f0, case.q, case.g, case.high))
            }
            assertTrue("$case worst err $worst < 1.0 dB", worst < 1.0)
        }
        // The tournament must never lose to plain RBJ: the 10 kHz +12 shelf is
        // a case where RBJ alone errs ~1 dB — matched must do better.
        val c = matchedShelfCoefficients(fs, 10000.0, 0.707, 12.0, true)!!
        val worst = probes.maxOf { abs(digitalDb(c, it) - analogShelfDb(it, 10000.0, 0.707, 12.0, true)) }
        assertTrue("10k shelf worst err $worst < 0.5 dB", worst < 0.5)
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
}
