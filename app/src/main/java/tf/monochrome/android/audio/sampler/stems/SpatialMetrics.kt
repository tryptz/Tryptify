// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import tf.monochrome.android.audio.sampler.SampleEdits
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measurements of what a separator did to the stereo image.
 *
 * These exist because "it produced four files" is not evidence that separation
 * worked, and because the published position on this is discouraging: the
 * ISMIR 2025 binaural study measured three well-regarded separators and found
 * that stereo source separation models "fail to preserve the spatial
 * information critical for maintaining the immersive quality of binaural
 * audio", by an amount that depends on both the architecture and the
 * instrument.
 *
 * If that is true of every model then a Tryptify backend cannot be trusted on
 * the strength of its SDR score. It has to be measured on the axis that
 * actually matters here, which is the one the listener notices: does the image
 * still sit where it sat.
 *
 * Everything is a pure function over float PCM, so the whole suite runs on the
 * JVM with no device and no model.
 */
object SpatialMetrics {

    /**
     * What a stereo signal looks like spatially.
     *
     * @param correlation inter-channel correlation, -1..1. Near 1 is mono-like,
     *   0 is uncorrelated, negative means the channels are fighting.
     * @param width 0 for a mono signal, 1 for fully decorrelated.
     * @param balanceDb positive means the right channel is louder.
     * @param itdSamples the lag at which the channels best match — the
     *   time-of-arrival cue that places a source left or right. Positive means
     *   the right channel arrives later, which puts the source on the left.
     * @param ildDb level difference in the correlated part of the signal.
     */
    data class Image(
        val correlation: Float,
        val width: Float,
        val balanceDb: Float,
        val itdSamples: Int,
        val ildDb: Float,
        val rmsL: Float,
        val rmsR: Float,
        val peak: Float,
        val dcL: Float,
        val dcR: Float,
    ) {
        val isMono: Boolean get() = width < 0.02f
        val clipped: Boolean get() = peak > 1.0f
    }

    /** How far a stem's image moved from the mixture it came out of. */
    data class Drift(
        val correlationDelta: Float,
        val widthDelta: Float,
        val balanceDeltaDb: Float,
        val itdDeltaSamples: Int,
        val ildDeltaDb: Float,
    ) {
        /**
         * A single number for "did the image survive", so a test or a UI can
         * have a threshold without arguing about which of five to use.
         *
         * Weighted by what a listener actually notices. Width and correlation
         * dominate because collapsing a wide mix to the middle is the most
         * audible failure by a distance; ITD is scaled per sample because a few
         * samples of inter-channel delay is a real shift in apparent position.
         */
        val score: Float
            get() = abs(widthDelta) * 2f +
                abs(correlationDelta) * 1.5f +
                abs(balanceDeltaDb) * 0.1f +
                abs(itdDeltaSamples) * 0.05f +
                abs(ildDeltaDb) * 0.05f
    }

    /** Largest lag considered when looking for the inter-channel delay. */
    private const val MAX_ITD = 64

    /** Below this the signal is silence and its "image" is meaningless. */
    private const val SILENCE = 1e-7f

    fun measure(buffer: SampleEdits.Buffer): Image {
        val l = buffer.left
        val r = buffer.right ?: buffer.left
        val n = min(l.size, r.size)
        if (n == 0) return Image(1f, 0f, 0f, 0, 0f, 0f, 0f, 0f, 0f, 0f)

        var sumL = 0.0
        var sumR = 0.0
        var sumLL = 0.0
        var sumRR = 0.0
        var sumLR = 0.0
        var peak = 0f
        for (i in 0 until n) {
            val a = l[i]
            val b = r[i]
            sumL += a
            sumR += b
            sumLL += a.toDouble() * a
            sumRR += b.toDouble() * b
            sumLR += a.toDouble() * b
            peak = max(peak, max(abs(a), abs(b)))
        }

        val rmsL = sqrt(sumLL / n).toFloat()
        val rmsR = sqrt(sumRR / n).toFloat()
        val denominator = sqrt(sumLL * sumRR)

        // Correlation is undefined when a channel is silent, and the two ways
        // that happens mean opposite things.
        //
        // Both silent: the signal is silence. Report 1 — perfectly correlated,
        // width 0 — because calling silence "wide" would let a backend that
        // produces nothing pass a width check.
        //
        // One silent: the signal is hard panned. Report 0 — fully decorrelated,
        // width 1 — because that content is as far from centre as it gets, and
        // calling it mono would be exactly backwards. [balanceDb] says which
        // side.
        val correlation = when {
            denominator > SILENCE -> (sumLR / denominator).toFloat()
            rmsL <= SILENCE && rmsR <= SILENCE -> 1f
            else -> 0f
        }

        val balanceDb = when {
            rmsL <= SILENCE && rmsR <= SILENCE -> 0f
            rmsL <= SILENCE -> 120f
            rmsR <= SILENCE -> -120f
            else -> 20f * kotlin.math.log10(rmsR / rmsL)
        }

        var itd = 0
        var ild = 0f
        if (rmsL > SILENCE && rmsR > SILENCE) {
            itd = bestLag(l, r, n)
            ild = balanceDb
        }

        return Image(
            correlation = correlation,
            // Width as the decorrelated fraction. 1 - |correlation| rather than
            // a side/mid ratio because it stays bounded and behaves sensibly
            // when one channel is much quieter than the other.
            width = (1f - abs(correlation)).coerceIn(0f, 1f),
            balanceDb = balanceDb,
            itdSamples = itd,
            ildDb = ild,
            rmsL = rmsL,
            rmsR = rmsR,
            peak = peak,
            dcL = (sumL / n).toFloat(),
            dcR = (sumR / n).toFloat(),
        )
    }

    fun drift(original: SampleEdits.Buffer, processed: SampleEdits.Buffer): Drift =
        drift(measure(original), measure(processed))

    fun drift(original: Image, processed: Image): Drift = Drift(
        correlationDelta = processed.correlation - original.correlation,
        widthDelta = processed.width - original.width,
        balanceDeltaDb = processed.balanceDb - original.balanceDb,
        itdDeltaSamples = processed.itdSamples - original.itdSamples,
        ildDeltaDb = processed.ildDb - original.ildDb,
    )

    /**
     * The lag at which the right channel best matches the left.
     *
     * Normalised per lag rather than raw, because the overlap shrinks as the
     * lag grows and an unnormalised correlation would quietly prefer lag 0.
     */
    private fun bestLag(l: FloatArray, r: FloatArray, n: Int): Int {
        var best = -Double.MAX_VALUE
        var bestLag = 0
        for (lag in -MAX_ITD..MAX_ITD) {
            val from = max(0, -lag)
            val to = min(n, n - lag)
            if (to - from < 64) continue
            var sum = 0.0
            var energy = 0.0
            for (i in from until to) {
                val a = l[i].toDouble()
                val b = r[i + lag].toDouble()
                sum += a * b
                energy += b * b
            }
            if (energy <= 0.0) continue
            val score = sum / sqrt(energy)
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        return bestLag
    }

    // ── reconstruction ──────────────────────────────────────────────────

    /**
     * How close summing the stems comes to the original.
     *
     * @param rmsError RMS of (original - sum of stems), in the same units as
     *   the audio.
     * @param relativeError [rmsError] against the original's own RMS. This is
     *   the readable one: 0.01 means the residual is 1% of the signal.
     * @param peakError largest single-sample difference.
     */
    data class Reconstruction(
        val rmsError: Float,
        val relativeError: Float,
        val peakError: Float,
        val correlationDelta: Float,
        val energyRatio: Float,
    )

    /**
     * Sums [stems] and compares against [original].
     *
     * A separator whose stems do not add back up to the mix has either lost
     * energy or invented it, and both are audible when the stems are used
     * together — which is the normal case in a sampler. This is the check the
     * brief asks for, and it is deliberately not "did files appear".
     */
    fun reconstruction(
        original: SampleEdits.Buffer,
        stems: Collection<SampleEdits.Buffer>,
    ): Reconstruction {
        val n = original.frames
        if (n == 0 || stems.isEmpty()) return Reconstruction(0f, 0f, 0f, 0f, 1f)

        val sumL = FloatArray(n)
        val sumR = FloatArray(n)
        for (stem in stems) {
            val sl = stem.left
            val sr = stem.right ?: stem.left
            val m = min(n, min(sl.size, sr.size))
            for (i in 0 until m) {
                sumL[i] += sl[i]
                sumR[i] += sr[i]
            }
        }

        val ol = original.left
        val or = original.right ?: original.left
        var sqErr = 0.0
        var sqOrig = 0.0
        var peakErr = 0f
        for (i in 0 until n) {
            val el = ol[i] - sumL[i]
            val er = (if (i < or.size) or[i] else 0f) - sumR[i]
            sqErr += el.toDouble() * el + er.toDouble() * er
            val a = ol[i]
            val b = if (i < or.size) or[i] else 0f
            sqOrig += a.toDouble() * a + b.toDouble() * b
            peakErr = max(peakErr, max(abs(el), abs(er)))
        }

        val rmsError = sqrt(sqErr / (2.0 * n)).toFloat()
        val rmsOrig = sqrt(sqOrig / (2.0 * n)).toFloat()
        val summed = SampleEdits.Buffer(sumL, sumR, original.sampleRate)

        return Reconstruction(
            rmsError = rmsError,
            relativeError = if (rmsOrig > SILENCE) rmsError / rmsOrig else 0f,
            peakError = peakErr,
            correlationDelta = measure(summed).correlation - measure(original).correlation,
            // 1.0 means the stems carry exactly the mix's energy. Below means
            // the separator dropped signal on the floor; above means it
            // invented some, usually by letting the same content into two
            // stems at full level.
            energyRatio = if (sqOrig > 0.0) sqrt(sumEnergy(sumL, sumR) / sqOrig).toFloat() else 1f,
        )
    }

    private fun sumEnergy(l: FloatArray, r: FloatArray): Double {
        var e = 0.0
        for (i in l.indices) e += l[i].toDouble() * l[i] + r[i].toDouble() * r[i]
        return e
    }
}
