package tf.monochrome.android.audio.golden

import java.util.Random

/**
 * Deterministic test signals, as float PCM in [-1, 1].
 *
 * Every audio test in this module grows its own sine loop, and they have
 * quietly drifted apart — different phase conventions, different amplitude
 * defaults, some in Double and some in Float. A golden fixture is only golden
 * if the signal that produced it is reproducible, so this is the one generator
 * they can share.
 *
 * ## Why StrictMath
 *
 * `Math.sin` is allowed 1 ulp of error and is free to differ between JVM
 * versions, platforms, and even between the interpreter and a JIT intrinsic on
 * the same machine. That is harmless for a tolerance test and fatal for a
 * bit-exact one: a fixture generated on one JDK would not compare equal on
 * another, and the failure would look like a DSP regression rather than a
 * changed transcendental. [StrictMath] is specified down to the bit — the same
 * fdlibm results everywhere, forever — so the fixtures these produce are
 * portable across every machine the suite will ever run on. That is the whole
 * reason the harness can assert bit-identity at all.
 *
 * Measurement code in [PcmCompare] uses plain `Math`: it reads signals rather
 * than defining them, and a 1 ulp difference in a magnitude is noise well below
 * any threshold worth asserting.
 */
object PcmSignals {

    /** A sine at [freqHz], sampled at [sampleRate], in radians of [phase] offset. */
    fun sine(
        frames: Int,
        freqHz: Double,
        sampleRate: Int,
        amplitude: Double = 1.0,
        phase: Double = 0.0,
    ): FloatArray {
        require(frames >= 0) { "frames must not be negative, was $frames" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        val w = 2.0 * StrictMath.PI * freqHz / sampleRate
        return FloatArray(frames) { i -> (amplitude * StrictMath.sin(w * i + phase)).toFloat() }
    }

    /** Full-scale sine — the input the clipping and dither gates are written against. */
    fun fullScaleSine(frames: Int, freqHz: Double, sampleRate: Int): FloatArray =
        sine(frames, freqHz, sampleRate, amplitude = 1.0)

    fun silence(frames: Int): FloatArray = FloatArray(frames)

    /** Constant offset, for testing DC blocking and quantisation bias. */
    fun dc(frames: Int, level: Double): FloatArray = FloatArray(frames) { level.toFloat() }

    /** A single unit sample, so a chain's impulse response can be read straight off. */
    fun impulse(frames: Int, at: Int = 0, amplitude: Double = 1.0): FloatArray {
        require(at in 0 until maxOf(frames, 1)) { "impulse at $at is outside 0..${frames - 1}" }
        return FloatArray(frames) { i -> if (i == at) amplitude.toFloat() else 0f }
    }

    /**
     * Exponential sweep from [fromHz] to [toHz] — one pass over the whole band,
     * which is what a resampler's or a shelf's error should be judged across
     * rather than at a handful of spot frequencies.
     *
     * The phase is the integral of the instantaneous frequency, not
     * `sin(2*pi*f(t)*t)`: the latter is the classic sweep bug, sweeping at twice
     * the intended rate and landing an octave out at the end.
     */
    fun logSweep(
        frames: Int,
        fromHz: Double,
        toHz: Double,
        sampleRate: Int,
        amplitude: Double = 1.0,
    ): FloatArray {
        require(fromHz > 0.0 && toHz > 0.0) { "sweep bounds must be positive" }
        require(frames > 1) { "a sweep needs more than one frame" }
        val duration = frames.toDouble() / sampleRate
        val ratio = StrictMath.log(toHz / fromHz)
        return FloatArray(frames) { i ->
            val t = i.toDouble() / sampleRate
            val phase = if (StrictMath.abs(ratio) < 1e-12) {
                2.0 * StrictMath.PI * fromHz * t
            } else {
                2.0 * StrictMath.PI * fromHz * duration / ratio *
                    (StrictMath.exp(t / duration * ratio) - 1.0)
            }
            (amplitude * StrictMath.sin(phase)).toFloat()
        }
    }

    /**
     * Reproducible white noise. [Random] is specified exactly — the same seed
     * gives the same sequence on every JVM — which is the only reason noise can
     * appear in a golden fixture at all.
     */
    fun whiteNoise(frames: Int, seed: Long, amplitude: Double = 1.0): FloatArray {
        val random = Random(seed)
        return FloatArray(frames) { (amplitude * (random.nextDouble() * 2.0 - 1.0)).toFloat() }
    }

    /** Channel-major to frame-major, the layout every AudioProcessor wants. */
    fun interleave(vararg channels: FloatArray): FloatArray {
        require(channels.isNotEmpty()) { "need at least one channel" }
        val frames = channels[0].size
        channels.forEachIndexed { i, c ->
            require(c.size == frames) { "channel $i has ${c.size} frames, channel 0 has $frames" }
        }
        val out = FloatArray(frames * channels.size)
        for (f in 0 until frames) {
            for (c in channels.indices) out[f * channels.size + c] = channels[c][f]
        }
        return out
    }

    fun deinterleave(interleaved: FloatArray, channels: Int): Array<FloatArray> {
        require(channels > 0) { "channels must be positive, was $channels" }
        require(interleaved.size % channels == 0) {
            "${interleaved.size} samples do not divide into $channels channels"
        }
        val frames = interleaved.size / channels
        return Array(channels) { c -> FloatArray(frames) { f -> interleaved[f * channels + c] } }
    }
}
