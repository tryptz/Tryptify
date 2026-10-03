package tf.monochrome.android.audio.tempo

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * A track's tempo, from its audio as it plays.
 *
 * The usual two stages:
 *
 *  1. **Onset envelope.** Every [ENVELOPE_HZ]th of a second, the energy of
 *     three bands — the kick's (under 180 Hz), the whole signal, and the
 *     hats' (over 2.5 kHz) — and how much each *rose* since the last step.
 *     Only rises count (spectral flux, half-wave rectified): a beat is
 *     something starting. The energy is smoothed over [SMOOTH_MS] first, or
 *     a kick's own cycle (18 ms at 55 Hz, longer than a step) would ripple
 *     into onsets of its own, and compressed as log(1 + λ·energy), the
 *     usual onset compression: a quiet passage still counts, but silence
 *     cannot, where a plain log turns the faintest sound after it into the
 *     biggest onset in the song.
 *  2. **Periodicity.** The envelope's autocorrelation over the last
 *     [WINDOW_FRAMES] steps (about ten seconds), searched between [MIN_BPM]
 *     and [MAX_BPM], with a parabola through the peak for a lag finer than
 *     one step. The scores are weighted by a log-normal prior centred on
 *     120 BPM (Ellis, "Beat Tracking by Dynamic Programming", 2007) — the
 *     standard answer to half- and double-time confusion, where 87 and 174
 *     look alike to the autocorrelation.
 *
 * [estimate] is null until there is enough audio, and for anything without
 * a steady pulse (an intro, ambient, speech).
 *
 * Pure Kotlin without Android types, so it is tested against synthetic beats.
 * Cheap enough for the playback thread: a few multiplies per sample, and one
 * estimate a second of ~300k multiply-adds.
 */
class TempoEstimator(sampleRate: Int) {

    private val hop = max(1, (sampleRate / ENVELOPE_HZ.toFloat()).roundToInt())
    private val envelopeHz = sampleRate.toFloat() / hop

    private val lowPass = Biquad.lowPass(sampleRate.toFloat(), 180f)
    private val highPass = Biquad.highPass(sampleRate.toFloat(), 2500f)

    private var hopCount = 0
    private val smoothCoef = 1f - exp(-1f / (SMOOTH_MS * 0.001f * sampleRate))
    private val bandEnergy = FloatArray(BANDS)
    private val previousLog = FloatArray(BANDS)
    private var primed = false

    private val envelope = FloatArray(WINDOW_FRAMES)
    private var written = 0L

    /** Feeds [count] mono samples. */
    fun feed(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            val x = samples[i]
            val low = lowPass.process(x)
            val high = highPass.process(x)
            bandEnergy[0] += smoothCoef * (low * low - bandEnergy[0])
            bandEnergy[1] += smoothCoef * (x * x - bandEnergy[1])
            bandEnergy[2] += smoothCoef * (high * high - bandEnergy[2])
            if (++hopCount == hop) {
                pushFrame()
                hopCount = 0
            }
        }
    }

    private fun pushFrame() {
        var flux = 0f
        for (b in 0 until BANDS) {
            val log = ln(1f + COMPRESSION * bandEnergy[b])
            if (primed) flux += BAND_WEIGHT[b] * max(0f, log - previousLog[b])
            previousLog[b] = log
        }
        primed = true
        envelope[(written % WINDOW_FRAMES).toInt()] = flux
        written++
    }

    /** Seconds of envelope collected, up to the window. */
    val seconds: Float get() = minOf(written, WINDOW_FRAMES.toLong()) / envelopeHz

    /** A discontinuity (a seek): the next step must not read as one big onset. */
    fun discontinuity() {
        primed = false
        hopCount = 0
        bandEnergy.fill(0f)
    }

    /** Forgets everything: a new track. */
    fun reset() {
        discontinuity()
        lowPass.reset()
        highPass.reset()
        envelope.fill(0f)
        written = 0
    }

    /**
     * The tempo of the last [WINDOW_FRAMES] steps in BPM, or null without
     * enough audio or a clear pulse.
     */
    fun estimate(): Float? {
        val n = minOf(written, WINDOW_FRAMES.toLong()).toInt()
        if (n < envelopeHz * MIN_SECONDS) return null
        // Oldest first, without the mean: autocorrelation of a positive
        // envelope is otherwise dominated by its own DC.
        val start = if (written > WINDOW_FRAMES) (written % WINDOW_FRAMES).toInt() else 0
        val e = FloatArray(n)
        var mean = 0f
        for (i in 0 until n) {
            e[i] = envelope[(start + i) % WINDOW_FRAMES]
            mean += e[i]
        }
        mean /= n
        for (i in 0 until n) e[i] -= mean

        val zero = acf(e, n, 0)
        if (zero <= 1e-9f) return null

        val minLag = (60f * envelopeHz / MAX_BPM).toInt()
        val maxLag = minOf((60f * envelopeHz / MIN_BPM).toInt() + 1, n / 2)
        if (maxLag <= minLag + 2) return null
        val scores = FloatArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) scores[lag] = acf(e, n, lag)

        var best = -1
        var bestWeighted = 0f
        for (lag in minLag..maxLag) {
            val weighted = scores[lag] * prior(60f * envelopeHz / lag)
            if (weighted > bestWeighted && scores[lag] >= scores[lag - 1] && scores[lag] >= scores[lag + 1]) {
                bestWeighted = weighted
                best = lag
            }
        }
        if (best < 0) return null
        // A pulse, or just texture? Relative to the envelope's own energy.
        if (scores[best] / zero < MIN_CONFIDENCE) return null

        // Parabola through the peak and its neighbours: a fractional lag.
        val a = scores[best - 1]
        val b = scores[best]
        val c = scores[best + 1]
        val denom = a - 2f * b + c
        val offset = if (denom < 0f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f
        return 60f * envelopeHz / (best + offset)
    }

    private fun acf(e: FloatArray, n: Int, lag: Int): Float {
        var sum = 0f
        for (i in lag until n) sum += e[i] * e[i - lag]
        return sum / (n - lag)
    }

    /** Log-normal tempo prior: 1 at [PRIOR_BPM], falling an octave either side. */
    private fun prior(bpm: Float): Float {
        val octaves = ln(bpm / PRIOR_BPM) / LN2
        return exp(-0.5f * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
    }

    /** RBJ biquad, transposed direct form II. */
    private class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f
        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        fun reset() { z1 = 0f; z2 = 0f }

        companion object {
            private const val Q = 0.70710678f

            fun lowPass(sr: Float, hz: Float): Biquad {
                val k = tan(PI * hz / sr).toFloat()
                val norm = 1f / (1f + k / Q + k * k)
                val b0 = k * k * norm
                return Biquad(b0, 2f * b0, b0, 2f * (k * k - 1f) * norm, (1f - k / Q + k * k) * norm)
            }

            fun highPass(sr: Float, hz: Float): Biquad {
                val k = tan(PI * hz / sr).toFloat()
                val norm = 1f / (1f + k / Q + k * k)
                return Biquad(norm, -2f * norm, norm, 2f * (k * k - 1f) * norm, (1f - k / Q + k * k) * norm)
            }
        }
    }

    companion object {
        /** Onset envelope rate: one step every 5 ms. */
        const val ENVELOPE_HZ = 200

        /** About ten seconds of envelope. */
        const val WINDOW_FRAMES = 2048

        const val MIN_BPM = 60f
        const val MAX_BPM = 200f

        /** No answer before this much audio. */
        const val MIN_SECONDS = 6f

        private const val MIN_CONFIDENCE = 0.08f
        private const val SMOOTH_MS = 20f
        private const val COMPRESSION = 1000f
        private const val PRIOR_BPM = 120f
        private const val PRIOR_OCTAVES = 0.9f
        private const val BANDS = 3
        // The kick carries the pulse in most music; the hats keep it in time
        // where there is none.
        private val BAND_WEIGHT = floatArrayOf(1.0f, 0.6f, 0.4f)
        private val LN2 = ln(2f)
    }
}
