package tf.monochrome.android.audio.eq

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM DSP kernels for the EQ processors: RBJ biquads and the 2x/4x
 * channel resampler. Deliberately free of Android/media3 imports so response
 * and resampler behaviour can be iterated on and unit-tested on a plain JVM
 * without an Android toolchain in the loop.
 */

internal enum class EqBiquadType { PEAKING, LOW_SHELF, HIGH_SHELF }

/** RBJ biquad (Transposed Direct Form II) with NaN/degenerate-coefficient guards. */
internal class EqBiquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun configure(type: EqBiquadType, sr: Double, freq: Double, q: Double, gainDb: Double) {
        val w0 = 2.0 * Math.PI * freq / sr
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0 * q)
        var nb0: Double; var nb1: Double; var nb2: Double
        var na0: Double; var na1: Double; var na2: Double

        when (type) {
            EqBiquadType.PEAKING -> {
                val a = 10.0.pow(gainDb / 40.0)
                nb0 = 1.0 + alpha * a; nb1 = -2.0 * cosw0; nb2 = 1.0 - alpha * a
                na0 = 1.0 + alpha / a; na1 = -2.0 * cosw0; na2 = 1.0 - alpha / a
            }
            EqBiquadType.LOW_SHELF -> {
                val a = 10.0.pow(gainDb / 40.0)
                val sq = 2.0 * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cosw0 + sq)
                nb1 = 2.0 * a * ((a - 1) - (a + 1) * cosw0)
                nb2 = a * ((a + 1) - (a - 1) * cosw0 - sq)
                na0 = (a + 1) + (a - 1) * cosw0 + sq
                na1 = -2.0 * ((a - 1) + (a + 1) * cosw0)
                na2 = (a + 1) + (a - 1) * cosw0 - sq
            }
            EqBiquadType.HIGH_SHELF -> {
                val a = 10.0.pow(gainDb / 40.0)
                val sq = 2.0 * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cosw0 + sq)
                nb1 = -2.0 * a * ((a - 1) + (a + 1) * cosw0)
                nb2 = a * ((a + 1) + (a - 1) * cosw0 - sq)
                na0 = (a + 1) - (a - 1) * cosw0 + sq
                na1 = 2.0 * ((a - 1) - (a + 1) * cosw0)
                na2 = (a + 1) - (a - 1) * cosw0 - sq
            }
        }
        if (abs(na0) < 1e-20 || !na0.isFinite()) {
            // Degenerate coefficient — fall back to passthrough instead of emitting NaN/Inf audio.
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            z1 = 0f; z2 = 0f
            return
        }
        val nb0f = (nb0 / na0).toFloat()
        val nb1f = (nb1 / na0).toFloat()
        val nb2f = (nb2 / na0).toFloat()
        val na1f = (na1 / na0).toFloat()
        val na2f = (na2 / na0).toFloat()
        if (!nb0f.isFinite() || !nb1f.isFinite() || !nb2f.isFinite() ||
            !na1f.isFinite() || !na2f.isFinite()) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
        } else {
            b0 = nb0f; b1 = nb1f; b2 = nb2f; a1 = na1f; a2 = na2f
        }
        z1 = 0f; z2 = 0f
    }

    fun processBlock(data: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = data[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            data[i] = y
        }
    }
}

/**
 * One-channel 2x/4x resampler: zero-stuff + 8th-order Butterworth anti-image
 * low-pass up, matching filter + decimation down, both at 0.9 × the base
 * Nyquist (mirrors ChannelOversampler in the native DSP).
 *
 * Latency note: these are IIR cascades, not linear-phase FIRs — they add
 * frequency-dependent group delay rather than a fixed pipeline delay. In the
 * audio band it is ~40 µs per leg at 48 kHz base (Σ 1/(Qᵢ·ω_c) with
 * ω_c ≈ 2π·21.6 kHz), ~80 µs round trip: three orders of magnitude below A/V
 * sync thresholds, so nothing is (or needs to be) reported to the position
 * pipeline.
 */
internal class ChannelResampler {
    private val up = Array(STAGES) { LpBiquad() }
    private val down = Array(STAGES) { LpBiquad() }
    private var factor = 1

    fun prepare(baseRate: Double, factor: Int) {
        this.factor = factor.coerceIn(1, 4)
        if (this.factor <= 1) { reset(); return }
        val osRate = baseRate * this.factor
        val fc = 0.45 * baseRate
        for (i in 0 until STAGES) {
            up[i].configure(osRate, fc, BUTTERWORTH_Q[i])
            down[i].configure(osRate, fc, BUTTERWORTH_Q[i])
        }
        reset()
    }

    fun reset() {
        for (i in 0 until STAGES) { up[i].reset(); down[i].reset() }
    }

    /** [out] receives n * factor samples. */
    fun upsample(input: FloatArray, out: FloatArray, n: Int) {
        val gain = factor.toFloat()
        var k = 0
        for (i in 0 until n) {
            for (f in 0 until factor) {
                var s = if (f == 0) input[i] * gain else 0f
                for (b in 0 until STAGES) s = up[b].process(s)
                out[k++] = s
            }
        }
    }

    /** Consumes n * factor samples, writes n. */
    fun downsample(input: FloatArray, out: FloatArray, n: Int) {
        var k = 0
        for (i in 0 until n) {
            var keep = 0f
            for (f in 0 until factor) {
                var s = input[k++]
                for (b in 0 until STAGES) s = down[b].process(s)
                if (f == 0) keep = s
            }
            out[i] = keep
        }
    }

    private class LpBiquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun configure(sr: Double, freq: Double, q: Double) {
            val w0 = 2.0 * Math.PI * freq / sr
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = (((1.0 - cosw0) / 2.0) / a0).toFloat()
            b1 = ((1.0 - cosw0) / a0).toFloat()
            b2 = b0
            a1 = ((-2.0 * cosw0) / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
            z1 = 0f; z2 = 0f
        }

        fun reset() { z1 = 0f; z2 = 0f }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    companion object {
        private const val STAGES = 4
        private val BUTTERWORTH_Q = doubleArrayOf(0.50980, 0.60134, 0.89998, 2.56292)
    }
}
