package tf.monochrome.android.performance

import android.view.Window
import androidx.compose.runtime.MonotonicFrameClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Settings › System › Performance › Frame rate limit: the most frames a second
 * anything in the app draws. The app's own screens and every animation in them
 * (Trypt Mania's playfield included) through [CappedFrameClock], which the
 * activity's whole composition runs on; the visualizer's render threads through
 * `ProjectMEngineRepository`, which reads [fps] for itself.
 *
 * With a limit set it also asks the display for the slowest refresh rate that
 * holds it ([applyTo]), since an LTPO panel kept at 120 Hz by the system's own
 * votes (Compose's among them) still had the window drawing at 120: the rate
 * alone, never a mode id, and none at all at Max, where the window holds no
 * vote (see MainActivity).
 */
object FrameRateLimit {
    /** 0: no limit, every refresh. */
    const val MAX = 0

    /** The chips under Max, in frames a second. */
    val CHOICES = listOf(30, 45, 60, 90, 120)

    /**
     * The limit in force, written from the setting on the main thread and
     * read by the frame clock and the visualizer's render threads.
     */
    @Volatile
    var fps: Int = MAX

    /** Frames [CappedFrameClock] let through, for the FPS counter to read and reset. */
    val composedFrames = AtomicInteger()

    /**
     * Asks the display, through [window], for the refresh rate [fps] needs
     * ([refreshRateFor]), or for nothing at Max. The preferred rate only: the
     * system keeps the resolution, and a touch or another window may still
     * raise it.
     */
    fun applyTo(window: Window, fps: Int) {
        val display = window.decorView.display ?: return
        val current = display.mode
        val rates = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }
        val wanted = refreshRateFor(fps, rates)
        val attributes = window.attributes
        if (attributes.preferredRefreshRate == wanted) return
        attributes.preferredRefreshRate = wanted
        window.attributes = attributes
    }

    /**
     * The slowest of the display's [rates] that is at least [fps], so the
     * panel refreshes no faster than the limit needs; the fastest when none
     * is; 0, no preference, at Max.
     */
    internal fun refreshRateFor(fps: Int, rates: List<Float>): Float {
        if (fps <= 0 || rates.isEmpty()) return 0f
        return rates.filter { it >= fps - 0.5f }.minOrNull() ?: rates.max()
    }
}

/**
 * Which display frames to keep at a limit. Each kept frame books the next one
 * an interval on, so a limit the display does not divide (90 on 120 Hz) still
 * averages out to it; a frame that comes a little early, within [SLACK_NANOS],
 * is kept rather than left for a whole vsync more. Main thread only.
 */
internal class FramePacer {
    private var nextDue = 0L

    /** Whether the frame at [frameNanos] is drawn at [fps] (0 or less: every one). */
    fun due(frameNanos: Long, fps: Int): Boolean {
        if (fps <= 0) {
            nextDue = 0L
            return true
        }
        val interval = 1_000_000_000L / fps
        if (nextDue != 0L && frameNanos < nextDue - SLACK_NANOS) return false
        // A stall (the screen was off, nothing asked for frames) starts afresh
        // rather than drawing a burst to catch up.
        nextDue = if (nextDue == 0L || frameNanos - nextDue > interval) frameNanos + interval else nextDue + interval
        return true
    }

    companion object {
        /** Under a quarter of a 120 Hz vsync: a frame's own jitter, never a whole one. */
        const val SLACK_NANOS = 2_000_000L
    }
}

/**
 * The display's frame clock with [FrameRateLimit] applied: a frame not due
 * runs nothing, so no recomposition, no animation step and no `withFrameNanos`
 * loop moves on it, and with nothing changed nothing is redrawn. Every
 * animation measures its own time between frames, so a slower clock moves
 * things by more each frame and at the same speed.
 *
 * A frame that is due runs inside the display's own frame callback, as it did
 * before, so what it changes is drawn on that same frame.
 */
internal class CappedFrameClock(private val display: MonotonicFrameClock) : MonotonicFrameClock {
    private val pacer = FramePacer()

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        while (true) {
            var ran = false
            var result: R? = null
            display.withFrameNanos { now ->
                if (pacer.due(now, FrameRateLimit.fps)) {
                    FrameRateLimit.composedFrames.incrementAndGet()
                    ran = true
                    result = onFrame(now)
                }
            }
            @Suppress("UNCHECKED_CAST")
            if (ran) return result as R
        }
    }
}
