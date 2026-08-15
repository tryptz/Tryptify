// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Short-time Fourier transform, and its inverse.
 *
 * Weighted overlap-add: the Hann window is applied on the way in *and* on the
 * way out, and the sum of the squared windows is divided out at the end. That
 * costs one extra multiply per sample and buys exact reconstruction for any
 * hop that satisfies the constant-overlap-add condition — including after the
 * spectrum has been modified, which is the entire point here. Windowing only
 * on analysis leaves discontinuities at frame edges once a mask has been
 * applied, and those are audible as a periodic flutter at the hop rate.
 *
 * Dividing by the measured window sum rather than a constant also means the
 * first and last frames, where the overlap is incomplete, come back correct
 * instead of quiet.
 */
class Stft(
    val fftSize: Int = DEFAULT_FFT_SIZE,
    val hop: Int = DEFAULT_FFT_SIZE / 4,
) {

    /** Real and imaginary parts, `[frame][bin]`, with `bins` = fftSize/2 + 1. */
    class Spectrogram(
        val re: Array<FloatArray>,
        val im: Array<FloatArray>,
        val frames: Int,
        val bins: Int,
        /** Zero-padding added in front, removed again on synthesis. */
        val pad: Int,
        /** Length of the padded signal the frames were taken from. */
        val paddedLength: Int,
    ) {
        fun magnitude(): Array<FloatArray> = Array(frames) { f ->
            FloatArray(bins) { b -> sqrt(re[f][b] * re[f][b] + im[f][b] * im[f][b]) }
        }
    }

    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / fftSize)).toFloat()
    }

    fun analyze(signal: FloatArray): Spectrogram {
        // Padded at both ends so the first and last real samples are covered by
        // a full set of overlapping windows.
        val pad = fftSize
        val padded = FloatArray(pad + signal.size + pad + fftSize)
        signal.copyInto(padded, pad)

        val frameCount = ((padded.size - fftSize) / hop) + 1
        val bins = fftSize / 2 + 1
        val re = Array(frameCount) { FloatArray(bins) }
        val im = Array(frameCount) { FloatArray(bins) }

        val bufferRe = FloatArray(fftSize)
        val bufferIm = FloatArray(fftSize)

        for (f in 0 until frameCount) {
            val offset = f * hop
            for (i in 0 until fftSize) {
                bufferRe[i] = padded[offset + i] * window[i]
                bufferIm[i] = 0f
            }
            Fft.transform(bufferRe, bufferIm, inverse = false)
            // Only the non-redundant half is kept; the spectrum of a real
            // signal is conjugate-symmetric, so the upper bins carry nothing
            // new and doubling the memory would buy nothing.
            bufferRe.copyInto(re[f], 0, 0, bins)
            bufferIm.copyInto(im[f], 0, 0, bins)
        }

        return Spectrogram(re, im, frameCount, bins, pad, padded.size)
    }

    /** Rebuilds a signal of [outputLength] samples from [spec]. */
    fun synthesize(spec: Stft.Spectrogram, outputLength: Int): FloatArray {
        val accumulator = FloatArray(spec.paddedLength)
        val windowSum = FloatArray(spec.paddedLength)

        val bufferRe = FloatArray(fftSize)
        val bufferIm = FloatArray(fftSize)

        for (f in 0 until spec.frames) {
            // Rebuild the discarded half by conjugate symmetry before inverting.
            for (b in 0 until spec.bins) {
                bufferRe[b] = spec.re[f][b]
                bufferIm[b] = spec.im[f][b]
            }
            for (b in spec.bins until fftSize) {
                val mirror = fftSize - b
                bufferRe[b] = spec.re[f][mirror]
                bufferIm[b] = -spec.im[f][mirror]
            }
            Fft.transform(bufferRe, bufferIm, inverse = true)

            val offset = f * hop
            if (offset + fftSize > accumulator.size) break
            for (i in 0 until fftSize) {
                accumulator[offset + i] += bufferRe[i] * window[i]
                windowSum[offset + i] += window[i] * window[i]
            }
        }

        val out = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val index = spec.pad + i
            if (index >= accumulator.size) break
            val sum = windowSum[index]
            // The guard matters at the very edges, where only a fraction of a
            // window has landed and dividing by it would amplify noise into a
            // click.
            out[i] = if (sum > 1e-6f) accumulator[index] / sum else 0f
        }
        return out
    }

    companion object {
        /**
         * 2048 at 48 kHz is ~43 ms — long enough to resolve a bass note, short
         * enough that a snare does not smear across the window. The usual
         * compromise for percussive/harmonic work.
         */
        const val DEFAULT_FFT_SIZE = 2048
    }
}
