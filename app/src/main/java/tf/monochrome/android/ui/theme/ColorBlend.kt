package tf.monochrome.android.ui.theme

/**
 * How long the album-art colours take to cross over when the track changes.
 *
 * A plain number of milliseconds, set in Settings › Appearance, and nothing
 * else decides it.
 *
 * It used to default to matching "Blend Between Tracks", on the reasoning that
 * a colour fade the length of the audio blend ends on the last sample of the
 * outgoing track — the picture and the sound moving together. The argument is
 * sound and the result was not: a blend of four or six seconds is an ordinary
 * setting, and it made the default colour transition a four- or six-second
 * animation repainting the whole window on every track change. The slider's
 * first stop read "Match blend · 4.00 s", which is a long way from what the
 * left end of a slider is expected to cost.
 *
 * So the two are unhitched. The fade is [DEFAULT_MS] unless it is set, the
 * stops start at zero, and anyone who wants the old pairing can read their
 * blend and dial the same number in.
 */
object ColorBlend {

    /**
     * The fade when nothing has been chosen.
     *
     * Short enough that a track change costs a few frames rather than seconds
     * of continuous repainting, long enough that the colour arrives rather than
     * snapping — near the 600ms gapless playback always used, for the same
     * reason: a hard cut to a new colour is jarring next to audio that gave no
     * seam at all.
     *
     * On the [STEP_MS] grid deliberately, so it is a stop the slider can rest
     * at. 600 was not, and the thumb fell back to the stop before it: an
     * untouched install read "Instant" while the app was fading.
     */
    const val DEFAULT_MS = 500

    /**
     * Top of the slider's travel. Eight seconds is already a slow dissolve
     * across a whole screen; past it the colour is still arriving when the next
     * track starts on anything short.
     */
    const val MAX_MS = 8_000

    /** Slider granularity, and so the values [millisFor] can be handed. */
    const val STEP_MS = 250

    /**
     * The stored preference as a duration that can actually be animated.
     *
     * Guards two things. Older builds stored `-1` for "match blend", which is
     * not a length and would be handed straight to an animation spec; it reads
     * as [DEFAULT_MS] now, which is what those listeners were getting on a
     * gapless queue anyway. And a value past [MAX_MS] — a hand-edited pref, or
     * a future build with a longer track — is clamped rather than trusted.
     *
     * Zero is left alone: it is a real choice and it means instant.
     */
    fun millisFor(storedMs: Int): Int =
        if (storedMs < 0) DEFAULT_MS else storedMs.coerceAtMost(MAX_MS)

    /** Every position the slider can rest at, instant first. */
    val steps: List<Int> = (0..MAX_MS step STEP_MS).toList()
}
