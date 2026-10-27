package tf.monochrome.android.performance

import android.content.Context
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout

/**
 * The activity's root view, holding every redraw to [FrameRateLimit].
 *
 * [CappedFrameClock] slows what the app animates, but not everything that
 * asks for a frame goes through it: a value read while drawing and written
 * from elsewhere (an audio tap, a player position), a TextureView's new
 * frame, a library redrawing its own layer. On a 120 Hz phone at a limit of
 * 30 the window still drew 120 frames a second (seen on device). Every one of
 * those reaches the window through here, a child telling its parent it needs
 * drawing again, so here is where a request that comes too soon is held: it
 * is passed on at the next display frame the limit allows, and the requests
 * that came meanwhile are drawn in that one frame. At Max everything passes
 * at once, as a plain FrameLayout would.
 */
class FrameLimitedLayout(context: Context) : FrameLayout(context) {
    // The frame clock's own pacing, which averages out to the limit on any
    // display rather than drifting from it.
    private val pacer = FramePacer()
    private var heldChild: View? = null
    private var heldTarget: View? = null

    private val release = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val child = heldChild ?: return
            val target = heldTarget ?: return
            // Timed by the same clock as a request let straight through.
            val now = clockNanos()
            if (!pacer.due(now, FrameRateLimit.fps)) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            heldChild = null
            heldTarget = null
            // In this frame's animation pass, so it is drawn in this frame.
            super@FrameLimitedLayout.onDescendantInvalidated(child, target)
        }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        val fps = FrameRateLimit.fps
        // Every invalidation in the window comes through here, so at Max the
        // clock is not even read.
        if (fps <= 0 || (heldChild == null && pacer.due(clockNanos(), fps))) {
            super.onDescendantInvalidated(child, target)
            return
        }
        // Already held: the frame it is held for draws this one too, since
        // everything invalidated stays marked dirty until it is drawn.
        if (heldChild == null) Choreographer.getInstance().postFrameCallback(release)
        heldChild = child
        heldTarget = target
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(release)
        heldChild = null
        heldTarget = null
        super.onDetachedFromWindow()
    }

    /** Uptime, in nanoseconds: a millisecond's precision is plenty for gaps of 8 ms and more. */
    private fun clockNanos(): Long = android.os.SystemClock.uptimeMillis() * 1_000_000L
}
