package tf.monochrome.android.ui.player

import kotlin.math.abs

/**
 * Soft focus ([tf.monochrome.android.domain.model.LyricsFxSettings.softFocus]):
 * how far out of focus and how dim a lyric line is for its distance from the
 * one being sung. The sung line is sharp and whole; each line further away is
 * a step softer, to [MAX_STEPS], past which every line is as soft as the
 * last. Kept apart from the drawing so it can be held to numbers.
 */
internal object LyricSoftFocus {
    /** Lines further from the sung one than this are no softer. */
    const val MAX_STEPS = 4

    /** A step's blur at full strength, as a sigma in dp. */
    const val BLUR_DP_PER_STEP = 1.5f

    /** What a step takes off a line's opacity at full strength. */
    const val DIM_PER_STEP = 0.13f

    /** Under this the setting is off: no layer is blurred at all. */
    const val OFF = 0.005f

    /**
     * How many steps [index] is from the sung line [current]. Before the
     * first line is sung ([current] under 0) the first line is the one
     * coming, a step away.
     */
    fun steps(index: Int, current: Int): Int =
        (if (current < 0) index + 1 else abs(index - current)).coerceIn(0, MAX_STEPS)

    /** The blur's sigma for a line [steps] away, in dp: none on the sung line. */
    fun blurDp(amount: Float, steps: Int): Float =
        if (amount < OFF) 0f else amount.coerceIn(0f, 1f) * BLUR_DP_PER_STEP * steps.coerceIn(0, MAX_STEPS)

    /** The line's opacity, over whatever its own colour has: 1 on the sung line, never under a third. */
    fun alpha(amount: Float, steps: Int): Float =
        if (amount < OFF) 1f else 1f - amount.coerceIn(0f, 1f) * DIM_PER_STEP * steps.coerceIn(0, MAX_STEPS)
}
