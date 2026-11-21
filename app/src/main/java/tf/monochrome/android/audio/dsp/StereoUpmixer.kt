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
 *  - **Bands.** What is above the bass is steered in [HI_BANDS] bands of
 *    its own (edges at [BAND_EDGES_HZ]), each with its own centre and
 *    ambience share: a voice in the middle and a wide pad or a reverb in
 *    another range are told apart, where one reading of the whole spectrum
 *    saw a half-correlated mix and sent half of each both ways. The bands
 *    are differences of low-passes of the input, so they add up to it
 *    exactly and cost no delay.
 *  - **Centre**: a share of each band's mid, divided by CENTER_COEF so the
 *    fold puts back what the front pair gave up. The share follows the
 *    signal: high when left and right are correlated and balanced (a lead
 *    vocal), zero for a hard-panned part, which a static matrix would smear
 *    into the middle. Capped at [CENTRE_MAX] so a vocal keeps a little width.
 *  - **Ambience**: a share of each band's side signal, high when the two
 *    channels are decorrelated and balanced (reverb, room), zero for a hard
 *    pan, so panned instruments stay in front. It is spread over the six
 *    surround and height pairs, each through its own delay and allpass so
 *    the copies are decorrelated. The delays (5–20 ms) are the precedence
 *    effect: the image stays anchored at the front and the room opens up
 *    behind and above it.
 *
 * Exactness: the fold gives the input back sample for sample (to a float's
 * rounding), ambience included. The decorrelated copies used to fold back
 * at the same power but as another waveform, which could comb a sustained
 * tone; and with the 16 channels going on to anything that adds them up (the
 * fold, a room on the mixer's master) that was a colour on the whole song.
 * Now what the six pairs carry is in two parts. The decorrelated copies have
 * their mean across the six taken off each, so on each side they add up to
 * nothing: they are there in a room and gone in the fold. And the front
 * pair gives up a small share of its side signal, undelayed, to the six in
 * equal parts, which the fold adds back; the share is the one that keeps
 * the sixteen channels' power what the stereo's was.
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

    // ── Bands: 2nd-order Butterworth low-passes of the input, per side ─────
    // lp[0] is the bass split; band b above it is lp[b + 1] − lp[b], and the
    // top one the input less the last. An edge the rate cannot hold is put
    // just under the one above it could be: those bands are then empty.
    private val lpL = Array(EDGES) { Biquad.lowPass(sr, edge(it)) }
    private val lpR = Array(EDGES) { Biquad.lowPass(sr, edge(it)) }
    private val loL = FloatArray(EDGES)
    private val loR = FloatArray(EDGES)

    private fun edge(i: Int): Float = minOf(BAND_EDGES_HZ[i], sr * MAX_EDGE_OF_RATE)

    // ── Steering statistics ────────────────────────────────────────────────
    private val statCoef = onePole(STAT_MS)
    private val gainCoef = onePole(GAIN_MS)
    private val fadeStep = 1f / max(1f, FADE_MS * sr / 1000f)
    private val eLL = FloatArray(HI_BANDS)
    private val eRR = FloatArray(HI_BANDS)
    private val eLR = FloatArray(HI_BANDS)
    private var lowLL = 0f
    private var lowRR = 0f
    private var lowLR = 0f
    // Per band: the centre's share of the mid; of the side, the amplitude
    // that is decorrelated ([spread]) and the share handed over as it is
    // ([given]). Each a target and where the gain is on its way to it.
    private val centreTarget = FloatArray(HI_BANDS)
    private val spreadTarget = FloatArray(HI_BANDS)
    private val givenTarget = FloatArray(HI_BANDS)
    private val centre = FloatArray(HI_BANDS)
    private val spread = FloatArray(HI_BANDS)
    private val given = FloatArray(HI_BANDS)
    private var bassTarget = 0f
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
    private val layerL = FloatArray(LAYERS)
    private val layerR = FloatArray(LAYERS)

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
        for (i in 0 until EDGES) { lpL[i].reset(); lpR[i].reset() }
        eLL.fill(0f); eRR.fill(0f); eLR.fill(0f)
        lowLL = 0f; lowRR = 0f; lowLR = 0f
        centreTarget.fill(0f); spreadTarget.fill(0f); givenTarget.fill(0f)
        centre.fill(0f); spread.fill(0f); given.fill(0f)
        bassTarget = 0f; bass = 0f
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

            // Bands. Complementary, so they add up to the input exactly.
            for (i in 0 until EDGES) {
                loL[i] = lpL[i].process(l)
                loR[i] = lpR[i].process(r)
            }
            val lLo = loL[0]
            val rLo = loR[0]
            lowLL += statCoef * (lLo * lLo - lowLL)
            lowRR += statCoef * (rLo * rLo - lowRR)
            lowLR += statCoef * (lLo * rLo - lowLR)

            // Each band above the bass: its statistics, and what its gains
            // (as they stand) send where.
            var frontMid = 0f   // stays in the front pair
            var toCentre = 0f   // goes to the centre speaker
            var frontSide = 0f  // stays in the front pair
            var handed = 0f     // side handed to the six pairs as it is
            var amb = 0f        // side to be decorrelated
            for (b in 0 until HI_BANDS) {
                val lB = (if (b == HI_BANDS - 1) l else loL[b + 1]) - loL[b]
                val rB = (if (b == HI_BANDS - 1) r else loR[b + 1]) - loR[b]
                eLL[b] += statCoef * (lB * lB - eLL[b])
                eRR[b] += statCoef * (rB * rB - eRR[b])
                eLR[b] += statCoef * (lB * rB - eLR[b])
                centre[b] += gainCoef * (centreTarget[b] - centre[b])
                spread[b] += gainCoef * (spreadTarget[b] - spread[b])
                given[b] += gainCoef * (givenTarget[b] - given[b])
                val mid = 0.5f * (lB + rB)
                val side = 0.5f * (lB - rB)
                toCentre += centre[b] * mid
                frontMid += mid - centre[b] * mid
                handed += given[b] * side
                frontSide += side - given[b] * side
                amb += spread[b] * side
            }
            if (counter == 0) steer()
            counter = (counter + 1) and (STEER_EVERY - 1)
            bass += gainCoef * (bassTarget - bass)

            // The LFE's share of the low mid; each front side gives it up.
            val toLfe = bass * 0.5f * (lLo + rLo)

            delayLine[delayPos] = amb

            // The upmix, then blended with the plain copy by [mix].
            if (mix != target) {
                mix = if (mix < target) minOf(target, mix + fadeStep) else maxOf(target, mix - fadeStep)
            }
            val m = mix
            val dry = 1f - m

            out[o + FL] = m * (lLo - toLfe + frontMid + frontSide) + dry * l
            out[o + FR] = m * (rLo - toLfe + frontMid - frontSide) + dry * r
            out[o + FC] = m * (toCentre / DownmixProcessor.CENTER_COEF)
            out[o + LFE] = m * (toLfe / DownmixProcessor.LFE_COEF)
            // The six pairs: each one's decorrelated copy less the mean of
            // the six, so a side's copies add up to nothing, and an equal
            // part of what the front pair handed over.
            var meanL = 0f
            var meanR = 0f
            for (i in 0 until LAYERS) {
                var read = delayPos - tap[i]
                if (read < 0) read += delayLen
                val d = delayLine[read] * weight[i]
                layerL[i] = apLeft[i].process(d)
                layerR[i] = apRight[i].process(d)
                meanL += layerL[i]
                meanR += layerR[i]
            }
            meanL *= ONE_LAYER
            meanR *= ONE_LAYER
            val part = handed * ONE_LAYER
            for (i in 0 until LAYERS) {
                val ch = LAYER_CHANNEL[i]
                out[o + ch] = m * (part + layerL[i] - meanL)
                out[o + ch + 1] = m * -(part + layerR[i] - meanR)
            }
            delayPos = if (delayPos + 1 == delayLen) 0 else delayPos + 1
        }
    }

    /** New bass, centre and ambience shares from the running statistics. */
    private fun steer() {
        bassTarget = share(lowLL, lowRR, lowLR)
        for (b in 0 until HI_BANDS) {
            val power = eLL[b] + eRR[b]
            if (power < SILENCE) {
                centreTarget[b] = 0f
                spreadTarget[b] = 0f
                givenTarget[b] = 0f
                continue
            }
            val corr = (eLR[b] / sqrt(eLL[b] * eRR[b] + SILENCE)).coerceIn(-1f, 1f)
            // 0 balanced … 1 everything on one side: a hard pan is direct
            // sound in one speaker, never centre and never ambience.
            val balanced = 1f - abs(eLL[b] - eRR[b]) / power
            val positive = max(0f, corr)
            centreTarget[b] = CENTRE_MAX * positive * balanced
            val ambient = AMBIENT_MAX * (1f - positive) * balanced
            spreadTarget[b] = sqrt(ambient)
            givenTarget[b] = givenFor(ambient)
        }
    }

    /** How shared a band is: correlated and balanced → 1, one-sided or unrelated → 0. */
    private fun share(ll: Float, rr: Float, lr: Float): Float {
        val power = ll + rr
        if (power < SILENCE) return 0f
        val corr = (lr / sqrt(ll * rr + SILENCE)).coerceIn(0f, 1f)
        return corr * (1f - abs(ll - rr) / power)
    }

    /**
     * The share of the side signal the front pair hands over undelayed, for
     * [ambient] of its power decorrelated: the one that leaves the sixteen
     * channels with the power the stereo had. The decorrelated copies, their
     * mean taken off, carry 5/6 of [ambient]; a share g leaves the front
     * (1 − g)² and puts g² / 6 in the six pairs; so
     * (1 − g)² + g² / 6 = 1 − 5/6 · ambient.
     */
    private fun givenFor(ambient: Float): Float {
        val decorrelated = ambient * (LAYERS - 1) / LAYERS
        val k = 1f + ONE_LAYER
        return (1f - sqrt(max(0f, 1f - k * decorrelated))) / k
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

        /**
         * Where the bands meet: the bass split, then about an octave and a
         * third apart up to the top band. Eight bands with the bass.
         */
        val BAND_EDGES_HZ = floatArrayOf(CROSSOVER_HZ, 250f, 630f, 1600f, 4000f, 8000f, 14000f)
        private const val EDGES = 7

        /** The bands above the bass, each steered on its own. */
        const val HI_BANDS = EDGES

        /** No edge above this share of the rate: a low-pass there is no filter. */
        private const val MAX_EDGE_OF_RATE = 0.45f

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
        private const val ONE_LAYER = 1f / LAYERS
        private val LAYER_CHANNEL = intArrayOf(FLC, SL, BL, TFL, TSL, TBL)
        private val LAYER_WEIGHTS = floatArrayOf(0.45f, 0.50f, 0.45f, 0.40f, 0.28f, 0.33f)
        private val LAYER_DELAY_MS = floatArrayOf(5f, 10f, 18f, 7f, 12f, 20f)
        private val ALLPASS_LEFT_MS = floatArrayOf(1.31f, 2.27f, 3.53f, 1.79f, 2.93f, 4.13f)
        private val ALLPASS_RIGHT_MS = floatArrayOf(1.61f, 2.71f, 3.97f, 2.11f, 3.29f, 4.67f)
    }
}
