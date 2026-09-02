package tf.monochrome.android.audio.golden

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The vocabulary the roadmap's audio gates are written in.
 *
 * P0.4 asks for "max sample deviation <= 1 LSB @ 24-bit" and "no clipping on
 * 0 dBFS input with dither on"; P0.0 asks that a flag-off build be "byte
 * identical on golden fixtures". Those are three different questions and they
 * were being answered ad hoc, per test, with whatever the author reached for.
 * Naming them once means a gate can be quoted rather than re-derived, and that
 * two tests claiming "bit-identical" mean the same thing by it.
 */
object PcmCompare {

    /**
     * One least-significant bit at [bitDepth], for samples scaled to [-1, 1].
     *
     * A signed N-bit sample has 2^(N-1) steps between silence and full scale, so
     * the step is 2^-(N-1): 1/32768 at 16-bit, 1/8388608 at 24-bit. Deviations
     * are quoted in these because "1 LSB" is a number that survives a change of
     * output depth, and "0.0000001" is not.
     */
    fun lsb(bitDepth: Int): Double {
        require(bitDepth in 2..32) { "bit depth $bitDepth is outside 2..32" }
        return 1.0 / (1L shl (bitDepth - 1)).toDouble()
    }

    /** Largest absolute difference between two equal-length signals. */
    fun maxAbsDeviation(a: FloatArray, b: FloatArray): Double {
        requireSameLength(a, b)
        var worst = 0.0
        for (i in a.indices) {
            val d = abs(a[i].toDouble() - b[i].toDouble())
            if (d > worst) worst = d
        }
        return worst
    }

    /** [maxAbsDeviation] expressed in LSBs at [bitDepth] — the form the gates use. */
    fun maxDeviationLsb(a: FloatArray, b: FloatArray, bitDepth: Int): Double =
        maxAbsDeviation(a, b) / lsb(bitDepth)

    /** The frame index of the largest deviation, for a failure that says where. */
    fun worstIndex(a: FloatArray, b: FloatArray): Int {
        requireSameLength(a, b)
        var worst = -1.0
        var at = 0
        for (i in a.indices) {
            val d = abs(a[i].toDouble() - b[i].toDouble())
            if (d > worst) { worst = d; at = i }
        }
        return at
    }

    /**
     * Bit-identity, compared on raw bit patterns rather than with `==`.
     *
     * `==` calls NaN unequal to itself and -0.0 equal to 0.0, and this is the
     * assertion behind "the flag-off path is unchanged" — where a chain that
     * started emitting -0.0, or turned a NaN into a different NaN, has changed
     * even though float equality would shrug. Raw bits is the strict reading,
     * and strict is the point of the gate.
     */
    fun isBitIdentical(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].toRawBits() != b[i].toRawBits()) return false
        }
        return true
    }

    /** The first frame where two signals stop being bit-identical, or -1. */
    fun firstDifference(a: FloatArray, b: FloatArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i].toRawBits() != b[i].toRawBits()) return i
        }
        return if (a.size == b.size) -1 else n
    }

    /** Peak level in dBFS. Silence is negative infinity, not an error. */
    fun peakDbfs(x: FloatArray): Double {
        var peak = 0.0
        for (v in x) {
            val a = abs(v.toDouble())
            if (a > peak) peak = a
        }
        return if (peak == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(peak)
    }

    fun rmsDbfs(x: FloatArray): Double {
        if (x.isEmpty()) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v.toDouble()
        val rms = sqrt(sum / x.size)
        return if (rms == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }

    /**
     * Samples outside [-ceiling, ceiling].
     *
     * Float PCM carries values past full scale happily; the clipping happens
     * later, at the integer sink, where it is inaudible in a unit test and very
     * audible on a device. Counting them here is how "no clipping on 0 dBFS
     * input with dither on" gets checked before the sink exists.
     */
    fun clippedCount(x: FloatArray, ceiling: Double = 1.0): Int {
        var n = 0
        for (v in x) if (abs(v.toDouble()) > ceiling) n++
        return n
    }

    /**
     * Amplitude of the [freqHz] component, by Goertzel over [from] onward.
     *
     * Skipping the head is not optional: every filter and resampler in this app
     * has a transient, and measuring across it reports the settling rather than
     * the response. Callers should start at least a few time constants in — half
     * the buffer is the convention the existing EQ tests use.
     */
    fun amplitudeAt(x: FloatArray, freqHz: Double, sampleRate: Int, from: Int = 0): Double {
        require(from in 0..x.size) { "from $from is outside 0..${x.size}" }
        val n = x.size - from
        if (n < 2) return 0.0
        val w = 2.0 * Math.PI * freqHz / sampleRate
        val c = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until x.size) {
            val s0 = x[i] + c * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt((s1 * s1 + s2 * s2 - c * s1 * s2).coerceAtLeast(0.0)) / (n / 2.0)
    }

    /** [amplitudeAt] in dB relative to full scale. */
    fun levelDbAt(x: FloatArray, freqHz: Double, sampleRate: Int, from: Int = 0): Double {
        val a = amplitudeAt(x, freqHz, sampleRate, from)
        return if (a == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(a)
    }

    private fun requireSameLength(a: FloatArray, b: FloatArray) {
        require(a.size == b.size) {
            "signals differ in length: ${a.size} vs ${b.size} frames"
        }
    }
}
