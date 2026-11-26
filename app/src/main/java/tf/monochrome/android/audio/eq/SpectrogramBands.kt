package tf.monochrome.android.audio.eq

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The 3D spectrogram's reading of one FFT: a level per log-spaced band, in
 * dBFS with the display's pink tilt and nothing else. The spectrum the other
 * views draw ([SpectrumAnalyzerTap.spectrumBins]) is re-centred on its own
 * midband every frame, smoothed in time and blurred across pitch, which is
 * right for one line and wrong for a history of them: a quiet moment is
 * raised to the level of a loud one, so silence is never flat and a note
 * never stands clear of what is around it.
 *
 * A band that holds whole FFT bins takes its strongest (a tone is as tall
 * in a wide band as in a narrow one, and no harmonic falls between two
 * bands); one narrower than a bin reads between its two neighbours.
 */
internal class SpectrogramBands(
    private val fftSize: Int,
    sampleRate: Int,
    val bins: Int,
    minHz: Float = 20f,
    maxHz: Float = 20_000f,
    tiltDbPerOctave: Float = 4f,
) {
    /** First and last FFT bin of each band, or `last < first` for a band narrower than a bin. */
    val first = IntArray(bins)
    val last = IntArray(bins)
    /** Where a narrow band's centre lies, in FFT bins. */
    val centre = FloatArray(bins)
    private val tilt = FloatArray(bins)

    init {
        val top = max(minHz + 1f, min(maxHz, sampleRate / 2f - 1f))
        val logMin = log10(minHz)
        val logMax = log10(top)
        fun hz(b: Float): Float = 10f.pow(logMin + b / (bins - 1) * (logMax - logMin))
        val binHz = sampleRate.toFloat() / fftSize
        val limit = fftSize / 2 - 1
        for (b in 0 until bins) {
            val f = hz(b.toFloat())
            // The band runs half a step either side, in log frequency.
            val from = hz(b - 0.5f) / binHz
            val to = hz(b + 0.5f) / binHz
            first[b] = ceil(from).toInt().coerceIn(1, limit)
            last[b] = floor(to).toInt().coerceIn(0, limit)
            centre[b] = (f / binHz).coerceIn(1f, (limit - 1).toFloat())
            tilt[b] = tiltDbPerOctave * (ln(f / 1000f) / ln(2f))
        }
    }

    /** The bands' levels for the FFT in [real] and [imag], written to [out]. */
    fun levels(real: FloatArray, imag: FloatArray, out: FloatArray) {
        val norm = fftSize / 2f
        for (b in 0 until bins) {
            val power = if (last[b] >= first[b]) {
                var strongest = 0f
                for (k in first[b]..last[b]) {
                    val p = real[k] * real[k] + imag[k] * imag[k]
                    if (p > strongest) strongest = p
                }
                strongest
            } else {
                val i = centre[b].toInt()
                val f = centre[b] - i
                val a = sqrt(real[i] * real[i] + imag[i] * imag[i])
                val c = sqrt(real[i + 1] * real[i + 1] + imag[i + 1] * imag[i + 1])
                val m = a + (c - a) * f
                m * m
            }
            val magnitude = sqrt(power) / norm
            out[b] = (if (magnitude > 1e-9f) 20f * log10(magnitude) else SILENCE_DB) + tilt[b]
        }
    }

    companion object {
        /** What a band with nothing in it reads, before the tilt. */
        const val SILENCE_DB = -120f
    }
}
