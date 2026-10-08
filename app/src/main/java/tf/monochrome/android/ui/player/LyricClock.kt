package tf.monochrome.android.ui.player

import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Timing for synced lyrics at any playback speed.
 *
 * Two clocks meet here. The player reports position in song time, which runs
 * at the playback speed. The Bluetooth delay and the poll interval are wall
 * time. Mixing them unconverted put the lyrics out of step as soon as the
 * speed left 1×:
 *
 *  - The delay was subtracted from song time as it stood. 200 ms of headphone
 *    latency is 300 ms of song at 1.5×, so the lyrics ran 100 ms ahead (and
 *    lagged below 1×).
 *  - The position was read every 250 ms of wall time, so a highlight could
 *    only move in steps of 250 ms × speed: at 2×, half a second, skipping
 *    two or three Apple-timed syllables per step and landing each one late.
 *
 * [nextPollDelayMs] wakes the poll at the next moment the lyrics change —
 * a word starting or ending, a line starting — converted to wall time at the
 * current speed. The position is still read, never extrapolated: a guessed
 * position is what made the active line jitter at boundaries before.
 */
internal object LyricClock {
    /** The poll's interval when no lyric boundary is nearer — the progress bar's rate. */
    const val IDLE_POLL_MS = 250L

    /** Never faster than a frame; denser boundaries would not be seen anyway. */
    const val MIN_POLL_MS = 16L

    /** Wake just past a boundary, so the reading compares `>=` on the right side of it. */
    private const val LAND_AFTER_MS = 2L

    /** [delayMs] of wall-time output latency, as song time at [speed]. */
    fun delayInSongMs(delayMs: Float, speed: Float): Long = (delayMs * effective(speed)).roundToLong()

    /** The song position being heard: the reported one less the output latency in song time. */
    fun heardPositionMs(positionMs: Long, delayMs: Float, speed: Float): Long =
        positionMs - delayInSongMs(delayMs, speed)

    /** Wall-time milliseconds until the poll should read the position again. */
    fun nextPollDelayMs(positionMs: Long, speed: Float, delayMs: Float, lyrics: Lyrics?): Long {
        val lines = lyrics?.takeIf { it.isSynced }?.lines?.takeIf { it.isNotEmpty() } ?: return IDLE_POLL_MS
        val s = effective(speed)
        val heard = heardPositionMs(positionMs, delayMs, s)
        val next = nextBoundaryMs(lines, heard) ?: return IDLE_POLL_MS
        val wallMs = ceil((next - heard) / s.toDouble()).toLong() + LAND_AFTER_MS
        return wallMs.coerceIn(MIN_POLL_MS, IDLE_POLL_MS)
    }

    /** The first line start, word start or word end after [heardMs], or null past the last. */
    fun nextBoundaryMs(lines: List<LyricLine>, heardMs: Long): Long? {
        val current = lines.indexOfLast { it.timeMs <= heardMs }
        var next: Long? = null
        fun consider(t: Long) {
            val soonest = next
            if (t > heardMs && (soonest == null || t < soonest)) next = t
        }
        lines.getOrNull(current + 1)?.let { consider(it.timeMs) }
        lines.getOrNull(current)?.words?.forEach {
            consider(it.startMs)
            consider(it.endMs)
        }
        return next
    }

    private fun effective(speed: Float): Float = if (speed.isFinite() && speed > 0f) speed else 1f
}
