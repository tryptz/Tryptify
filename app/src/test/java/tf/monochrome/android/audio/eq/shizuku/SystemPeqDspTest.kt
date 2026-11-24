package tf.monochrome.android.audio.eq.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Shizuku mode's equalizer: other apps' audio through the listener's
 * real parametric correction, an ear at a time, on the 16-bit stereo a
 * loopback capture hands over.
 */
class SystemPeqDspTest {

    private val rate = 48000

    private fun peak(freq: Float, gain: Float, q: Float = 1.4f) =
        EqBand(id = freq.toInt(), type = FilterType.PEAKING, freq = freq, gain = gain, q = q, enabled = true)

    /** A stereo tone, [amp] of full scale, interleaved. */
    private fun tone(hz: Float, n: Int, amp: Float = 0.25f) = ShortArray(2 * n) {
        (amp * 32767f * sin(2.0 * PI * hz * (it / 2) / rate)).toInt().toShort()
    }

    private fun rmsDb(pcm: ShortArray, channel: Int, from: Int): Double {
        var e = 0.0
        var n = 0
        var i = from
        while (2 * i + channel < pcm.size) {
            val v = pcm[2 * i + channel] / 32768.0
            e += v * v
            n++
            i++
        }
        return 10.0 * log10(e / n + 1e-20)
    }

    private fun run(dsp: SystemPeqDsp, pcm: ShortArray, block: Int = 480): ShortArray {
        val out = pcm.copyOf()
        val frames = out.size / 2
        var at = 0
        val piece = ShortArray(2 * block)
        while (at < frames) {
            val n = minOf(block, frames - at)
            System.arraycopy(out, 2 * at, piece, 0, 2 * n)
            dsp.process(piece, n)
            System.arraycopy(piece, 0, out, 2 * at, 2 * n)
            at += n
        }
        return out
    }

    @Test
    fun `with no curve the samples pass exactly as they came`() {
        val dsp = SystemPeqDsp(rate)
        val random = Random(1)
        val song = ShortArray(2 * 4800) { random.nextInt(-32768, 32768).toShort() }
        assertArrayEquals(song, run(dsp, song))
        assertFalse(dsp.shaping)
        // A band that is off, one at 0 dB, and no preamp: still nothing.
        dsp.setCurve(
            SystemPeqDsp.packBands(listOf(peak(1000f, 0f), peak(200f, 5f).copy(enabled = false))),
            FloatArray(0), 0f,
        )
        assertFalse(dsp.shaping)
        assertArrayEquals(song, run(dsp, song))
    }

    @Test
    fun `a band plays at the gain the app's own equalizer gives it, at every frequency tried`() {
        val bands = listOf(peak(3150f, -6f), peak(120f, 4f, q = 0.9f), peak(8000f, 3f, q = 2f))
        for (hz in listOf(60f, 120f, 500f, 1000f, 3150f, 5000f, 8000f, 12000f)) {
            val dsp = SystemPeqDsp(rate)
            dsp.setCurve(SystemPeqDsp.packBands(bands), SystemPeqDsp.packBands(bands), 0f)
            val n = rate
            val input = tone(hz, n)
            val out = run(dsp, input)
            val got = rmsDb(out, 0, n / 2) - rmsDb(input, 0, n / 2)
            val want = bands.sumOf { AutoEqEngine.calculateBiquadResponse(hz, it, rate.toFloat()).toDouble() }
            assertEquals("at $hz Hz", want, got, 0.1)
        }
    }

    @Test
    fun `each ear plays its own curve`() {
        val dsp = SystemPeqDsp(rate)
        dsp.setCurve(
            SystemPeqDsp.packBands(listOf(peak(1000f, -9f))),
            SystemPeqDsp.packBands(listOf(peak(1000f, 3f))),
            0f,
        )
        assertTrue(dsp.shaping)
        val n = rate
        val input = tone(1000f, n)
        val out = run(dsp, input)
        assertEquals(-9.0, rmsDb(out, 0, n / 2) - rmsDb(input, 0, n / 2), 0.1)
        assertEquals(3.0, rmsDb(out, 1, n / 2) - rmsDb(input, 1, n / 2), 0.1)
    }

    @Test
    fun `the preamp turns both ears down, and a shelf is a shelf`() {
        val dsp = SystemPeqDsp(rate)
        val shelf = EqBand(id = 1, type = FilterType.LOWSHELF, freq = 110f, gain = 6f, q = 0.7f, enabled = true)
        dsp.setCurve(SystemPeqDsp.packBands(listOf(shelf)), SystemPeqDsp.packBands(listOf(shelf)), -6f)
        val n = rate
        val low = tone(40f, n)
        val high = tone(4000f, n)
        // The shelf's 6 dB under 110 Hz is taken back by the preamp; above it only the preamp is left.
        assertEquals(0.0, rmsDb(run(dsp, low), 0, n / 2) - rmsDb(low, 0, n / 2), 0.4)
        val dsp2 = SystemPeqDsp(rate).apply { setCurve(SystemPeqDsp.packBands(listOf(shelf)), SystemPeqDsp.packBands(listOf(shelf)), -6f) }
        assertEquals(-6.0, rmsDb(run(dsp2, high), 1, n / 2) - rmsDb(high, 1, n / 2), 0.2)
    }

    @Test
    fun `a boost with no room rounds off under full scale and never wraps`() {
        val dsp = SystemPeqDsp(rate)
        dsp.setCurve(SystemPeqDsp.packBands(listOf(peak(1000f, 12f))), SystemPeqDsp.packBands(listOf(peak(1000f, 12f))), 0f)
        val n = rate / 2
        val loud = tone(1000f, n, amp = 0.9f)
        val out = run(dsp, loud)
        var peak = 0
        var wrapped = false
        for (i in n / 2 until n) {
            val v = out[2 * i].toInt()
            peak = maxOf(peak, abs(v))
            // A wrap is a sample of the other sign where the input is far from zero.
            if (abs(loud[2 * i].toInt()) > 20000 && (v > 0) != (loud[2 * i] > 0)) {
                // The filter shifts the phase a little at its centre: none here, the peak is at 1 kHz.
                wrapped = true
            }
        }
        assertTrue("peak $peak", peak in 31000..32768)
        assertFalse(wrapped)
    }

    @Test
    fun `a quiet tone comes out a quiet tone, not a stepped one`() {
        // Six 16-bit steps high, through a cut: without dither the rounding
        // would follow the tone and add harmonics; with it the error is noise.
        val dsp = SystemPeqDsp(rate)
        dsp.setCurve(SystemPeqDsp.packBands(listOf(peak(1000f, -3f))), SystemPeqDsp.packBands(listOf(peak(1000f, -3f))), 0f)
        val n = rate * 2
        val quiet = ShortArray(2 * n) { (6.0 * sin(2.0 * PI * 1000.0 * (it / 2) / rate)).toInt().toShort() }
        val out = run(dsp, quiet)
        fun at(hz: Double): Double {
            var s = 0.0
            var c = 0.0
            for (i in n / 2 until n) {
                val w = 2.0 * PI * hz * i / rate
                s += out[2 * i] * sin(w)
                c += out[2 * i] * kotlin.math.cos(w)
            }
            return 2.0 * sqrt(s * s + c * c) / (n / 2)
        }
        val fundamental = at(1000.0)
        assertTrue("the tone is there: $fundamental", fundamental > 2.5)
        assertTrue("third harmonic ${at(3000.0)}", at(3000.0) < 0.05 * fundamental)
    }

    @Test
    fun `a new curve with as many bands carries on, a turn of a knob makes no jump`() {
        val dsp = SystemPeqDsp(rate)
        dsp.setCurve(SystemPeqDsp.packBands(listOf(peak(1000f, -6f))), SystemPeqDsp.packBands(listOf(peak(1000f, -6f))), 0f)
        val n = 4800
        val input = tone(1000f, 2 * n)
        val first = input.copyOfRange(0, 2 * n)
        dsp.process(first, n)
        dsp.setCurve(SystemPeqDsp.packBands(listOf(peak(1000f, -5.5f))), SystemPeqDsp.packBands(listOf(peak(1000f, -5.5f))), 0f)
        val second = input.copyOfRange(2 * n, 4 * n)
        dsp.process(second, n)
        // The step across the change is no larger than the tone's own between samples.
        val before = first[2 * (n - 1)].toInt()
        val after = second[0].toInt()
        val own = (0.25 * 32767 * 2.0 * PI * 1000.0 / rate).toInt()
        assertTrue("step ${abs(after - before)} against the tone's own $own", abs(after - before) < 2 * own)
    }

    @Test
    fun `what cannot be a filter is left out, whatever was sent`() {
        val dsp = SystemPeqDsp(rate)
        val nonsense = floatArrayOf(
            0f, Float.NaN, 3f, 1f,          // no frequency
            0f, 1000f, Float.POSITIVE_INFINITY, 1f,
            0f, 30000f, 3f, 1f,             // past Nyquist
            0f, 1000f, 3f, 0f,              // no Q
            0f, -50f, 3f, 1f,
            7f, 1000f,                      // cut short
        )
        dsp.setCurve(nonsense, nonsense, Float.NaN)
        assertFalse(dsp.shaping)
        val song = tone(1000f, 4800)
        assertArrayEquals(song, run(dsp, song))
        // And more bands than it takes are dropped past the limit, not an error.
        val many = SystemPeqDsp.packBands(List(200) { peak(100f + it * 50f, 1f) })
        assertEquals(SystemPeqDsp.MAX_BANDS * SystemPeqDsp.FIELDS, many.size)
    }
}
