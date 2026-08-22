package tf.monochrome.android.audio

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Equal-temperament ratios for the speed / pitch controls.
 *
 * The controls used to round to two decimals before storing — `Math.round(v *
 * 100) / 100f` — which is a coarse grid to land a musical interval on. A
 * semitone ratio is `2^(n/12)`: +2 semitones is 1.122462, and 1.12 is 3.8 cents
 * flat of it. Over one octave the worst case is -9 semitones, where 0.594604
 * rounds to 0.59 — **13.5 cents** flat, against a just-noticeable difference of
 * roughly 2-3 cents for beating between simultaneous tones. Float32 carries
 * about seven significant digits, so storing the ratio itself costs well under
 * a thousandth of a cent; the whole error was the rounding.
 *
 * Speed and pitch are the same number whenever pitch rides the tempo
 * (preserve-pitch off), which is why these live together: a "+2 semitones"
 * request there *is* a speed of 1.122462.
 */
object PitchRatio {

    /** The range both speed sliders offer. */
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 3.0f

    /** Whole semitones that stay inside [MIN_SPEED]..[MAX_SPEED]. */
    val SEMITONE_RANGE: IntRange = -24..19

    /** Exact equal-temperament ratio for [semitones]. */
    fun ratioFor(semitones: Float): Float = 2f.pow(semitones / 12f)

    /** Exact equal-temperament ratio for a whole number of [semitones]. */
    fun ratioFor(semitones: Int): Float = 2f.pow(semitones / 12f)

    /** How many semitones [ratio] is, as a real number. */
    fun semitonesFor(ratio: Float): Float =
        if (ratio > 0f) 12f * log2(ratio) else 0f

    /** Interval from [from] to [to] in cents. */
    fun cents(from: Float, to: Float): Float =
        if (from > 0f && to > 0f) 1200f * log2(to / from) else 0f

    /** Nearest whole semitone to [ratio], clamped to [SEMITONE_RANGE]. */
    fun nearestSemitone(ratio: Float): Int =
        semitonesFor(ratio).roundToInt().coerceIn(SEMITONE_RANGE.first, SEMITONE_RANGE.last)

    /** How far [ratio] sits from the nearest whole semitone, in cents. */
    fun centsOffSemitone(ratio: Float): Float =
        cents(ratioFor(nearestSemitone(ratio)), ratio)

    /** True when [ratio] is within [toleranceCents] of a whole semitone. */
    fun isOnSemitone(ratio: Float, toleranceCents: Float = 1f): Boolean =
        abs(centsOffSemitone(ratio)) <= toleranceCents

    /**
     * Snaps [ratio] to the nearest exact semitone when it is already within
     * [toleranceCents] of one, and leaves it alone otherwise.
     *
     * For a continuous slider: a drag that lands near an interval gets the
     * interval exactly, while a deliberate in-between setting is preserved.
     */
    fun snap(ratio: Float, toleranceCents: Float = 8f): Float =
        if (isOnSemitone(ratio, toleranceCents)) {
            ratioFor(nearestSemitone(ratio)).coerceIn(MIN_SPEED, MAX_SPEED)
        } else {
            ratio
        }

    /**
     * Steps [ratio] by [delta] whole semitones, from the nearest semitone when
     * the current value is already an interval and from its real position
     * otherwise — so repeated presses walk the scale instead of drifting.
     */
    fun step(ratio: Float, delta: Int): Float {
        val base = if (isOnSemitone(ratio, 8f)) {
            nearestSemitone(ratio)
        } else {
            semitonesFor(ratio).roundToInt()
        }
        val next = (base + delta).coerceIn(SEMITONE_RANGE.first, SEMITONE_RANGE.last)
        return ratioFor(next).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** "+2" / "-5" / "0", for a semitone readout. */
    fun formatSemitones(semitones: Int): String =
        if (semitones > 0) "+$semitones" else semitones.toString()
}
