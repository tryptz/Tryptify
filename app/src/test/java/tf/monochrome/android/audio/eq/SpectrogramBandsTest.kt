package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 3D spectrogram's reading of an FFT ([SpectrogramBands]): every FFT bin
 * in range belongs to a band, a tone is as tall in a wide band as in a
 * narrow one, and silence is silence, not a level raised to meet the eye.
 */
class SpectrogramBandsTest {
    private val n = 8192
    private val sr = 44_100
    private val bands = SpectrogramBands(n, sr, 256)

    /** The spectrum of a sine of [amplitude] at FFT bin [k], as the tap's windowed FFT gives it (a Hann peak). */
    private fun tone(k: Int, amplitude: Float): Pair<FloatArray, FloatArray> {
        val real = FloatArray(n)
        val imag = FloatArray(n)
        // A Hann-windowed sine on a bin centre: half the amplitude there, a quarter either side.
        real[k] = amplitude * n / 4f
        real[k - 1] = -amplitude * n / 8f
        real[k + 1] = -amplitude * n / 8f
        return real to imag
    }

    @Test
    fun `wide bands hold whole bins with none left out, narrow ones read between two`() {
        var narrow = 0
        var previousLast = 0
        for (b in 0 until bands.bins) {
            if (bands.last[b] < bands.first[b]) {
                narrow++
                assertTrue(bands.centre[b] >= 1f)
            } else {
                // No FFT bin falls between two wide bands.
                if (previousLast > 0) assertTrue("band $b", bands.first[b] <= previousLast + 1)
                previousLast = bands.last[b]
            }
        }
        // The low bands are narrower than an 8K FFT's bins; the high ones hold many.
        assertTrue(narrow in 20..140)
        assertTrue(bands.last[255] - bands.first[255] > 20)
    }

    @Test
    fun `a tone reads the same level wherever it falls in a wide band, and silence reads silence`() {
        val out = FloatArray(bands.bins)
        val levels = listOf(1500, 1503, 1507).map { k ->
            val (re, im) = tone(k, 0.5f)
            bands.levels(re, im, out)
            out.max()
        }
        assertEquals(levels[0], levels[1], 0.35f)
        assertEquals(levels[0], levels[2], 0.35f)
        // 0.5 amplitude through a Hann window is -12 dBFS, before the tilt (+12 dB at 8 kHz).
        val k = 1486 // 8 kHz
        val (re, im) = tone(k, 0.5f)
        bands.levels(re, im, out)
        assertEquals(-12f + 4f * 3f, out.max(), 0.6f)
        // 20 dB quieter reads 20 dB lower: nothing re-levels it.
        val (qr, qi) = tone(k, 0.05f)
        val quiet = FloatArray(bands.bins)
        bands.levels(qr, qi, quiet)
        assertEquals(out.max() - 20f, quiet.max(), 0.05f)
        // Nothing at all.
        bands.levels(FloatArray(n), FloatArray(n), out)
        assertTrue(out.all { it < SpectrogramBands.SILENCE_DB + 40f })
    }

    @Test
    fun `a real windowed sine lands in the band of its frequency`() {
        val hz = 440.0
        val real = FloatArray(n) { i ->
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sin(2 * PI * hz * i / sr) * w).toFloat()
        }
        val imag = FloatArray(n)
        // A plain DFT of the few bins around the tone is enough to fill them.
        val k0 = (hz * n / sr).toInt()
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (k in k0 - 6..k0 + 6) {
            var a = 0.0
            var b = 0.0
            for (i in 0 until n) {
                val ph = 2 * PI * k * i / n
                a += real[i] * cos(ph)
                b -= real[i] * sin(ph)
            }
            re[k] = a.toFloat()
            im[k] = b.toFloat()
        }
        val out = FloatArray(bands.bins)
        bands.levels(re, im, out)
        val loudest = out.indices.maxBy { out[it] }
        val bandHz = 20.0 * Math.pow(1000.0, loudest / 255.0)
        assertEquals(440.0, bandHz, 440.0 * 0.03)
        assertTrue(imag.all { it == 0f })
    }
}
