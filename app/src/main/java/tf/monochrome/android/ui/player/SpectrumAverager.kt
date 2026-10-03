package tf.monochrome.android.ui.player

import tf.monochrome.android.domain.model.SpectrumAnalysisType
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Holds each spectrum bin over time the way [SpectrumAnalysisType] says, at
 * the averaging time it is given — the overlay's version of a spectrum
 * analyzer's "Type" and "Avg Time".
 *
 * Fed once per display frame with the analyzer's newest bins (dB) and the time
 * since the last frame, so it behaves the same at 60 Hz and at 120. Allocates
 * nothing after construction; [values] is updated in place and is what the
 * overlay draws.
 *
 *  - [SpectrumAnalysisType.RT_AVG] rises over about a fifth of the averaging
 *    time and falls over all of it. The asymmetry is the overlay's long-standing
 *    feel — a transient lands at once and decays gently — and at the default
 *    139 ms it reproduces the old fixed attack and release exactly.
 *  - [SpectrumAnalysisType.RT_MAX] takes a peak instantly, holds it for the
 *    averaging time, then falls at [MAX_FALL_DB_PER_SEC].
 *  - [SpectrumAnalysisType.AVG] is the mean power since [reset]: averaged as
 *    power, not as dB, so a loud bar and a silent one average to "3 dB under
 *    the loud one", not to half way between them.
 *  - [SpectrumAnalysisType.MAX] is the highest each bin has been since [reset].
 */
internal class SpectrumAverager(size: Int, private val floorDb: Float) {
    val values = FloatArray(size) { floorDb }
    private val holdLeft = FloatArray(size)
    private val meanPower = DoubleArray(size)
    private var frames = 0L
    private var lastType: SpectrumAnalysisType? = null

    /** Forget the history: the long-term types start over from the next frame. */
    fun reset() {
        values.fill(floorDb)
        holdLeft.fill(0f)
        meanPower.fill(0.0)
        frames = 0L
    }

    /**
     * Folds [src] into [values] over [dtSec] and returns the largest change to
     * any bin, in dB, so the caller can tell when the picture has settled.
     * [avgTimeMs] is ignored by the long-term types.
     */
    fun process(src: FloatArray, dtSec: Float, type: SpectrumAnalysisType, avgTimeMs: Float): Float {
        // A switch between kinds of history starts it over: a max carried into
        // an average, or the reverse, is neither.
        if (type != lastType) {
            lastType = type
            reset()
        }
        val n = minOf(src.size, values.size)
        val dt = dtSec.coerceAtLeast(0f)
        val avgSec = (avgTimeMs / 1000f).coerceAtLeast(0f)
        var largest = 0f
        when (type) {
            SpectrumAnalysisType.RT_AVG -> {
                val rise = alpha(dt, avgSec * RISE_FRACTION)
                val fall = alpha(dt, avgSec)
                for (i in 0 until n) {
                    val cur = values[i]
                    val step = (src[i] - cur) * if (src[i] > cur) rise else fall
                    values[i] = cur + step
                    largest = max(largest, kotlin.math.abs(step))
                }
            }
            SpectrumAnalysisType.RT_MAX -> {
                for (i in 0 until n) {
                    val cur = values[i]
                    val next = if (src[i] >= cur) {
                        holdLeft[i] = avgSec
                        src[i]
                    } else if (holdLeft[i] > 0f) {
                        holdLeft[i] -= dt
                        cur
                    } else {
                        max(src[i], cur - MAX_FALL_DB_PER_SEC * dt)
                    }
                    largest = max(largest, kotlin.math.abs(next - cur))
                    values[i] = next
                }
            }
            SpectrumAnalysisType.AVG -> {
                frames++
                val weight = 1.0 / frames
                for (i in 0 until n) {
                    val p = 10.0.pow(src[i] / 10.0)
                    meanPower[i] += (p - meanPower[i]) * weight
                    val db = (10.0 * log10(meanPower[i].coerceAtLeast(MIN_POWER))).toFloat()
                    largest = max(largest, kotlin.math.abs(db - values[i]))
                    values[i] = db
                }
            }
            SpectrumAnalysisType.MAX -> {
                for (i in 0 until n) {
                    if (src[i] > values[i]) {
                        largest = max(largest, src[i] - values[i])
                        values[i] = src[i]
                    }
                }
            }
        }
        return largest
    }

    private fun alpha(dt: Float, tauSec: Float): Float =
        if (tauSec <= 0f) 1f else (1f - exp(-dt / tauSec)).coerceIn(0f, 1f)

    companion object {
        /**
         * The rise time as a fraction of the averaging time: the old fixed
         * attack and release, 0.55 and 0.12 per 60 Hz frame, are time
         * constants of 30 ms and 139 ms, and this is their ratio. With the
         * default 139 ms it gives the old 30 ms rise.
         */
        const val RISE_FRACTION = 0.12f / 0.55f

        /** How fast a real-time max falls once its hold runs out. */
        const val MAX_FALL_DB_PER_SEC = 30f

        private const val MIN_POWER = 1e-12
    }
}
