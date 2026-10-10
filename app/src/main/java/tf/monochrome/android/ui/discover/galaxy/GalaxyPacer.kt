package tf.monochrome.android.ui.discover.galaxy

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The map's frame pacing at rest. While nobody is touching it and no camera
 * move or animation is under way, the galaxy moves on every other vsync of a
 * fast display — 60 times a second on a 120 Hz screen — and on every one again
 * the moment a finger is down.
 *
 * At rest only slow things move: the turn, the twinkle, the drifting dust and
 * the comets. They look the same at 60 frames a second as at 120, and every
 * frame is every pass of the GPU. A vsync let pass changes nothing the galaxy
 * draws, so nothing is redrawn and the GPU does nothing for it; the next one
 * moves everything by both vsyncs' time, so every speed stays what it was.
 *
 * Displays at 90 Hz or less are left alone: half of 90 is 45, which is visibly
 * slower, and at 60 there is nothing to halve.
 *
 * Main thread only: the clock and the pointer input both run there.
 */
@Stable
internal class GalaxyPacer {
    /**
     * Whether the map is at rest: what the layers that slow down further at
     * rest watch (the gas). Snapshot state, written only when it changes.
     */
    var idle by mutableStateOf(false)
        private set

    private var lastVsync = 0L
    private var vsync = 0L
    private var lastActive = 0L
    private var lastTouch = 0L
    private var down = false
    private var held = false

    /** A pointer event anywhere on the map at [now] (System.nanoTime), [pressed] if a finger is still down. */
    fun touch(pressed: Boolean, now: Long = System.nanoTime()) {
        lastTouch = now
        down = pressed
    }

    /**
     * Called once a vsync with its time, [frameNanos] (System.nanoTime's
     * clock, as the frame clock gives it). [moving]: a camera move or another
     * animation is under way. True means let this vsync pass, moving nothing.
     */
    fun hold(frameNanos: Long, moving: Boolean): Boolean {
        val dt = frameNanos - lastVsync
        if (lastVsync != 0L && dt in 1..MAX_VSYNC_NS) vsync = if (vsync == 0L) dt else (vsync * 7 + dt) / 8
        lastVsync = frameNanos
        if (moving || down || lastActive == 0L) lastActive = frameNanos
        val resting = frameNanos - maxOf(lastActive, lastTouch) > IDLE_AFTER_NS
        if (idle != resting) idle = resting
        val fast = vsync in 1 until FAST_VSYNC_NS
        if (!resting || !fast) {
            held = false
            return false
        }
        held = !held
        return held
    }

    companion object {
        /** How long after the last touch or move the map counts as at rest. */
        const val IDLE_AFTER_NS = 1_000_000_000L

        /** A vsync shorter than this is a display faster than 100 Hz: one worth halving. */
        const val FAST_VSYNC_NS = 10_000_000L

        /** A gap longer than this is a stall, not a vsync, and is left out of the average. */
        const val MAX_VSYNC_NS = 50_000_000L
    }
}
