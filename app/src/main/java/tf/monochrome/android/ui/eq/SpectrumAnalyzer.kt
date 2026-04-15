package tf.monochrome.android.ui.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Log-spaced, pink-weighted FFT analyser.
 *
 * Pink noise has power proportional to 1/f. If we sum FFT bin power into
 * equal-width log-frequency bins, each log bin collects `log(fHi/fLo)` worth
 * of spectrum — constant across bins. So pink noise produces a flat line at
 * the graph's 0 dB centre. No explicit +3 dB/oct correction needed.
 */
class SpectrumAnalyzer(
    private val fftSize: Int = 1024,
    private val binCount: Int = 96,
    private val minHz: Float = 20f,
    private val maxHz: Float = 20000f,
) {
    private val logMin = log10(minHz)
    private val logMax = log10(maxHz)
    private val window = FloatArray(fftSize) { i ->
        (0.5f - 0.5f * cos(2.0 * PI * i / (fftSize - 1)).toFloat())
    }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val smoothed = FloatArray(binCount) { -120f }
    private val output = FloatArray(binCount) { -120f }

    /**
     * Process interleaved PCM samples (mono or stereo) at [sampleRate].
     * Returns `binCount` dB values spanning `minHz..maxHz` log-spaced.
     * Values are referenced so that pink noise at ~-20 dBFS hits ~0 dB.
     */
    fun process(samples: FloatArray, channelCount: Int, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return output

        val step = max(1, channelCount)
        val totalFrames = samples.size / step
        if (totalFrames < 8) return output

        // Take most recent fftSize frames, downmix to mono, apply window.
        val take = minOf(fftSize, totalFrames)
        val srcStart = (totalFrames - take) * step
        for (i in 0 until fftSize) {
            if (i < take) {
                val idx = srcStart + i * step
                val s = if (channelCount >= 2) {
                    0.5f * (samples[idx] + samples[idx + 1])
                } else samples[idx]
                re[i] = s * window[i]
            } else re[i] = 0f
            im[i] = 0f
        }

        fftInPlace(re, im)

        // Sum power into log-spaced bins.
        val bandPower = FloatArray(binCount)
        val bandCount = IntArray(binCount)
        val half = fftSize / 2
        val hzPerBin = sampleRate.toFloat() / fftSize
        val invRange = 1f / (logMax - logMin)
        for (k in 1 until half) {
            val f = k * hzPerBin
            if (f < minHz || f > maxHz) continue
            val t = (log10(f) - logMin) * invRange
            val bi = (t * binCount).toInt().coerceIn(0, binCount - 1)
            val r = re[k]; val m = im[k]
            bandPower[bi] += r * r + m * m
            bandCount[bi]++
        }

        // Fill any empty bands by nearest neighbour so line stays continuous.
        for (b in 0 until binCount) {
            if (bandCount[b] == 0) {
                var l = b - 1
                while (l >= 0 && bandCount[l] == 0) l--
                var r = b + 1
                while (r < binCount && bandCount[r] == 0) r++
                bandPower[b] = when {
                    l >= 0 && r < binCount -> 0.5f * (bandPower[l] + bandPower[r])
                    l >= 0 -> bandPower[l]
                    r < binCount -> bandPower[r]
                    else -> 1e-12f
                }
            }
        }

        // Convert to dB, reference so pink ~0 dB, apply attack/release smoothing.
        val refOffset = 10f * log10(fftSize.toFloat()) + 20f // calibration to 0 dB line
        val raw = FloatArray(binCount)
        for (b in 0 until binCount) {
            val db = 10f * log10(bandPower[b] + 1e-12f) - refOffset
            val prev = smoothed[b]
            // Fast attack, very slow release (held-peak feel like FabFilter Pro-Q).
            val coef = if (db > prev) 0.55f else 0.06f
            smoothed[b] = prev + coef * (db - prev)
            raw[b] = smoothed[b]
        }

        // Spatial smoothing: 5-tap gaussian-ish blur across log-frequency bins
        // to remove per-bin jaggies and produce a flowing envelope.
        val w0 = 0.38f; val w1 = 0.24f; val w2 = 0.07f
        for (b in 0 until binCount) {
            val l2 = raw[(b - 2).coerceAtLeast(0)]
            val l1 = raw[(b - 1).coerceAtLeast(0)]
            val c = raw[b]
            val r1 = raw[(b + 1).coerceAtMost(binCount - 1)]
            val r2 = raw[(b + 2).coerceAtMost(binCount - 1)]
            output[b] = (w2 * l2 + w1 * l1 + w0 * c + w1 * r1 + w2 * r2)
                .coerceIn(-80f, 40f)
        }
        return output
    }

    fun clear() {
        for (i in smoothed.indices) { smoothed[i] = -120f; output[i] = -120f }
    }

    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Cooley–Tukey.
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val tRe = curRe * re[b] - curIm * im[b]
                    val tIm = curRe * im[b] + curIm * re[b]
                    re[b] = re[a] - tRe; im[b] = im[a] - tIm
                    re[a] = re[a] + tRe; im[a] = im[a] + tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
