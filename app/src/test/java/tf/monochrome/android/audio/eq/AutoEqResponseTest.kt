package tf.monochrome.android.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * End-to-end response tests for the AutoEQ audio path. The correction chain
 * uses decramped (matched) biquads, so the requirement throughout is: the
 * processed audio lands on the ANALOG prototype the correction curves are
 * designed against — including the top octave, where plain bilinear/RBJ
 * designs cramp by several dB at 44.1/48 kHz.
 */
class AutoEqResponseTest {

    private val sampleRate = 48000

    /** Push a stereo float sine through the processor; returns steady-state L amplitude. */
    private fun measure(proc: AutoEqProcessor, freq: Double, frames: Int = 32768): Double {
        val out = FloatArray(frames)
        var written = 0
        var phaseIdx = 0
        val chunk = 1024
        val buf = ByteBuffer.allocateDirect(chunk * 8).order(ByteOrder.nativeOrder())
        while (written < frames) {
            buf.clear()
            for (i in 0 until chunk) {
                val s = sin(2.0 * PI * freq * (phaseIdx + i) / sampleRate).toFloat()
                buf.putFloat(s)
                buf.putFloat(s)
            }
            phaseIdx += chunk
            buf.flip()
            proc.queueInput(buf)
            val o = proc.output
            while (o.remaining() >= 8 && written < frames) {
                out[written++] = o.getFloat()
                o.getFloat()  // skip R
            }
        }
        val start = frames / 2
        val w = 2.0 * PI * freq / sampleRate
        val c = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in start until frames) {
            val s0 = out[i] + c * s1 - s2
            s2 = s1; s1 = s0
        }
        val n = frames - start
        return sqrt((s1 * s1 + s2 * s2 - c * s1 * s2).coerceAtLeast(0.0)) / (n / 2.0)
    }

    private fun makeProcessor(bands: List<EqBand>): AutoEqProcessor {
        val proc = AutoEqProcessor()
        proc.configure(AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT))
        proc.flush()
        proc.applyBands(bands, preamp = 0f, enabled = true)
        return proc
    }

    /** Analog RBJ peaking prototype magnitude in dB at [freq]. */
    private fun analogPeakingDb(freq: Double, f0: Double, q: Double, gainDb: Double): Double {
        val a = Math.pow(10.0, gainDb / 40.0)
        val x = freq / f0
        val re = 1.0 - x * x
        val num = sqrt(re * re + (x * a / q) * (x * a / q))
        val den = sqrt(re * re + (x / (a * q)) * (x / (a * q)))
        return 20.0 * log10(num / den)
    }

    /** Analog shelf prototype magnitude in dB at [freq]. */
    private fun analogShelfDb(freq: Double, f0: Double, q: Double, gainDb: Double, high: Boolean): Double {
        val a = Math.pow(10.0, gainDb / 40.0)
        val x = freq / f0
        val im = sqrt(a) / q * x
        val num: Double
        val den: Double
        if (high) {
            num = hypot(1.0 - a * x * x, im); den = hypot(a - x * x, im)
        } else {
            num = hypot(a - x * x, im); den = hypot(1.0 - a * x * x, im)
        }
        return 20.0 * log10(a * num / den)
    }

    @Test
    fun `near-flat chain is transparent`() {
        val flat = listOf(EqBand(0, FilterType.PEAKING, 1000f, 0.01f, 1f, true))
        assertEquals(1.0, measure(makeProcessor(flat), 1000.0), 0.01)
    }

    @Test
    fun `top-octave peaking lands on the analog curve`() {
        // +12 dB peak at 18 kHz, Q 1 — where plain RBJ cramps by >4 dB.
        val band = listOf(EqBand(0, FilterType.PEAKING, 18000f, 12f, 1f, true))
        val probe = 15000.0
        val target = analogPeakingDb(probe, 18000.0, 1.0, 12.0)
        val db = 20.0 * log10(measure(makeProcessor(band), probe))
        assertTrue("within 1 dB of analog target (was ${abs(db - target)})",
            abs(db - target) < 1.0)
    }

    @Test
    fun `high shelf lands on the analog curve through the transition`() {
        // +12 dB high shelf at 10 kHz — RBJ errs ~1.2 dB mid-transition.
        val band = listOf(EqBand(0, FilterType.HIGHSHELF, 10000f, 12f, 0.707f, true))
        for (probe in doubleArrayOf(8000.0, 14000.0, 18000.0)) {
            val target = analogShelfDb(probe, 10000.0, 0.707, 12.0, high = true)
            val db = 20.0 * log10(measure(makeProcessor(band), probe))
            assertTrue("probe $probe within 1 dB (was ${abs(db - target)})",
                abs(db - target) < 1.0)
        }
    }

    @Test
    fun `mid-band response is exact`() {
        val band = listOf(EqBand(0, FilterType.PEAKING, 1000f, 6f, 1.4f, true))
        assertEquals(6.0, 20.0 * log10(measure(makeProcessor(band), 1000.0)), 0.15)
    }

    @Test
    fun `flat chain is a hard bypass - output is bit-identical to input`() {
        // Zero-gain bands build an empty filter chain; with unity preamp the
        // whole EQ stage must be structurally out of the path.
        val proc = makeProcessor(listOf(EqBand(0, FilterType.PEAKING, 1000f, 0f, 1f, true)))
        val n = 4096
        val buf = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
        val input = FloatArray(n)
        for (i in 0 until n) {
            val s = sin(2.0 * PI * 997.0 * i / sampleRate).toFloat()
            input[i] = s
            buf.putFloat(s); buf.putFloat(s)
        }
        buf.flip()
        proc.queueInput(buf)
        val o = proc.output
        var i = 0
        while (o.remaining() >= 8) {
            val l = o.getFloat(); o.getFloat()
            assertEquals("sample $i untouched", input[i], l, 0f)
            i++
        }
        assertTrue(i > 0)
    }
}
