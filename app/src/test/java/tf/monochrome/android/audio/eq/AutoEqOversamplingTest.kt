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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM tests for AutoEQ oversampling. EQ is linear, so the point of
 * oversampling here is response accuracy: at 48 kHz the bilinear transform
 * cramps peaking filters near Nyquist, and running the biquads at 4x the
 * stream rate should land the measured response much closer to the analog
 * prototype the correction curves are designed against.
 */
class AutoEqOversamplingTest {

    private val sampleRate = 48000

    /** Push [frames] of a stereo float sine through the processor; returns steady-state L amplitude. */
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
        // Goertzel amplitude over the steady tail
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

    private fun makeProcessor(os: Int, bands: List<EqBand>): AutoEqProcessor {
        val proc = AutoEqProcessor()
        proc.configure(AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT))
        proc.flush()
        proc.setOversampling(os)
        proc.applyBands(bands, preamp = 0f, enabled = true)
        return proc
    }

    /** Analog RBJ peaking prototype magnitude in dB at [freq]. */
    private fun analogPeakingDb(freq: Double, f0: Double, q: Double, gainDb: Double): Double {
        val a = Math.pow(10.0, gainDb / 40.0)
        val x = freq / f0
        val re = 1.0 - x * x
        val numIm = x * a / q
        val denIm = x / (a * q)
        val num = sqrt(re * re + numIm * numIm)
        val den = sqrt(re * re + denIm * denIm)
        return 20.0 * log10(num / den)
    }

    @Test
    fun `oversampling is transparent when no bands are active`() {
        // A single 0-gain-adjacent band keeps the chain enabled but flat.
        val flat = listOf(EqBand(0, FilterType.PEAKING, 1000f, 0.01f, 1f, true))
        val amp1 = measure(makeProcessor(1, flat), 1000.0)
        val amp4 = measure(makeProcessor(4, flat), 1000.0)
        assertEquals(1.0, amp1, 0.01)
        assertEquals(1.0, amp4, 0.02)
    }

    @Test
    fun `4x oversampling lands the top-octave response on the analog curve`() {
        // +12 dB peak at 18 kHz, Q 1 — deep cramping territory at 48 kHz.
        val band = listOf(EqBand(0, FilterType.PEAKING, 18000f, 12f, 1f, true))
        val probe = 15000.0
        val target = analogPeakingDb(probe, 18000.0, 1.0, 12.0)

        val db1 = 20.0 * log10(measure(makeProcessor(1, band), probe))
        val db4 = 20.0 * log10(measure(makeProcessor(4, band), probe))

        val err1 = abs(db1 - target)
        val err4 = abs(db4 - target)
        // Oversampling must be strictly more accurate, and land within 1 dB.
        assertTrue("4x ($err4 dB off) should beat 1x ($err1 dB off)", err4 < err1)
        assertTrue("4x within 1 dB of analog target (was $err4)", err4 < 1.0)
        assertTrue("1x is visibly cramped (sanity, was $err1)", err1 > err4 + 0.25)
    }

    @Test
    fun `mid-band response is unaffected by oversampling`() {
        val band = listOf(EqBand(0, FilterType.PEAKING, 1000f, 6f, 1.4f, true))
        val db1 = 20.0 * log10(measure(makeProcessor(1, band), 1000.0))
        val db4 = 20.0 * log10(measure(makeProcessor(4, band), 1000.0))
        assertEquals(6.0, db1, 0.15)
        assertEquals(6.0, db4, 0.15)
    }
}
