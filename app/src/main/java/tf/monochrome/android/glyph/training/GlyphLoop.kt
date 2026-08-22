// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.training

/**
 * A practice segment and the arithmetic that keeps it from drifting.
 *
 * The failure this is written against: looping by adding the segment length to
 * a running position. Do that and every wrap inherits the previous wrap's
 * rounding error, so the hundredth pass through a two-bar loop starts audibly
 * later than the first. Here a wrap is computed from the segment's own bounds
 * — `start + ((position - start) mod length)` — so every wrap is derived from
 * the same two numbers and the hundredth is exactly the first.
 *
 * `GlyphLoopTest` runs a thousand wraps and asserts the start position is
 * unchanged, which is the test that would have caught the naive version.
 */
data class GlyphLoopSegment(
    val startSeconds: Float,
    val endSeconds: Float,
) {
    init {
        require(endSeconds > startSeconds) { "a loop must have a positive length" }
    }

    val lengthSeconds: Float get() = endSeconds - startSeconds

    operator fun contains(seconds: Float): Boolean =
        seconds >= startSeconds && seconds < endSeconds

    /**
     * The position [seconds] maps to inside the loop.
     *
     * Exact for any number of passes, including a position far past the end —
     * which happens when the app is backgrounded and the audio runs on.
     */
    fun wrap(seconds: Float): Float {
        if (seconds < startSeconds) return startSeconds
        val elapsed = seconds - startSeconds
        val within = elapsed % lengthSeconds
        return startSeconds + within
    }

    /** How many complete passes have finished by [seconds]. */
    fun passesAt(seconds: Float): Int {
        if (seconds <= startSeconds) return 0
        return ((seconds - startSeconds) / lengthSeconds).toInt()
    }

    /** Clamp to a song of [durationSeconds], keeping at least [MINIMUM_SECONDS]. */
    fun coerceInto(durationSeconds: Float): GlyphLoopSegment {
        val end = endSeconds.coerceAtMost(durationSeconds)
        val start = startSeconds.coerceIn(0f, (end - MINIMUM_SECONDS).coerceAtLeast(0f))
        return GlyphLoopSegment(start, maxOf(end, start + MINIMUM_SECONDS))
    }

    companion object {
        /** Shorter than this is not a practice segment, it is a stutter. */
        const val MINIMUM_SECONDS = 1.0f
    }
}

/**
 * The count-in before a loop pass.
 *
 * Expressed in beats rather than seconds so it stays musical when the practice
 * speed changes: four beats of count-in is four beats at 0.6× too, which is the
 * point of practising slowly.
 */
data class GlyphCountIn(val beats: Int) {
    fun seconds(bpm: Float): Float = if (bpm <= 0f) 0f else beats * 60f / bpm

    companion object {
        val NONE = GlyphCountIn(0)
        val ONE_BAR = GlyphCountIn(4)
        val TWO_BARS = GlyphCountIn(8)
    }
}
