package tf.monochrome.android.ui.mixer.fxchain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure math behind the per-snapin visualizations in [FxVisual].
 *
 * Everything here is a deterministic function of the snapin's parameter values
 * so the displays track the *actual* DSP. Formulas mirror the
 * native processors in `cpp/dsp/snapins` (RBJ biquads, the distortion
 * waveshaper cases, the trance-gate pattern table, the dynamics transfer
 * curve, …) — keep them in sync when the native side changes.
 *
 * No Android/Compose imports: this object is exercised by plain JVM unit tests.
 */
internal object FxVisualMath {

    const val SAMPLE_RATE = 48000f
    const val MIN_FREQ = 20f
    const val MAX_FREQ = 20000f

    /** Matches `BiquadType` usage across the native snapins. */
    enum class BiquadShape { LOWPASS, HIGHPASS, BANDPASS, NOTCH, LOWSHELF, PEAK, HIGHSHELF }

    /** FilterProcessor type map: 0=LP, 1=BP, 2=HP, 3=Notch, 4=LowShelf, 5=Peak, 6=HighShelf. */
    val FILTER_TYPE_MAP = listOf(
        BiquadShape.LOWPASS, BiquadShape.BANDPASS, BiquadShape.HIGHPASS,
        BiquadShape.NOTCH, BiquadShape.LOWSHELF, BiquadShape.PEAK, BiquadShape.HIGHSHELF
    )

    /** NonlinearFilterProcessor type map: 0=LP, 1=BP, 2=HP, 3=Notch. */
    val NONLINEAR_TYPE_MAP = listOf(
        BiquadShape.LOWPASS, BiquadShape.BANDPASS, BiquadShape.HIGHPASS, BiquadShape.NOTCH
    )

    // ── Frequency axis (log-spaced 20 Hz .. 20 kHz) ─────────────────────────

    /** Frequency at normalized position [t] in 0..1 on the log axis. */
    fun freqAt(t: Float): Float = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(t)

    /** Normalized log-axis position of [freq]. */
    fun freqToT(freq: Float): Float =
        (ln(freq.coerceIn(MIN_FREQ, MAX_FREQ) / MIN_FREQ) / ln(MAX_FREQ / MIN_FREQ))

    fun dbToLin(db: Float): Float = 10f.pow(db / 20f)

    fun linToDb(lin: Float): Float = if (lin > 1e-10f) 20f * log10(lin) else -200f

    // ── Biquad magnitude response (RBJ cookbook) ────────────────────────────

    /**
     * Magnitude response in dB of one RBJ biquad at [freq], for a filter with
     * center/corner [f0], quality [q] and (shelf/peak only) [gainDb].
     */
    fun biquadDb(shape: BiquadShape, freq: Float, f0: Float, q: Float, gainDb: Float = 0f): Float {
        val fs = SAMPLE_RATE
        val w0 = 2f * Math.PI.toFloat() * (f0.coerceIn(10f, fs * 0.49f) / fs)
        val cw = cos(w0)
        val sw = sin(w0)
        val qq = q.coerceAtLeast(0.05f)
        val alpha = sw / (2f * qq)
        val a = 10f.pow(gainDb / 40f)
        val sqrtA = sqrt(a)

        val b0: Float; val b1: Float; val b2: Float
        val a0: Float; val a1: Float; val a2: Float
        when (shape) {
            BiquadShape.LOWPASS -> {
                b0 = (1f - cw) / 2f; b1 = 1f - cw; b2 = (1f - cw) / 2f
                a0 = 1f + alpha; a1 = -2f * cw; a2 = 1f - alpha
            }
            BiquadShape.HIGHPASS -> {
                b0 = (1f + cw) / 2f; b1 = -(1f + cw); b2 = (1f + cw) / 2f
                a0 = 1f + alpha; a1 = -2f * cw; a2 = 1f - alpha
            }
            BiquadShape.BANDPASS -> {
                b0 = alpha; b1 = 0f; b2 = -alpha
                a0 = 1f + alpha; a1 = -2f * cw; a2 = 1f - alpha
            }
            BiquadShape.NOTCH -> {
                b0 = 1f; b1 = -2f * cw; b2 = 1f
                a0 = 1f + alpha; a1 = -2f * cw; a2 = 1f - alpha
            }
            BiquadShape.PEAK -> {
                b0 = 1f + alpha * a; b1 = -2f * cw; b2 = 1f - alpha * a
                a0 = 1f + alpha / a; a1 = -2f * cw; a2 = 1f - alpha / a
            }
            BiquadShape.LOWSHELF -> {
                b0 = a * ((a + 1f) - (a - 1f) * cw + 2f * sqrtA * alpha)
                b1 = 2f * a * ((a - 1f) - (a + 1f) * cw)
                b2 = a * ((a + 1f) - (a - 1f) * cw - 2f * sqrtA * alpha)
                a0 = (a + 1f) + (a - 1f) * cw + 2f * sqrtA * alpha
                a1 = -2f * ((a - 1f) + (a + 1f) * cw)
                a2 = (a + 1f) + (a - 1f) * cw - 2f * sqrtA * alpha
            }
            BiquadShape.HIGHSHELF -> {
                b0 = a * ((a + 1f) + (a - 1f) * cw + 2f * sqrtA * alpha)
                b1 = -2f * a * ((a - 1f) + (a + 1f) * cw)
                b2 = a * ((a + 1f) + (a - 1f) * cw - 2f * sqrtA * alpha)
                a0 = (a + 1f) - (a - 1f) * cw + 2f * sqrtA * alpha
                a1 = 2f * ((a - 1f) - (a + 1f) * cw)
                a2 = (a + 1f) - (a - 1f) * cw - 2f * sqrtA * alpha
            }
        }

        val w = 2f * Math.PI.toFloat() * (freq.coerceIn(1f, fs * 0.499f) / fs)
        val numMag = complexPolyMag(b0, b1, b2, w)
        val denMag = complexPolyMag(a0, a1, a2, w)
        return linToDb(numMag / denMag.coerceAtLeast(1e-12f))
    }

    /** |c0 + c1·e^{-jw} + c2·e^{-2jw}|. */
    private fun complexPolyMag(c0: Float, c1: Float, c2: Float, w: Float): Float {
        val re = c0 + c1 * cos(w) + c2 * cos(2f * w)
        val im = c1 * sin(w) + c2 * sin(2f * w)
        return hypot(re, im)
    }

    /**
     * FilterProcessor response: the chosen biquad cascaded `slope + 1` times
     * (0=12 dB/oct … 3=48 dB/oct), matching `filter.h`.
     */
    fun filterDb(typeIndex: Int, freq: Float, cutoff: Float, q: Float, gainDb: Float, slope: Int): Float {
        val shape = FILTER_TYPE_MAP[typeIndex.coerceIn(0, FILTER_TYPE_MAP.size - 1)]
        val stages = slope.coerceIn(0, 3) + 1
        return biquadDb(shape, freq, cutoff, q, gainDb) * stages
    }

    /**
     * 4-pole ladder LP magnitude with resonance feedback:
     * |H| = 1 / |(1 + jx)^4 + k|, x = f/fc, k = 4·reso.
     */
    fun ladderDb(freq: Float, cutoff: Float, resoPct: Float): Float {
        val x = freq / cutoff.coerceAtLeast(10f)
        // (1 + jx)^2 = (1 - x²) + j·2x, squared again:
        val re2 = 1f - x * x
        val im2 = 2f * x
        val re4 = re2 * re2 - im2 * im2
        val im4 = 2f * re2 * im2
        val k = 3.9f * (resoPct / 100f).coerceIn(0f, 1f)
        return linToDb(1f / hypot(re4 + k, im4).coerceAtLeast(1e-6f))
    }

    /**
     * Comb filter response. The native comb mixes `dry·(1-m) + m·(dry ± delayed)`
     * with delay 1/cutoff s, which folds to H = 1 ± m·e^{-jθ}, θ = 2π·f/cutoff.
     */
    fun combDb(freq: Float, cutoff: Float, mixPct: Float, negativePolarity: Boolean): Float {
        val m = (mixPct / 100f).coerceIn(0f, 1f)
        val theta = 2f * Math.PI.toFloat() * freq / cutoff.coerceAtLeast(20f)
        val sign = if (negativePolarity) -1f else 1f
        val re = 1f + sign * m * cos(theta)
        val im = sign * m * sin(theta)
        return linToDb(hypot(re, im).coerceAtLeast(1e-6f))
    }

    /**
     * Formant filter response: two parallel band-passes at the interpolated
     * vowel formants (a/e/i/o/u corner map from `formant_filter.h`), plus the
     * low/high dry-blend floors.
     */
    fun formantDb(freq: Float, vowelX: Float, vowelY: Float, q: Float, lowsPct: Float, highsPct: Float): Float {
        val x = vowelX.coerceIn(0f, 1f)
        val y = vowelY.coerceIn(0f, 1f)
        // Corners: a(800,1200) e(400,2200) i(300,2800) u(350,700)
        val f1 = 800f * (1 - x) * (1 - y) + 400f * x * (1 - y) + 300f * x * y + 350f * (1 - x) * y
        val f2 = 1200f * (1 - x) * (1 - y) + 2200f * x * (1 - y) + 2800f * x * y + 700f * (1 - x) * y
        val bp1 = dbToLin(biquadDb(BiquadShape.BANDPASS, freq, f1, q, 0f))
        val bp2 = dbToLin(biquadDb(BiquadShape.BANDPASS, freq, f2, q, 0f))
        val body = (lowsPct / 100f) * 0.3f * dbToLin(biquadDb(BiquadShape.LOWPASS, freq, 300f, 0.707f))
        val air = (highsPct / 100f) * 0.3f * dbToLin(biquadDb(BiquadShape.HIGHPASS, freq, 4000f, 0.707f))
        return linToDb(((bp1 + bp2) * 0.5f + body + air).coerceAtLeast(1e-6f))
    }

    /**
     * Phaser response sketch: `stages/2` notches log-spread around the center
     * frequency; depth sets notch depth, feedback sharpens them.
     */
    fun phaserDb(freq: Float, stages: Int, centerHz: Float, depthPct: Float, feedbackPct: Float): Float {
        val notches = (stages.coerceIn(2, 12) / 2).coerceAtLeast(1)
        val depthDb = -30f * (depthPct / 100f).coerceIn(0f, 1f)
        val q = 1.5f + 4f * (abs(feedbackPct) / 100f).coerceIn(0f, 1f)
        var sum = 0f
        for (k in 0 until notches) {
            val spreadOct = (k - (notches - 1) / 2f) * 0.9f
            val fNotch = (centerHz * 2f.pow(spreadOct)).coerceIn(MIN_FREQ, MAX_FREQ)
            sum += biquadDb(BiquadShape.PEAK, freq, fNotch, q, depthDb)
        }
        return sum
    }

    // ── Dynamics transfer curves (input dB → output dB) ─────────────────────

    /** Classic soft-knee downward compressor. */
    fun compressorOutDb(inDb: Float, threshDb: Float, ratio: Float, kneeDb: Float, makeupDb: Float): Float {
        val r = ratio.coerceAtLeast(1f)
        val halfKnee = kneeDb / 2f
        val out = when {
            inDb < threshDb - halfKnee -> inDb
            inDb <= threshDb + halfKnee && kneeDb > 0f -> {
                val x = inDb - threshDb + halfKnee
                inDb + (1f / r - 1f) * x * x / (2f * kneeDb)
            }
            else -> threshDb + (inDb - threshDb) / r
        }
        return out + makeupDb
    }

    /** Brickwall limiter: input gain, hard ceiling, output gain. */
    fun limiterOutDb(inDb: Float, inGainDb: Float, threshDb: Float, outGainDb: Float): Float =
        min(inDb + inGainDb, threshDb) + outGainDb

    /**
     * Gate static curve: unity above threshold, `-range` below the tolerance
     * band, smooth quadratic blend inside the band.
     */
    fun gateOutDb(inDb: Float, threshDb: Float, toleranceDb: Float, rangeDb: Float): Float {
        val closedFloor = threshDb - toleranceDb.coerceAtLeast(0.01f)
        return when {
            inDb >= threshDb -> inDb
            inDb <= closedFloor -> inDb - rangeDb
            else -> {
                val t = (threshDb - inDb) / (threshDb - closedFloor)  // 0 at open, 1 at closed
                inDb - rangeDb * t * t
            }
        }
    }

    /**
     * Dual-threshold dynamics gain in dB (added to the input level), mirroring
     * `DynamicsProcessor::computeTransferGain`.
     */
    fun dynamicsGainDb(
        levelDb: Float, lowThreshDb: Float, lowRatio: Float,
        highThreshDb: Float, highRatio: Float, kneeDb: Float
    ): Float {
        val halfKnee = kneeDb * 0.5f
        val loR = lowRatio.coerceAtLeast(0.05f)
        val hiR = highRatio.coerceAtLeast(1f)
        return when {
            levelDb < lowThreshDb - halfKnee ->
                lowThreshDb + (levelDb - lowThreshDb) / loR - levelDb
            levelDb < lowThreshDb + halfKnee && kneeDb > 0f -> {
                val x = levelDb - lowThreshDb + halfKnee
                val t = x / kneeDb
                (1f / loR - 1f) * (1f - t) * (1f - t) * 0.5f * kneeDb
            }
            levelDb < highThreshDb - halfKnee -> 0f
            levelDb < highThreshDb + halfKnee && kneeDb > 0f -> {
                val x = levelDb - highThreshDb + halfKnee
                val t = x / kneeDb
                (1f / hiR - 1f) * t * t * 0.5f * kneeDb
            }
            else -> highThreshDb + (levelDb - highThreshDb) / hiR - levelDb
        }
    }

    /** Compactor: limiter-style curve whose strength scales with range% (0–200). */
    fun compactorOutDb(inDb: Float, threshDb: Float, rangePct: Float): Float {
        val excess = max(0f, inDb - threshDb)
        return inDb - excess * (rangePct / 100f).coerceIn(0f, 2f)
    }

    // ── Waveshapers ─────────────────────────────────────────────────────────

    /** DistortionProcessor::waveshape, types 0–5 (see `distortion.h`). */
    fun distortionShape(type: Int, x: Float): Float = when (type.coerceIn(0, 5)) {
        0 -> {  // Soft clip (cubic)
            val ax = abs(x)
            when {
                ax > 1.5f -> if (x > 0f) 1f else -1f
                ax > 1f -> {
                    val t = (3f - (2f - ax) * (2f - ax)) / 3f
                    if (x > 0f) t else -t
                }
                else -> x - (x * x * x) / 3f
            }
        }
        1 -> x.coerceIn(-1f, 1f)                       // Hard clip
        2 -> tanh(x)                                   // Tanh saturation
        3 -> sin(x * Math.PI.toFloat())                // Foldback (sine folder)
        4 -> abs(x)                                    // Full-wave rectify
        else -> if (x >= 0f) 1f - exp(-x) else -tanh(-x * 1.5f)  // Asymmetric tube
    }

    /** ShaperProcessor overflow handling: 0=Hold, 1=Repeat (wrap), 2=Mirror. */
    fun shaperOverflow(mode: Int, input: Float): Float = when (mode.coerceIn(0, 2)) {
        0 -> input.coerceIn(-1f, 1f)
        1 -> {
            var x = (input + 1f).mod(2f)
            if (x < 0f) x += 2f
            x - 1f
        }
        else -> {
            var x = input
            var guard = 0
            while ((x > 1f || x < -1f) && guard++ < 64) {
                if (x > 1f) x = 2f - x
                if (x < -1f) x = -2f - x
            }
            x
        }
    }

    /** Bit-depth quantization used by the Bitcrush display. */
    fun bitcrushQuantize(x: Float, bits: Float): Float {
        val levels = 2f.pow((bits.coerceIn(1f, 24f) - 1f))
        return (x * levels).let { Math.round(it) / levels }
    }

    /** Phase-distortion display curve: the signal modulating its own phase. */
    fun phaseDistortion(phase: Float, drivePct: Float, biasRad: Float): Float {
        val driveAmt = (drivePct / 100f) * Math.PI.toFloat()
        return sin(phase + driveAmt * sin(phase) + biasRad)
    }

    // ── Trance gate ─────────────────────────────────────────────────────────

    /** Pattern table mirroring `TranceGateProcessor::initDefaultPatterns`. */
    val TRANCE_PATTERNS: List<BooleanArray> = buildList {
        fun pattern(build: BooleanArray.() -> Unit) = add(BooleanArray(32).apply(build))
        pattern { for (s in 0 until 16 step 4) this[s] = true }              // four-on-the-floor
        pattern { for (s in 2 until 16 step 4) this[s] = true }              // offbeat
        pattern { for (s in 0 until 16 step 2) this[s] = true }              // 8th notes
        pattern { intArrayOf(0, 3, 6, 8, 11, 14).forEach { this[it] = true } }   // syncopated
        pattern { intArrayOf(0, 3, 6, 9, 12, 15).forEach { this[it] = true } }   // dotted
        pattern { for (s in 8 until 16) this[s] = true }                     // buildup
        pattern { intArrayOf(0, 1, 4, 5, 8, 9, 12, 13).forEach { this[it] = true } }  // stutter
        pattern { for (s in 0 until 32) this[s] = true }                     // all on
    }

    // ── Misc ────────────────────────────────────────────────────────────────

    /**
     * Reduce a waveform to per-bucket min/max pairs for scope drawing:
     * `out[2k] = min`, `out[2k+1] = max` of bucket k. Buckets cover [0, length)
     * evenly; empty buckets (length < buckets) collapse to 0..0.
     */
    fun waveMinMax(wave: FloatArray, length: Int, buckets: Int): FloatArray {
        val out = FloatArray(buckets * 2)
        val len = length.coerceIn(0, wave.size)
        if (len == 0 || buckets <= 0) return out
        for (k in 0 until buckets) {
            val start = (k.toLong() * len / buckets).toInt()
            val end = ((k + 1).toLong() * len / buckets).toInt()
            if (start >= end) continue
            var lo = wave[start]
            var hi = wave[start]
            for (i in start + 1 until end) {
                val v = wave[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            out[k * 2] = lo
            out[k * 2 + 1] = hi
        }
        return out
    }

    /** MIDI note number → frequency in Hz (A4 = 69 = 440 Hz). */
    fun midiToHz(note: Float): Float = 440f * 2f.pow((note - 69f) / 12f)

    /** Semitones → playback-rate ratio. */
    fun semitonesToRatio(semitones: Float): Float = 2f.pow(semitones / 12f)
}
