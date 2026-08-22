// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import kotlin.math.abs

/**
 * The song position, interpolated between audio-clock readings.
 *
 * The rule this exists to enforce: **the audio clock is authoritative and the
 * frame clock only fills the gaps**. A media player reports its position in
 * coarse steps — tens of milliseconds, and not on every frame — so drawing
 * straight from it makes the arrows stutter. The obvious fix, advancing the
 * position by the frame delta, is the bug this class prevents: frame deltas
 * accumulate error, and after two minutes the notes and the music disagree by
 * enough to be unplayable.
 *
 * So: [sync] takes a real reading and is the only thing that can set the
 * position outright. [positionAt] extrapolates from the last reading using the
 * monotonic system clock, never by accumulating. Any drift is bounded by the
 * time since the last sync rather than by the length of the song, and a reading
 * that disagrees with the extrapolation by more than [RESYNC_THRESHOLD_SECONDS]
 * snaps rather than glides, because that is a seek or a stall and pretending
 * otherwise would smear it over the next second of play.
 *
 * Not a composable, not a coroutine, no Android types: this is the mode's
 * definition of "now" and it is unit-tested as such.
 */
class GlyphClock {

    private var anchorSongSeconds = 0f
    private var anchorRealtimeNanos = 0L
    private var running = false

    /** Playback rate. 1.0 is normal; the clock must know, or it drifts by the ratio. */
    var speed: Float = 1f
        private set

    /** The last position handed in by the audio layer. */
    var lastSyncedSeconds: Float = 0f
        private set

    val isRunning: Boolean get() = running

    /**
     * Take an authoritative reading.
     *
     * @param songSeconds where the audio actually is.
     * @param realtimeNanos a monotonic timestamp for that reading —
     *   `System.nanoTime()`. Passed in rather than read here so tests can drive
     *   the clock without sleeping.
     */
    fun sync(songSeconds: Float, realtimeNanos: Long) {
        anchorSongSeconds = songSeconds
        anchorRealtimeNanos = realtimeNanos
        lastSyncedSeconds = songSeconds
    }

    /**
     * A reading that only moves the anchor if it disagrees materially.
     *
     * Media players jitter by a few milliseconds between polls; snapping to
     * every one of those makes the scroll visibly nervous. Small disagreements
     * are absorbed by re-anchoring at the extrapolated position, which keeps
     * the visual motion smooth while still refusing to let error accumulate.
     */
    fun syncSmooth(songSeconds: Float, realtimeNanos: Long) {
        if (!running) {
            sync(songSeconds, realtimeNanos)
            return
        }
        val extrapolated = positionAt(realtimeNanos)
        lastSyncedSeconds = songSeconds
        if (abs(songSeconds - extrapolated) >= RESYNC_THRESHOLD_SECONDS) {
            anchorSongSeconds = songSeconds
            anchorRealtimeNanos = realtimeNanos
        } else {
            // Keep the smooth position but re-anchor to it, so the next
            // extrapolation starts from now rather than from an old reading.
            anchorSongSeconds = extrapolated
            anchorRealtimeNanos = realtimeNanos
        }
    }

    fun start(realtimeNanos: Long) {
        anchorRealtimeNanos = realtimeNanos
        running = true
    }

    /** Freeze at the current position. The anchor keeps it for the resume. */
    fun pause(realtimeNanos: Long) {
        if (running) {
            anchorSongSeconds = positionAt(realtimeNanos)
            anchorRealtimeNanos = realtimeNanos
        }
        running = false
    }

    fun resume(realtimeNanos: Long) {
        anchorRealtimeNanos = realtimeNanos
        running = true
    }

    /**
     * Change the playback rate.
     *
     * The anchor is moved to the current position first: without that, every
     * elapsed nanosecond since the last sync would be re-interpreted at the new
     * rate and the position would jump.
     */
    fun setSpeed(newSpeed: Float, realtimeNanos: Long) {
        val safe = newSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (safe == speed) return
        anchorSongSeconds = positionAt(realtimeNanos)
        anchorRealtimeNanos = realtimeNanos
        speed = safe
    }

    /** Jump outright — a seek, a loop wrap, or a restart. */
    fun seekTo(songSeconds: Float, realtimeNanos: Long) {
        anchorSongSeconds = songSeconds
        anchorRealtimeNanos = realtimeNanos
        lastSyncedSeconds = songSeconds
    }

    /**
     * Where the song is at [realtimeNanos].
     *
     * Extrapolated from the anchor, never accumulated: calling this a thousand
     * times gives the same answer as calling it once.
     */
    fun positionAt(realtimeNanos: Long): Float {
        if (!running) return anchorSongSeconds
        val elapsed = (realtimeNanos - anchorRealtimeNanos) / NANOS_PER_SECOND
        return anchorSongSeconds + (elapsed * speed).toFloat()
    }

    fun reset() {
        anchorSongSeconds = 0f
        anchorRealtimeNanos = 0L
        lastSyncedSeconds = 0f
        running = false
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /**
         * Disagreement above this is treated as a real discontinuity. Below a
         * frame at 60 Hz, so ordinary jitter is smoothed and a seek is not.
         */
        const val RESYNC_THRESHOLD_SECONDS = 0.012f

        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 2.0f
    }
}
