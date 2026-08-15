// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 complex FFT.
 *
 * There is already an FFT in the app — inside `SpectrumAnalyzerTap` — but it is
 * private, sized for that analyser and wired to its ring buffer. This one is a
 * plain function over two arrays so the stem separator can use it offline,
 * where the constraints are the opposite: correctness and reuse matter, and
 * the per-call cost does not.
 *
 * Both arrays are modified. Length must be a power of two, which [Stft] and
 * everything else here guarantees by construction.
 */
object Fft {

    /**
     * Transforms [re]/[im] in place. [inverse] also divides by N, so a forward
     * pass followed by an inverse pass is the identity.
     */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        if (n <= 1 || n != im.size || (n and (n - 1)) != 0) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = 2.0 * Math.PI / length * if (inverse) 1.0 else -1.0
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                // Twiddle by repeated multiplication rather than a table.
                // Over the block sizes used here the drift is far below the
                // noise floor, and it keeps this allocation-free.
                var cr = 1f
                var ci = 0f
                val half = length / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val xr = re[i + k + half]
                    val xi = im[i + k + half]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val nextCr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nextCr
                }
                i += length
            }
            length = length shl 1
        }

        if (inverse) {
            val scale = 1f / n
            for (k in 0 until n) {
                re[k] *= scale
                im[k] *= scale
            }
        }
    }

    /** Rounds up to a power of two, which is all the transform accepts. */
    fun nextPowerOfTwo(value: Int): Int {
        var n = 1
        while (n < value) n = n shl 1
        return n
    }
}
