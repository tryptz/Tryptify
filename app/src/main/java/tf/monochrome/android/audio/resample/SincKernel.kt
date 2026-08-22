package tf.monochrome.android.audio.resample

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Windowed-sinc polyphase kernels for arbitrary-ratio resampling.
 *
 * The alternative, and what Media3's Sonic does in `interpolate()`, is to take
 * the two samples either side of the read position and blend them linearly.
 * That is a first-order interpolator: its response is `sinc^2`, which is
 * already -1.8 dB at 12 kHz and -4.8 dB at 19.2 kHz on a 48 kHz stream, and its
 * stopband falls off so slowly that the images it fails to suppress fold back
 * as inharmonic aliasing. On a pitch shift, that lands right where a headphone
 * correction is usually adding treble.
 *
 * A windowed sinc fixes both halves: a Kaiser window buys a designed stopband
 * (~80 dB here) instead of an accidental one, and the cutoff tracks the
 * resampling ratio so that speeding up band-limits the input *before*
 * decimating it rather than aliasing it back into the audible range.
 *
 * Design is not audio-thread work — it evaluates a sinc and a Bessel function
 * per tap per phase. Build the table when the ratio changes and let the audio
 * thread do nothing but multiply-accumulate.
 */
internal class SincKernel(
    /** Taps either side of the read position; the filter is `2 * halfWidth` long. */
    val halfWidth: Int,
    /** Fractional positions the table is sampled at. */
    val phases: Int,
    /**
     * `phases + 1` rows of `2 * halfWidth` taps. The extra row is phase 1.0, so
     * interpolating between rows `p` and `p + 1` never wraps.
     */
    val taps: Array<FloatArray>,
) {
    companion object {
        /**
         * Kaiser beta for roughly 80 dB of stopband attenuation. With 64 taps
         * that puts the transition band at about 3.8 kHz on a 48 kHz stream,
         * which is what leaves the passband flat to 20 kHz.
         */
        const val DEFAULT_BETA = 9.0

        /**
         * Cutoff as a fraction of the input sample rate at ratio <= 1. Below
         * Nyquist by enough to fit the transition band in.
         */
        const val DEFAULT_CUTOFF = 0.46

        /**
         * Builds the table for [ratio] input frames consumed per output frame.
         *
         * Above 1.0 the stream is being decimated, so the cutoff comes down by
         * the same factor: content above `nyquist / ratio` would otherwise fold
         * back. Below 1.0 the limit is plain Nyquist — there is nothing to
         * alias, only images to suppress.
         */
        fun design(
            ratio: Double,
            halfWidth: Int = 32,
            phases: Int = 256,
            beta: Double = DEFAULT_BETA,
            cutoffFraction: Double = DEFAULT_CUTOFF,
        ): SincKernel {
            require(halfWidth > 0 && phases > 0)
            val fc = cutoffFraction * min(1.0, 1.0 / ratio.coerceAtLeast(1e-6))
            val width = 2 * halfWidth
            val denom = besselI0(beta)
            val rows = Array(phases + 1) { p ->
                val frac = p.toDouble() / phases
                val row = DoubleArray(width)
                var sum = 0.0
                for (k in 0 until width) {
                    // Tap k sits at offset (k - halfWidth + 1) from the integer
                    // part of the read position; the fraction shifts it back.
                    val u = (k - halfWidth + 1) - frac
                    val windowArg = u / halfWidth
                    val w = if (abs(windowArg) >= 1.0) {
                        0.0
                    } else {
                        besselI0(beta * sqrt(1.0 - windowArg * windowArg)) / denom
                    }
                    val v = 2.0 * fc * sinc(2.0 * fc * u) * w
                    row[k] = v
                    sum += v
                }
                // Normalise each phase to unity DC gain. Without this the tiny
                // per-phase differences in tap sum become a rate-dependent gain
                // ripple — a tremolo that tracks the resampling fraction.
                val norm = if (abs(sum) > 1e-12) 1.0 / sum else 1.0
                FloatArray(width) { (row[it] * norm).toFloat() }
            }
            return SincKernel(halfWidth, phases, rows)
        }

        private fun sinc(x: Double): Double {
            if (abs(x) < 1e-9) return 1.0
            val px = PI * x
            return sin(px) / px
        }

        /** Modified Bessel function of the first kind, order zero. */
        internal fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 64) {
                val r = x / (2.0 * k)
                term *= r * r
                sum += term
                if (term < 1e-17 * sum) break
                k++
            }
            return sum
        }
    }
}
