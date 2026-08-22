package tf.monochrome.android.audio.resample

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the resampler has to beat is Media3's `Sonic.interpolate()`: a two-point
 * linear blend, which is what runs when pitch rides the tempo. These tests
 * measure both and assert the gap, so the claim is a number rather than an
 * opinion.
 */
class VariRateAudioProcessorTest {

    private val fs = 48000

    private fun processor(ratio: Float): VariRateAudioProcessor {
        val p = VariRateAudioProcessor()
        p.configure(AudioFormat(fs, 2, C.ENCODING_PCM_FLOAT))
        p.setRatio(ratio)
        p.flush()
        return p
    }

    /** Runs a stereo sine through and returns the left output channel. */
    private fun run(p: VariRateAudioProcessor, freq: Double, inFrames: Int): FloatArray {
        val chunk = 1024
        val out = ArrayList<Float>(inFrames * 4)
        val buf = ByteBuffer.allocateDirect(chunk * 8).order(ByteOrder.nativeOrder())
        var n = 0
        while (n < inFrames) {
            val count = minOf(chunk, inFrames - n)
            buf.clear()
            for (i in 0 until count) {
                val s = sin(2.0 * PI * freq * (n + i) / fs).toFloat()
                buf.putFloat(s); buf.putFloat(s)
            }
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            while (o.remaining() >= 8) { out.add(o.getFloat()); o.getFloat() }
            n += count
        }
        return out.toFloatArray()
    }

    /** Amplitude of [freq] in [x], by Goertzel over the settled portion. */
    private fun amplitudeAt(x: FloatArray, freq: Double): Double {
        val start = x.size / 4
        val n = x.size - start
        if (n <= 16) return 0.0
        val w = 2.0 * PI * freq / fs
        val c = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in start until x.size) {
            val s0 = x[i] + c * s1 - s2
            s2 = s1; s1 = s0
        }
        return sqrt((s1 * s1 + s2 * s2 - c * s1 * s2).coerceAtLeast(0.0)) / (n / 2.0)
    }

    private fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        val start = x.size / 4
        var acc = 0.0
        for (i in start until x.size) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (x.size - start))
    }

    /**
     * Sonic's kernel, reproduced: linear blend of the two samples either side
     * of the read position, with no band-limiting of any kind.
     */
    private fun linearInterpResample(freq: Double, ratio: Double, inFrames: Int): FloatArray {
        val input = DoubleArray(inFrames) { sin(2.0 * PI * freq * it / fs) }
        val outCount = ((inFrames - 2) / ratio).toInt()
        return FloatArray(outCount) { k ->
            val pos = k * ratio
            val i = pos.toInt()
            val f = pos - i
            ((input[i] * (1 - f)) + input[i + 1] * f).toFloat()
        }
    }

    @Test
    fun `unity ratio is inactive`() {
        val p = VariRateAudioProcessor()
        p.configure(AudioFormat(fs, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        assertFalse(p.isActive)
        p.setRatio(1.5f)
        assertTrue(p.isActive)
        p.setRatio(1.0f)
        assertFalse(p.isActive)
    }

    @Test
    fun `speeding up moves the tone up by the ratio`() {
        val p = processor(1.5f)
        val out = run(p, 1000.0, 48000)
        assertEquals("tone should land at 1500 Hz", 1.0, amplitudeAt(out, 1500.0), 0.05)
        assertTrue("nothing should remain at 1000 Hz", amplitudeAt(out, 1000.0) < 0.02)
    }

    @Test
    fun `slowing down moves the tone down by the ratio`() {
        val p = processor(0.5f)
        val out = run(p, 2000.0, 24000)
        assertEquals(1.0, amplitudeAt(out, 1000.0), 0.05)
    }

    @Test
    fun `exact semitone ratios shift by exact semitones`() {
        // The whole chain's reason for existing: +7 semitones must arrive as a
        // fifth, not as something 6 cents off one.
        val ratio = 2.0.pow(7.0 / 12.0)
        val p = processor(ratio.toFloat())
        val out = run(p, 440.0, 48000)
        assertEquals(1.0, amplitudeAt(out, 440.0 * ratio), 0.05)
    }

    @Test
    fun `output frame count tracks the ratio`() {
        for (ratio in listOf(0.5f, 1.25f, 2.0f)) {
            val p = processor(ratio)
            val out = run(p, 1000.0, 48000)
            val expected = 48000 / ratio
            assertEquals(
                "ratio $ratio produced ${out.size} frames",
                expected.toDouble(), out.size.toDouble(), expected * 0.01,
            )
        }
    }

    @Test
    fun `speeding up rejects what would otherwise alias`() {
        // At 2x, a 15 kHz input maps to 30 kHz -- above Nyquist. It must be
        // filtered out before decimation, not folded back to 18 kHz.
        val ratio = 2.0
        val ours = run(processor(ratio.toFloat()), 15000.0, 48000)
        val theirs = linearInterpResample(15000.0, ratio, 48000)

        val ourAlias = 20 * log10(rms(ours).coerceAtLeast(1e-12))
        val theirAlias = 20 * log10(rms(theirs).coerceAtLeast(1e-12))

        assertTrue(
            "sinc kernel should suppress the alias (got $ourAlias dBFS)",
            ourAlias < -55.0,
        )
        // And it should be dramatically better than the linear blend.
        assertTrue(
            "expected a large improvement, ours=$ourAlias dB theirs=$theirAlias dB",
            theirAlias - ourAlias > 30.0,
        )
    }

    @Test
    fun `passband stays flat where linear interpolation droops`() {
        // Linear interpolation's response is sinc^2: about -1.8 dB at 12 kHz on
        // a 48 kHz stream, before any aliasing is considered.
        val ratio = 1.25
        for (freq in listOf(1000.0, 5000.0, 10000.0)) {
            val out = run(processor(ratio.toFloat()), freq, 48000)
            val gain = 20 * log10(amplitudeAt(out, freq * ratio).coerceAtLeast(1e-12))
            assertTrue("gain at $freq Hz was $gain dB", abs(gain) < 0.5)
        }
    }

    @Test
    fun `constant input stays constant`() {
        // Per-phase DC normalisation: without it the tap-sum differences show up
        // as a gain ripple that tracks the resampling fraction.
        val p = processor(1.37f)
        val chunk = 1024
        val buf = ByteBuffer.allocateDirect(chunk * 8).order(ByteOrder.nativeOrder())
        val out = ArrayList<Float>()
        repeat(40) {
            buf.clear()
            repeat(chunk) { buf.putFloat(0.5f); buf.putFloat(0.5f) }
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            while (o.remaining() >= 8) { out.add(o.getFloat()); o.getFloat() }
        }
        val settled = out.subList(out.size / 2, out.size)
        val maxDev = settled.maxOf { abs(it - 0.5f) }
        assertTrue("DC ripple of $maxDev", maxDev < 1e-3)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
