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

    @Test
    fun `the shortest FFTs are read whole, the bass as one slope`() {
        for (size in listOf(256, 512)) {
            val bands = SpectrogramBands(size, 44_100, 256)
            // A tone at 2 kHz, through a Hann window, as the analyzer does it.
            val real = FloatArray(size) { i ->
                val w = 0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / (size - 1)).toFloat()
                w * kotlin.math.sin(2.0 * Math.PI * 2000.0 * i / 44_100.0).toFloat()
            }
            val imag = FloatArray(size)
            fft(real, imag)
            val out = FloatArray(256)
            bands.levels(real, imag, out)
            assertTrue(out.all { it.isFinite() })
            // Loudest near 2 kHz (band 170 of 256 is 2 kHz on the log scale), and the far ends well under it.
            val loudest = out.indices.maxByOrNull { out[it] }!!
            val hz = 20.0 * Math.pow(1000.0, loudest / 255.0)
            assertTrue("$size: loudest at $hz Hz", hz in 1500.0..2700.0)
            assertTrue(out[loudest] - out[250] > 20f)
            // Every band reads inside the FFT: none past its last bin.
            for (b in 0 until 256) {
                assertTrue(bands.first[b] in 1..size / 2 - 1)
                assertTrue(bands.last[b] <= size / 2 - 1)
                assertTrue(bands.centre[b] >= 1f && bands.centre[b] <= size / 2 - 2f)
            }
        }
    }

    /** A plain radix-2 FFT, in place, for the test's own signals. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = real[i]; real[i] = real[j]; real[j] = t; val u = imag[i]; imag[i] = imag[j]; imag[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = kotlin.math.cos(ang * k).toFloat()
                    val sn = kotlin.math.sin(ang * k).toFloat()
                    val xr = real[i + k + len / 2] * c - imag[i + k + len / 2] * sn
                    val xi = real[i + k + len / 2] * sn + imag[i + k + len / 2] * c
                    real[i + k + len / 2] = real[i + k] - xr
                    imag[i + k + len / 2] = imag[i + k] - xi
                    real[i + k] += xr
                    imag[i + k] += xi
                }
            }
            len = len shl 1
        }
    }
}
