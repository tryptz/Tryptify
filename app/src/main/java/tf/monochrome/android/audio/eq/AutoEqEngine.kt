package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max

/**
 * AutoEqEngine — Headphone correction filter generator.
 * Greedy iterative algorithm matching the SeapEngine implementation.
 */
object AutoEqEngine {

    private const val MAX_BOOST = 12.0
    private const val MAX_CUT = 12.0
    private const val MIN_Q = 0.6
    private const val MAX_Q = 5.0
    private const val DEFAULT_SAMPLE_RATE = 48000f

    fun calculateBiquadResponse(
        freqHz: Float,
        band: EqBand,
        sampleRate: Float = DEFAULT_SAMPLE_RATE
    ): Float {
        if (!band.enabled) return 0f

        val w0 = 2.0 * PI * band.freq.toDouble() / sampleRate.toDouble()
        val phi = 2.0 * PI * freqHz.toDouble() / sampleRate.toDouble()
        val alpha = sin(w0) / (2.0 * band.q.toDouble())
        val A = 10.0.pow(band.gain.toDouble() / 40.0)
        val cosW0 = cos(w0)

        // RBJ Audio EQ Cookbook biquad coefficients per filter type. These
        // must mirror ParametricEqProcessor.BiquadFilter.configure() so the
        // displayed response curve matches the actual audio processing.
        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (band.type) {
            FilterType.LOWSHELF -> {
                val sq = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + sq)
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - sq)
                a0 = (A + 1.0) + (A - 1.0) * cosW0 + sq
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                a2 = (A + 1.0) + (A - 1.0) * cosW0 - sq
            }
            FilterType.HIGHSHELF -> {
                val sq = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + sq)
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - sq)
                a0 = (A + 1.0) - (A - 1.0) * cosW0 + sq
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                a2 = (A + 1.0) - (A - 1.0) * cosW0 - sq
            }
            else -> {
                b0 = 1.0 + alpha * A
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * A
                a0 = 1.0 + alpha / A
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / A
            }
        }

        val inv = 1.0 / a0
        val b0n = b0 * inv; val b1n = b1 * inv; val b2n = b2 * inv
        val a1n = a1 * inv; val a2n = a2 * inv

        val cp = cos(phi); val c2p = cos(2.0 * phi)
        val num = b0n*b0n + b1n*b1n + b2n*b2n +
                  2.0*(b0n*b1n + b1n*b2n)*cp + 2.0*b0n*b2n*c2p
        val den = 1.0 + a1n*a1n + a2n*a2n +
                  2.0*(a1n + a1n*a2n)*cp + 2.0*a2n*c2p

        return (10.0 * log10(num / den)).toFloat()
    }

    private fun interpolate(freq: Float, data: List<FrequencyPoint>): Float {
        if (data.isEmpty()) return 0f
        if (freq <= data.first().freq) return data.first().gain
        if (freq >= data.last().freq) return data.last().gain
        for (i in 0 until data.size - 1) {
            if (freq >= data[i].freq && freq <= data[i + 1].freq) {
                val t = (freq - data[i].freq) / (data[i + 1].freq - data[i].freq)
                return data[i].gain + t * (data[i + 1].gain - data[i].gain)
            }
        }
        return 0f
    }

    private fun getNormalizationOffset(data: List<FrequencyPoint>): Float {
        var sum = 0f; var count = 0
        for (p in data) if (p.freq in 250f..2500f) { sum += p.gain; count++ }
        return if (count > 0) sum / count else interpolate(1000f, data)
    }

    fun runAutoEqAlgorithm(
        measurement: List<FrequencyPoint>,
        target: List<FrequencyPoint>,
        bandCount: Int,
        maxFrequency: Float = 16000f,
        minFrequency: Float = 20f,
        @Suppress("UNUSED_PARAMETER") maxQ: Float = MAX_Q.toFloat(),
        sampleRate: Float = DEFAULT_SAMPLE_RATE
    ): List<EqBand> {
        val offset = getNormalizationOffset(target) - getNormalizationOffset(measurement)

        // Error curve: positive = above target (need cut), negative = below (need boost)
        val error = measurement.map { p ->
            FrequencyPoint(p.freq, (p.gain + offset) - interpolate(p.freq, target))
        }.toMutableList()

        val bands = mutableListOf<EqBand>()

        for (i in 0 until bandCount) {
            var maxDev = 0.0
            var maxWeightedDev = 0.0
            var peakFreq = 1000.0
            var peakIdx = 0

            // Scan: find largest weighted deviation (both positive and negative)
            for (j in error.indices) {
                val freq = error[j].freq.toDouble()
                if (freq < minFrequency || freq > maxFrequency) continue

                // 3-point smooth
                var v = error[j].gain.toDouble()
                if (j > 0 && j < error.size - 1) {
                    v = (error[j - 1].gain + v + error[j + 1].gain) / 3.0
                }

                // Priority weighting
                val priority = when {
                    freq < 300.0  -> 1.5
                    freq < 4000.0 -> 1.0
                    freq < 8000.0 -> 0.5
                    else          -> 0.25
                }

                val weightedAbs = abs(v * priority)
                if (weightedAbs > abs(maxWeightedDev)) {
                    maxWeightedDev = weightedAbs
                    maxDev = v
                    peakFreq = freq
                    peakIdx = j
                }
            }

            // Invert for correction
            var gain = -maxDev

            // Treble safety: taper max boost in highs
            var safeBoost = MAX_BOOST
            if (peakFreq > 3000.0) safeBoost = 6.0
            if (peakFreq > 6000.0) safeBoost = 3.0

            // Asymmetric clamping
            if (gain > safeBoost) gain = safeBoost
            if (gain < -MAX_CUT) gain = -MAX_CUT

            if (abs(gain) < 0.2) break

            // Q calculation: half-energy bandwidth
            val targetEnergy = maxDev / 2.0
            var lowerFreq = peakFreq
            var upperFreq = peakFreq

            for (k in peakIdx downTo 0) {
                if (abs(error[k].gain) < abs(targetEnergy)) {
                    lowerFreq = error[k].freq.toDouble()
                    break
                }
            }
            for (k in peakIdx until error.size) {
                if (abs(error[k].gain) < abs(targetEnergy)) {
                    upperFreq = error[k].freq.toDouble()
                    break
                }
            }

            var bandwidth = log2(upperFreq / max(1.0, lowerFreq))
            if (bandwidth < 0.1) bandwidth = 0.1

            var q = sqrt(2.0.pow(bandwidth)) / (2.0.pow(bandwidth) - 1.0)

            // Constraints
            if (q < MIN_Q) q = MIN_Q
            if (q > MAX_Q) q = MAX_Q
            if (peakFreq > 5000.0 && q > 3.0) q = 3.0  // treble safety
            if (gain > 0.0 && q > 2.0) q = 2.0          // boost safety

            val newBand = EqBand(
                id = i,
                type = FilterType.PEAKING,
                freq = peakFreq.toFloat(),
                gain = gain.toFloat(),
                q = q.toFloat(),
                enabled = true
            )
            bands.add(newBand)

            // Update error curve
            for (j in error.indices) {
                val response = calculateBiquadResponse(error[j].freq, newBand, sampleRate)
                error[j] = FrequencyPoint(error[j].freq, error[j].gain + response)
            }
        }

        // Sort by frequency, re-index
        return bands.sortedBy { it.freq }.mapIndexed { idx, b -> b.copy(id = idx) }
    }
}
