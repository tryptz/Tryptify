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

    // ── Timing at any speed ────────────────────────────────────────────────
    //
    // The blend length is a setting in heard time: "a 6 s crossfade" is six
    // seconds of overlap whatever the playback speed. The track's position and
    // duration are media time, which at 1.5x passes one and a half times as
    // fast. The functions below convert between the two, so the overlap lasts
    // as long as the setting says at every speed, and the outgoing tail holds
    // exactly enough media to last it.

    /** How long [mediaMs] of the track takes to hear at [speed]. */
    fun heardMs(mediaMs: Long, speed: Float): Long =
        if (speed <= 0f) mediaMs else (mediaMs / speed).toLong()

    /** How much of the track passes in [heardMs] at [speed]. */
    fun mediaMs(heardMs: Long, speed: Float): Long =
        if (speed <= 0f) heardMs else (heardMs * speed).toLong()

    /**
     * Whether to start preparing the tail: the track has more heard time left
     * than the blend (or it would be shorter than its own crossfade), and less
     * than the blend plus [leadMs] — the head start the tail player needs to
     * open, decode and seek before it has to sound.
     */
    fun shouldPrepare(positionMs: Long, durationMs: Long, crossfadeMs: Long, speed: Float, leadMs: Long): Boolean {
        if (crossfadeMs <= 0L || durationMs <= 0L) return false
        if (heardMs(durationMs, speed) <= crossfadeMs) return false
        val left = heardMs(durationMs - positionMs, speed)
        return left <= crossfadeMs + leadMs
    }

    /**
     * The media position where the blend begins: one crossfade of heard time
     * before the end. If the play head is already past it (a seek into the
     * last seconds), the blend begins [leadMs] of heard time from now instead,
     * so the tail still has its head start; null when that leaves too little
     * of the track to blend over.
     */
    fun fadeStartMs(positionMs: Long, durationMs: Long, crossfadeMs: Long, speed: Float, leadMs: Long): Long? {
        val ideal = durationMs - mediaMs(crossfadeMs, speed)
        val earliest = positionMs + mediaMs(leadMs, speed)
        val start = maxOf(ideal, earliest)
        return start.takeIf { heardMs(durationMs - it, speed) >= MIN_BLEND_MS }
    }

    /** Shortest blend worth running; less than this is a glitch, not a fade. */
    const val MIN_BLEND_MS = 500L

    private const val HALF_PI = (Math.PI / 2.0).toFloat()
}
