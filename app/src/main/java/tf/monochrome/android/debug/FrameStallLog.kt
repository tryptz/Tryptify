package tf.monochrome.android.debug

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer

/**
 * Says in the log when the app stopped drawing for a moment, and on which
 * screen: a frame that came more than [STALL_MS] after the one before. It
 * runs only while the live log does ([LiveLog]), so that a stutter someone
 * reports can be seen from where the log is read, with its length and place,
 * instead of guessed at.
 */
object FrameStallLog : Choreographer.FrameCallback {
    private const val TAG = "FrameStall"
    private const val STALL_MS = 120L

    /** The screen on show, as the navigation names it; set by the nav host. */
    @Volatile var screen: String = "?"

    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var lastNanos = 0L

    fun start() = main.post {
        if (running) return@post
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() = main.post { running = false }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val gap = if (lastNanos == 0L) 0L else (frameTimeNanos - lastNanos) / 1_000_000
        lastNanos = frameTimeNanos
        if (gap >= STALL_MS) Log.i(TAG, "no frame for $gap ms on $screen")
        Choreographer.getInstance().postFrameCallback(this)
    }
}
