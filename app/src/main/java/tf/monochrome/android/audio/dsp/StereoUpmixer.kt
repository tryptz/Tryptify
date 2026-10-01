package tf.monochrome.android.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Stereo → 9.1.6, written so the fold back to stereo gives the song back.
 *
 * Output is 16 channels in the order the rest of the pipeline reads a 9.1.6
 * bed ([ChannelLayout]): FL FR FC LFE BL BR FLC FRC SL SR TFL TFR TBL TBR TSL
 * TSR. The mixer spreads that onto nine buses, and [DownmixProcessor] folds
 * it to stereo (or the spatial map places it) unless the listener passes it
 * through. Most people hear the fold, so this is built around it: every
 * piece taken out of the stereo is put back by the fold's own matrix
 * (all lefts to the left at 1, FC at [DownmixProcessor.CENTER_COEF], LFE at
 * [DownmixProcessor.LFE_COEF] into both). The split, in the order an upmix
 * engineer works:
 *
 *  - **Bass** below [CROSSOVER_HZ] (the LFE convention). A share of the
 *    low mid goes to the LFE at 1 / LFE_COEF and both front speakers give
 *    up that much, so the fold restores it exactly. The share is steered
 *    like the centre: all of it for bass that is the same on both sides,
 *    none for a bass line panned to one side — moving that would leave an
 *    inverted copy in the opposite speaker to cancel it in the fold, which
 *    is right on paper and wrong in a room. The low/high split is
 *    complementary (high = input − low), so the bands sum to the input.
 *  - **Centre**: a share of the mid, divided by CENTER_COEF so the fold puts
 *    back what the front pair gave up. The share follows the signal: high
 *    when left and right are correlated and balanced (a lead vocal), zero
 *    for a hard-panned part, which a static matrix would smear into the
 *    middle. Capped at [CENTRE_MAX] so a vocal keeps a little width.
 *  - **Ambience**: a share of the side signal, high when the two channels are
 *    decorrelated and balanced (reverb, room), zero for a hard pan, so
 *    panned instruments stay in front. It is spread over the six surround
 *    and height pairs with weights whose squares sum to one, each through
 *    its own delay and allpass so the copies are decorrelated, and the fold
 *    keeps the ambience's energy rather than stacking six copies of it.
 *    The delays (5–20 ms) are the precedence effect: the image stays
 *    anchored at the front and the room opens up behind and above it.
 *
 * Exactness: the bass and the centre fold back to the input sample for
 * sample. The ambience folds back at the same average power but not the
 * same waveform — the decorrelation is the point — so a sustained tone that
 * is also decorrelated can pick up a little comb colour in the fold. That
 * is the trade every upmixer makes; it is kept to the content that is
 * already diffuse, where it is least audible.
 *
 * [enabled] crossfades over [FADE_MS] between the upmix and a plain
 * FL/FR copy of the input, which folds back to the input exactly — so the
 * switch can turn off mid-track without a format change.
 *
 * Pure Kotlin with no Android types, so it is unit tested on the JVM.
 */
class StereoUpmixer(sampleRate: Int) {

    @Volatile
    var enabled: Boolean = true

    private val sr = sampleRate.toFloat()

    // ── Bass split: 2nd-order Butterworth low-pass per side ────────────────
    private val lpL = Biquad.lowPass(sr, CROSSOVER_HZ)
    private val lpR = Biquad.lowPass(sr, CROSSOVER_HZ)

    // ── Steering statistics ────────────────────────────────────────────────
    private val statCoef = onePole(STAT_MS)
    private val gainCoef = onePole(GAIN_MS)
    private val fadeStep = 1f / max(1f, FADE_MS * sr / 1000f)
    private var eLL = 0f
    private var eRR = 0f
    private var eLR = 0f
    private var lowLL = 0f
    private var lowRR = 0f
    private var lowLR = 0f
    private var centreTarget = 0f
    private var ambientTarget = 0f
    private var bassTarget = 0f
    private var centre = 0f
    private var ambient = 0f
    private var bass = 0f
    private var mix = if (enabled) 1f else 0f
    private var counter = 0

    // ── Ambience: one delay line, a tap and two allpasses per output ───────
    private val delayLen: Int
    private val delayLine: FloatArray
    private var delayPos = 0
    private val tap = IntArray(LAYERS)
    private val weight = FloatArray(LAYERS)
    private val apLeft = Array(LAYERS) { Allpass(0) }
    private val apRight = Array(LAYERS) { Allpass(0) }

    init {
        var norm = 0f
        for (w in LAYER_WEIGHTS) norm += w * w
        val scale = 1f / sqrt(norm)
        var longest = 1
        for (i in 0 until LAYERS) {
            weight[i] = LAYER_WEIGHTS[i] * scale
            tap[i] = (LAYER_DELAY_MS[i] * sr / 1000f).roundToInt().coerceAtLeast(1)
            longest = max(longest, tap[i])
            apLeft[i] = Allpass((ALLPASS_LEFT_MS[i] * sr / 1000f).roundToInt().coerceAtLeast(1))
            apRight[i] = Allpass((ALLPASS_RIGHT_MS[i] * sr / 1000f).roundToInt().coerceAtLeast(1))
        }
        delayLen = longest + 1
        delayLine = FloatArray(delayLen)
    }

    /** Clears every filter, delay and statistic: a seek, not a new song. */
    fun reset() {
        lpL.reset(); lpR.reset()
        eLL = 0f; eRR = 0f; eLR = 0f
        lowLL = 0f; lowRR = 0f; lowLR = 0f
        centreTarget = 0f; ambientTarget = 0f; bassTarget = 0f
        centre = 0f; ambient = 0f; bass = 0f
        mix = if (enabled) 1f else 0f
        counter = 0
        delayLine.fill(0f)
        delayPos = 0
        for (i in 0 until LAYERS) { apLeft[i].reset(); apRight[i].reset() }
    }

    /**
     * Upmixes [frames] frames of [left]/[right] into [out], interleaved
     * 16-channel, starting at index 0.
     */
    fun process(left: FloatArray, right: FloatArray, frames: Int, out: FloatArray) {
        val target = if (enabled) 1f else 0f
        for (n in 0 until frames) {
            val l = left[n]
            val r = right[n]
            val o = n * CHANNELS

            // Bands. Complementary, so lo + hi is the input exactly.
            val lLo = lpL.process(l)
            val rLo = lpR.process(r)
            val lHi = l - lLo
            val rHi = r - rLo

            // Steering, from the band the steering acts on.
            eLL += statCoef * (lHi * lHi - eLL)
            eRR += statCoef * (rHi * rHi - eRR)
            eLR += statCoef * (lHi * rHi - eLR)
            lowLL += statCoef * (lLo * lLo - lowLL)
            lowRR += statCoef * (rLo * rLo - lowRR)
            lowLR += statCoef * (lLo * rLo - lowLR)
            if (counter == 0) steer()
            counter = (counter + 1) and (STEER_EVERY - 1)
            centre += gainCoef * (centreTarget - centre)
            ambient += gainCoef * (ambientTarget - ambient)
            bass += gainCoef * (bassTarget - bass)

            // The LFE's share of the low mid; each front side gives it up.
            val toLfe = bass * 0.5f * (lLo + rLo)
            val mid = 0.5f * (lHi + rHi)
            val side = 0.5f * (lHi - rHi)
            val frontSide = sqrt(1f - ambient) * side
            val amb = sqrt(ambient) * side

            delayLine[delayPos] = amb

            // The upmix, then blended with the plain copy by [mix].
            if (mix != target) {
                mix = if (mix < target) minOf(target, mix + fadeStep) else maxOf(target, mix - fadeStep)
            }
            val m = mix
            val dry = 1f - m

            val frontMid = (1f - centre) * mid
            out[o + FL] = m * (lLo - toLfe + frontMid + frontSide) + dry * l
            out[o + FR] = m * (rLo - toLfe + frontMid - frontSide) + dry * r
            out[o + FC] = m * (centre * mid / DownmixProcessor.CENTER_COEF)
            out[o + LFE] = m * (toLfe / DownmixProcessor.LFE_COEF)
            for (i in 0 until LAYERS) {
                var read = delayPos - tap[i]
                if (read < 0) read += delayLen
                val d = delayLine[read] * weight[i]
                val ch = LAYER_CHANNEL[i]
                out[o + ch] = m * apLeft[i].process(d)
                out[o + ch + 1] = m * -apRight[i].process(d)
            }
            delayPos = if (delayPos + 1 == delayLen) 0 else delayPos + 1
        }
    }

    /** New bass, centre and ambience shares from the running statistics. */
    private fun steer() {
        bassTarget = share(lowLL, lowRR, lowLR)
        val power = eLL + eRR
        if (power < SILENCE) {
            centreTarget = 0f
            ambientTarget = 0f
            return
        }
        val corr = (eLR / sqrt(eLL * eRR + SILENCE)).coerceIn(-1f, 1f)
        // 0 balanced … 1 everything on one side: a hard pan is direct sound
        // in one speaker, never centre and never ambience.
        val balanced = 1f - abs(eLL - eRR) / power
        val positive = max(0f, corr)
        centreTarget = CENTRE_MAX * positive * balanced
        ambientTarget = AMBIENT_MAX * (1f - positive) * balanced
    }

    /** How shared a band is: correlated and balanced → 1, one-sided or unrelated → 0. */
    private fun share(ll: Float, rr: Float, lr: Float): Float {
        val power = ll + rr
        if (power < SILENCE) return 0f
        val corr = (lr / sqrt(ll * rr + SILENCE)).coerceIn(0f, 1f)
        return corr * (1f - abs(ll - rr) / power)
    }

    private fun onePole(ms: Float): Float = 1f - exp(-1f / (ms * 0.001f * sr))

    /** Schroeder allpass: flat magnitude, a phase that differs per length. */
    private class Allpass(length: Int) {
        private val buf = FloatArray(length.coerceAtLeast(1))
        private var pos = 0
        fun process(x: Float): Float {
            val delayed = buf[pos]
            val v = x + ALLPASS_G * delayed
            buf[pos] = v
            pos = if (pos + 1 == buf.size) 0 else pos + 1
            return delayed - ALLPASS_G * v
        }
        fun reset() { buf.fill(0f); pos = 0 }
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
            fun lowPass(sr: Float, hz: Float): Biquad {
                // Butterworth (Q = 1/√2) via the bilinear transform.
                val k = tan(PI * hz / sr).toFloat()
                val q = 0.70710678f
                val norm = 1f / (1f + k / q + k * k)
                val b0 = k * k * norm
                return Biquad(b0, 2f * b0, b0, 2f * (k * k - 1f) * norm, (1f - k / q + k * k) * norm)
            }
        }
    }

    companion object {
        const val CHANNELS = 16

        // 9.1.6 channel indices, FFmpeg order (see ChannelLayout).
        const val FL = 0
        const val FR = 1
        const val FC = 2
        const val LFE = 3
        const val BL = 4
        const val FLC = 6
        const val SL = 8
        const val TFL = 10
        const val TBL = 12
        const val TSL = 14

        /** Bass management crossover: the LFE convention. */
        const val CROSSOVER_HZ = 120f

        /** Most of a mid that may move to the centre speaker. */
        const val CENTRE_MAX = 0.7f

        /** Most of a side signal that may move to the surrounds and heights. */
        const val AMBIENT_MAX = 0.8f

        private const val STAT_MS = 80f
        private const val GAIN_MS = 60f
        private const val FADE_MS = 60f
        private const val STEER_EVERY = 32 // power of two
        private const val SILENCE = 1e-9f
        private const val ALLPASS_G = 0.5f

        // The six ambience pairs, in order: front wide, side, rear, top
        // front, top side, top rear. Weights are normalised so their squares
        // sum to one. Delays rise with distance from the front stage; the
        // allpass lengths are all different, left and right included.
        private const val LAYERS = 6
        private val LAYER_CHANNEL = intArrayOf(FLC, SL, BL, TFL, TSL, TBL)
        private val LAYER_WEIGHTS = floatArrayOf(0.45f, 0.50f, 0.45f, 0.40f, 0.28f, 0.33f)
        private val LAYER_DELAY_MS = floatArrayOf(5f, 10f, 18f, 7f, 12f, 20f)
        private val ALLPASS_LEFT_MS = floatArrayOf(1.31f, 2.27f, 3.53f, 1.79f, 2.93f, 4.13f)
        private val ALLPASS_RIGHT_MS = floatArrayOf(1.61f, 2.71f, 3.97f, 2.11f, 3.29f, 4.67f)
    }
}
