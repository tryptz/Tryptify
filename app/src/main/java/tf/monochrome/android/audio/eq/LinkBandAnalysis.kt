// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * The music links' own look at the sound, apart from the spectrum the EQ and
 * the visualizers draw. That one is made to look right, not to measure: it is
 * tilted pink, re-centred every frame on the 200 Hz–2 kHz average (so a kick
 * link fell when the vocals rose), blurred across neighbouring bins (so bands
 * bled into each other), smoothed over a fifth of a second before a link's own
 * attack ever saw it, read from one FFT bin a display bin, and taken over an
 * 8K or 16K window that smears a hit across a third of a second. A link read
 * off it lagged, softened, and moved with sound outside its band.
 *
 * Here a short window ([SIZE] samples, 43 ms at 48 kHz) is taken every few
 * milliseconds into a plain power spectrum ([powerSpectrum]), and each link
 * sums only its own band's bins ([bandDb]): the band's energy in dBFS, as the
 * mixer's knob links measure theirs (block RMS of the band, `mod_route.h`),
 * with no smoothing but the link's own. Pure Kotlin, for the tests.
 */
class LinkBandAnalysis {
    private val window = FloatArray(SIZE) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / SIZE)).toFloat() }
    /** One-sided power to mean square (Parseval, with the window's energy taken out). */
    private val powerScale = 2f / (SIZE.toFloat() * window.sumOf { (it * it).toDouble() }.toFloat())
    private val real = FloatArray(SIZE)
    private val imag = FloatArray(SIZE)
    private val cosT = FloatArray(SIZE / 2) { k -> cos(2.0 * PI * k / SIZE).toFloat() }
    private val sinT = FloatArray(SIZE / 2) { k -> -sin(2.0 * PI * k / SIZE).toFloat() }

    /**
     * The power of [samples] (the newest [SIZE], mono, −1..1) in each of the
     * [BINS] bins from 0 Hz to the Nyquist, written into [out], scaled so a
     * band's sum is its mean square.
     */
    fun powerSpectrum(samples: FloatArray, out: FloatArray) {
        require(samples.size >= SIZE && out.size >= BINS)
        for (i in 0 until SIZE) {
            real[i] = samples[i] * window[i]
            imag[i] = 0f
        }
        fft()
        for (k in 0 until BINS) {
            val p = real[k] * real[k] + imag[k] * imag[k]
            // DC and the Nyquist have no mirror image to fold in.
            out[k] = p * if (k == 0 || k == BINS - 1) powerScale / 2f else powerScale
        }
    }

    private fun fft() {
        // Bit reversal.
        var j = 0
        for (i in 1 until SIZE) {
            var bit = SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= SIZE) {
            val half = len / 2
            val step = SIZE / len
            var start = 0
            while (start < SIZE) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = real[b] * wr - imag[b] * wi
                    val xi = real[b] * wi + imag[b] * wr
                    real[b] = real[a] - xr
                    imag[b] = imag[a] - xi
                    real[a] += xr
                    imag[a] += xi
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** 43 ms at 48 kHz: a kick's hit lands in one window, and 30 Hz still spans a bin. */
        const val SIZE = 2048
        const val BINS = SIZE / 2 + 1

        /** What silence (or no analysis) reads as. */
        const val SILENT_DB = -120f

        /**
         * The energy of [loHz]..[hiHz] in [power] (from [powerSpectrum] at
         * [sampleRate]), in dBFS: the bins whose centres fall in the band, or
         * the one nearest it when none does. A full-scale sine in the band
         * reads −3 dB, its RMS, as on the mixer's knob links.
         */
        fun bandDb(power: FloatArray, sampleRate: Int, loHz: Float, hiHz: Float): Float {
            if (power.isEmpty() || sampleRate <= 0) return SILENT_DB
            val hzPerBin = sampleRate.toFloat() / SIZE
            val last = minOf(power.size, BINS) - 1
            var a = kotlin.math.ceil(loHz / hzPerBin).toInt().coerceIn(1, last)
            var b = kotlin.math.floor(hiHz / hzPerBin).toInt().coerceIn(1, last)
            if (b < a) {
                // A band narrower than a bin: the bin nearest its middle.
                a = Math.round((loHz + hiHz) / 2f / hzPerBin).coerceIn(1, last)
                b = a
            }
            var sum = 0.0
            for (k in a..b) sum += power[k]
            return if (sum > 1e-12) (10.0 * log10(sum)).toFloat() else SILENT_DB
        }
    }
}
