package tf.monochrome.android.player

import kotlin.math.cos
import kotlin.math.sin

/**
 * The gain curves used to blend one track into the next.
 *
 * Equal-power (sine/cosine), not linear. Two tracks crossfading are
 * uncorrelated signals, so their powers add rather than their amplitudes: with
 * a linear fade both sit at 0.5 amplitude halfway through, which sums to about
 * -3 dB and is plainly audible as a dip in the middle of every transition.
 * Sine/cosine holds `out² + in² = 1` all the way across, so perceived loudness
 * stays flat.
 */
internal object CrossfadeRamp {

    /** Gain for the incoming track, 0 at the start of the blend and 1 at the end. */
    fun fadeIn(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * HALF_PI).toFloat()

    /** Gain for the outgoing track, 1 at the start of the blend and 0 at the end. */
    fun fadeOut(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * HALF_PI).toFloat()

    /**
     * How far through the blend we are, given how long it has been running.
     * Clamped, so a late tick can't overshoot and wrap the curve back up.
     */
    fun progress(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 1f
        return (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Whether the blend should begin: the track has a known duration and is
     * within [crossfadeMs] of the end.
     *
     * A duration shorter than the blend itself is refused — blending a 2s
     * interstitial over 12s would start it before it began.
     */
    fun shouldStart(positionMs: Long, durationMs: Long, crossfadeMs: Long): Boolean {
        if (crossfadeMs <= 0L) return false
        if (durationMs <= 0L) return false
        if (durationMs <= crossfadeMs) return false
        return durationMs - positionMs <= crossfadeMs
    }

    private const val HALF_PI = (Math.PI / 2.0).toFloat()
}
