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
/**
 * How the correction stack is fitted.
 *
 * [PEAKING] — the classic greedy fit: every band is a bell, including at the
 * frequency extremes.
 *
 * [SHELF_ENDS] — fits a low shelf and a high shelf FIRST, then bells on the
 * residual. Engineering rationale: headphone deviations at the extremes are
 * broad tilts (bass roll-off/boost, treble tilt), not resonances — a shelf
 * matches that shape with one filter where bells leave ripple, extrapolates
 * gracefully below/above the measurement's reliable range (rig bass data
 * under ~40 Hz is seal-dependent noise), and carries no resonant overshoot
 * into the preamp headroom budget.
 */
enum class AutoEqAlgorithm(val label: String) {
    PEAKING("Peaking"),
    SHELF_ENDS("Shelf ends"),
}

object AutoEqEngine {

    /**
     * Measurement smoothing, ported verbatim from SeapEngine's graph tool
     * (the `a7` helper): a triangular-weighted moving average whose window
     * radius grows with the 0–100 % slider — radius = floor(percent / 2.5)
     * POINTS, weights 1 − |offset|/(radius+1). Index-based on purpose:
     * measurement files are log-spaced in frequency, so a point window IS an
     * octave window, and matching SeapEngine exactly means a profile tuned
     * there reproduces identically here. 0 % (or <3 points) is a no-op.
     */
    fun smoothCurve(
        points: List<FrequencyPoint>,
        percent: Float,
    ): List<FrequencyPoint> {
        if (percent <= 0f || points.size < 3) return points
        val radius = kotlin.math.max(1, (percent / 2.5f).toInt())
        val out = ArrayList<FrequencyPoint>(points.size)
        for (i in points.indices) {
            var sum = 0f
            var wSum = 0f
            for (c in -radius..radius) {
                val j = i + c
                if (j in points.indices) {
                    val w = 1f - kotlin.math.abs(c).toFloat() / (radius + 1)
                    sum += points[j].gain * w
                    wSum += w
                }
            }
            out.add(FrequencyPoint(points[i].freq, sum / wSum))
        }
        return out
    }

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
        val c = coeffsFor(band, sampleRate)
        val phi = 2.0 * PI * freqHz.toDouble() / sampleRate.toDouble()
        return magnitudeDb(c, cos(phi), cos(2.0 * phi)).toFloat()
    }

    /**
     * Normalized RBJ coefficients for a band. Split out of the response
     * calculation because they are FIXED per band — only the phase term varies
     * per evaluation frequency. The refinement pass exploits this: coefficients
     * once per candidate, then a transcendental-free magnitude per grid point
     * against precomputed cos(φ)/cos(2φ) tables.
     */
    private class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    )

    private fun coeffsFor(band: EqBand, sampleRate: Float): BiquadCoeffs {
        val w0 = 2.0 * PI * band.freq.toDouble() / sampleRate.toDouble()
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
        return BiquadCoeffs(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv)
    }

    /** |H| in dB at a grid point, given cos(φ) and cos(2φ). Pure arithmetic. */
    private fun magnitudeDb(c: BiquadCoeffs, cp: Double, c2p: Double): Double {
        val num = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 +
            2.0 * (c.b0 * c.b1 + c.b1 * c.b2) * cp + 2.0 * c.b0 * c.b2 * c2p
        val den = 1.0 + c.a1 * c.a1 + c.a2 * c.a2 +
            2.0 * (c.a1 + c.a1 * c.a2) * cp + 2.0 * c.a2 * c2p
        return 10.0 * log10(num / den)
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
        sampleRate: Float = DEFAULT_SAMPLE_RATE,
        algorithm: AutoEqAlgorithm = AutoEqAlgorithm.PEAKING,
    ): List<EqBand> {
        val offset = getNormalizationOffset(target) - getNormalizationOffset(measurement)

        // Error curve: positive = above target (need cut), negative = below (need boost)
        val error = measurement.map { p ->
            FrequencyPoint(p.freq, (p.gain + offset) - interpolate(p.freq, target))
        }.toMutableList()

        val bands = mutableListOf<EqBand>()

        if (algorithm == AutoEqAlgorithm.SHELF_ENDS) {
            // Low shelf: corner at AutoEq's conventional 105 Hz, gain fitted to
            // the mean error below it. Full ±12 range — bass boosts are the
            // shelf's whole job.
            fitEndShelf(
                error, FilterType.LOWSHELF,
                corner = 105f, regionLo = minFrequency, regionHi = 105f,
                maxBoost = MAX_BOOST, sampleRate = sampleRate, id = bands.size,
            )?.let(bands::add)
            // High shelf: corner at 10 kHz (pulled down when the fit range
            // ends earlier). Boost capped at 4 dB — the plateau spans the
            // whole top octave, where measurement confidence and hearing-
            // damage risk both argue for restraint; cuts keep the full range.
            val hiCorner = kotlin.math.min(10_000f, maxFrequency * 0.75f)
            fitEndShelf(
                error, FilterType.HIGHSHELF,
                corner = hiCorner, regionLo = hiCorner, regionHi = maxFrequency,
                maxBoost = 4.0, sampleRate = sampleRate, id = bands.size,
            )?.let(bands::add)
        }

        while (bands.size < bandCount) {
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
                val priority = priorityWeight(freq)

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
                id = bands.size,
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

        // Cyclic refinement: the greedy loop froze each band at fit time, so
        // early choices never adapt to later ones — exactly what starves
        // low band counts. Re-fitting each band against the residual all the
        // OTHERS leave lets a small stack cooperate like a jointly-optimized
        // one.
        refineBands(bands, error, minFrequency, maxFrequency, sampleRate)

        // Sort by frequency, re-index
        return bands.sortedBy { it.freq }.mapIndexed { idx, b -> b.copy(id = idx) }
    }

    private fun priorityWeight(freq: Double): Double = when {
        freq < 300.0  -> 1.5
        freq < 4000.0 -> 1.0
        freq < 8000.0 -> 0.5
        else          -> 0.25
    }

    /** Frequency-dependent boost/cut clamp shared by the greedy fit and refinement. */
    private fun clampGain(g: Double, type: FilterType, freq: Double): Double {
        val boostCap = when {
            type == FilterType.LOWSHELF -> MAX_BOOST
            type == FilterType.HIGHSHELF -> 4.0
            freq > 6000.0 -> 3.0
            freq > 3000.0 -> 6.0
            else -> MAX_BOOST
        }
        return g.coerceIn(-MAX_CUT, boostCap)
    }

    /**
     * Cyclic coordinate descent over the fitted stack (a few sweeps):
     * for each band, remove its response from the residual, then re-fit it on
     * what the OTHER bands actually leave behind — frequency and Q over a
     * local log-grid (shelves keep their corner and slope; only their gain
     * refits), gain in closed form by weighted least squares. The dB response
     * of an RBJ filter is near-linear in its gain setting for the ranges used
     * here, which is what makes the closed-form gain valid; candidates are
     * still SCORED with the exact response, so the approximation only picks
     * the search point, never the final answer. Bands another band fully
     * absorbed (|gain| < 0.2 dB) are dropped rather than left as noise.
     */
    private fun refineBands(
        bands: MutableList<EqBand>,
        residual: MutableList<FrequencyPoint>,
        minFrequency: Float,
        maxFrequency: Float,
        sampleRate: Float,
        sweeps: Int = 4,
    ) {
        if (bands.isEmpty()) return
        val n = residual.size

        // Phase tables: cos(φ)/cos(2φ) per grid point, computed ONCE. Every
        // candidate evaluation after this is pure arithmetic — the win that
        // makes a wide candidate grid and multiple sweeps cost milliseconds.
        val cp = DoubleArray(n)
        val c2p = DoubleArray(n)
        val weights = DoubleArray(n)
        for (j in 0 until n) {
            val phi = 2.0 * PI * residual[j].freq.toDouble() / sampleRate.toDouble()
            cp[j] = cos(phi)
            c2p[j] = cos(2.0 * phi)
            val f = residual[j].freq.toDouble()
            weights[j] = if (f < minFrequency || f > maxFrequency) 0.0 else priorityWeight(f)
        }
        val res = DoubleArray(n) { residual[it].gain.toDouble() }

        var prevTotal = Double.MAX_VALUE
        for (sweep in 0 until sweeps) {
            for (k in bands.indices) {
                val band = bands[k]
                // Residual with band k's contribution removed.
                val ck = coeffsFor(band, sampleRate)
                val eWo = DoubleArray(n)
                for (j in 0 until n) eWo[j] = res[j] - magnitudeDb(ck, cp[j], c2p[j])

                // ±half-octave in frequency, 0.5–2× in Q — wide enough to walk
                // out of a bad greedy seed over a few sweeps.
                val freqCands: List<Float>
                val qCands: List<Float>
                if (band.type == FilterType.PEAKING) {
                    freqCands = listOf(0.71f, 0.84f, 1f, 1.19f, 1.41f)
                        .map { (band.freq * it).coerceIn(minFrequency, maxFrequency) }
                        .distinct()
                    qCands = listOf(0.5f, 0.7f, 1f, 1.4f, 2f)
                        .map { (band.q * it).coerceIn(MIN_Q.toFloat(), MAX_Q.toFloat()) }
                        .distinct()
                } else {
                    freqCands = listOf(band.freq)
                    qCands = listOf(band.q)
                }

                var best = band
                var bestScore = Double.MAX_VALUE
                for (fc in freqCands) for (qc0 in qCands) {
                    var qc = qc0
                    if (band.type == FilterType.PEAKING && fc > 5000f && qc > 3f) qc = 3f

                    // Closed-form gain from the +1 dB basis shape (RBJ dB
                    // response is near-linear in gain at these ranges); the
                    // exact response still does the scoring below.
                    val cProbe = coeffsFor(band.copy(freq = fc, q = qc, gain = 1f), sampleRate)
                    var num = 0.0
                    var den = 1e-9
                    for (j in 0 until n) {
                        val sj = magnitudeDb(cProbe, cp[j], c2p[j])
                        num += weights[j] * eWo[j] * sj
                        den += weights[j] * sj * sj
                    }
                    var g = clampGain(-num / den, band.type, fc.toDouble())
                    if (band.type == FilterType.PEAKING && g > 0.0 && qc > 2f) qc = 2f
                    val cand = band.copy(freq = fc, q = qc, gain = g.toFloat())

                    val cCand = coeffsFor(cand, sampleRate)
                    var score = 0.0
                    for (j in 0 until n) {
                        val r = eWo[j] + magnitudeDb(cCand, cp[j], c2p[j])
                        score += weights[j] * r * r
                    }
                    if (score < bestScore) {
                        bestScore = score
                        best = cand
                    }
                }

                bands[k] = best
                val cBest = coeffsFor(best, sampleRate)
                for (j in 0 until n) res[j] = eWo[j] + magnitudeDb(cBest, cp[j], c2p[j])
            }

            // Converged? Stop early rather than burning identical sweeps.
            var total = 0.0
            for (j in 0 until n) total += weights[j] * res[j] * res[j]
            if (prevTotal - total < prevTotal * 0.005) break
            prevTotal = total
        }

        for (j in 0 until n) residual[j] = FrequencyPoint(residual[j].freq, res[j].toFloat())
        bands.removeAll { abs(it.gain) < 0.2f }
    }

    /**
     * Fits one end shelf to the mean residual error over [regionLo, regionHi]
     * and subtracts its real (RBJ) response from the error curve. Returns null
     * when the region is empty or the fitted gain is below 1 dB — a broadband
     * tilt under 1 dB sits at the just-noticeable difference, and residual
     * sub-dB offsets also appear spuriously whenever midband normalization
     * shifts the whole curve. Not worth a filter slot either way.
     */
    private fun fitEndShelf(
        error: MutableList<FrequencyPoint>,
        type: FilterType,
        corner: Float,
        regionLo: Float,
        regionHi: Float,
        maxBoost: Double,
        sampleRate: Float,
        id: Int,
    ): EqBand? {
        var sum = 0.0
        var count = 0
        for (p in error) {
            if (p.freq in regionLo..regionHi) {
                sum += p.gain
                count++
            }
        }
        if (count == 0) return null
        var gain = -(sum / count)
        if (gain > maxBoost) gain = maxBoost
        if (gain < -MAX_CUT) gain = -MAX_CUT
        if (abs(gain) < 1.0) return null

        // Butterworth shoulder: monotonic, no corner overshoot to re-correct.
        val band = EqBand(
            id = id,
            type = type,
            freq = corner,
            gain = gain.toFloat(),
            q = 0.707f,
            enabled = true,
        )
        for (j in error.indices) {
            val response = calculateBiquadResponse(error[j].freq, band, sampleRate)
            error[j] = FrequencyPoint(error[j].freq, error[j].gain + response)
        }
        return band
    }
}
