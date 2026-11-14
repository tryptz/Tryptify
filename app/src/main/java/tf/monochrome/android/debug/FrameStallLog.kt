package tf.monochrome.android.debug

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference

/**
 * Says in the log when the app drew slowly, on which screen, and where the
 * time went. It runs only while the live log does ([LiveLog]), so that a
 * stutter someone reports can be read from where the log is read, with its
 * length, its place and its cause's side, instead of guessed at.
 *
 * Two things are watched, because a stutter has two places to come from:
 * - the gap between two frames on the main thread ([Choreographer]): a gap of
 *   [STALL_MS] or more is the app's own code holding the thread;
 * - what each frame cost, as the system measured it ([FrameMetrics]): the
 *   app's part (input, animation, layout, draw), the hand-over to the render
 *   thread (sync), the drawing commands (issue), the swap, and on Android 12
 *   and up the GPU. A second with frames over [SLOW_MS] in it is one line,
 *   with the average of each part over those slow frames. Much time in issue,
 *   swap or GPU with little in the app's part is the drawing itself (an
 *   effect, a blur, a shader), not the app's code.
 */
object FrameStallLog : Choreographer.FrameCallback {
    private const val TAG = "FrameStall"
    private const val STALL_MS = 120L
    private const val SLOW_MS = 16.0     // under 60 frames a second, on a display that shows 120 or 165
    private const val FEW = 4          // slow frames in a second that are worth a line

    /** The screen on show, as the navigation names it; set by the nav host. */
    @Volatile var screen: String = "?"

    /** The app's window, set by the activity: what the frame measurements are taken from. */
    @Volatile var window: WeakReference<Window>? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    /** The effects that cost the GPU, each counted while the log runs: which were at work in a slow second. */
    enum class Effect { LENS, SLAB, RAYS, CURVE }

    private val drawn = java.util.concurrent.atomic.AtomicIntegerArray(Effect.entries.size)
    private val lensPixels = java.util.concurrent.atomic.AtomicLong()

    /**
     * An effect was drawn or made anew just now: the live lens once for each
     * pane it draws (with the [pixels] it blurs and bends), the others once
     * for each time their effect is built again. Costs one check when the
     * log is off.
     */
    fun drew(effect: Effect, pixels: Long = 0L) {
        if (!running) return
        drawn.incrementAndGet(effect.ordinal)
        if (pixels > 0L) lensPixels.addAndGet(pixels)
    }
    private var lastNanos = 0L
    private var thread: HandlerThread? = null
    private var listening: Window? = null

    // One second of frames, summed on the metrics thread.
    private var secondStart = 0L
    private var frames = 0
    private var slow = 0
    private var worst = 0.0
    private val parts = DoubleArray(5)   // app, sync, issue, swap, gpu: milliseconds over the slow frames

    private val metrics = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
        fun ms(id: Int) = frame.getMetric(id) / 1e6
        val total = ms(FrameMetrics.TOTAL_DURATION)
        val now = System.nanoTime()
        if (secondStart == 0L) secondStart = now
        frames++
        if (total >= SLOW_MS) {
            slow++
            worst = maxOf(worst, total)
            parts[0] += ms(FrameMetrics.INPUT_HANDLING_DURATION) + ms(FrameMetrics.ANIMATION_DURATION) +
                ms(FrameMetrics.LAYOUT_MEASURE_DURATION) + ms(FrameMetrics.DRAW_DURATION)
            parts[1] += ms(FrameMetrics.SYNC_DURATION)
            parts[2] += ms(FrameMetrics.COMMAND_ISSUE_DURATION)
            parts[3] += ms(FrameMetrics.SWAP_BUFFERS_DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) parts[4] += ms(FrameMetrics.GPU_DURATION)
        }
        if (now - secondStart >= 1_000_000_000L) {
            val counts = IntArray(Effect.entries.size) { drawn.getAndSet(it, 0) }
            val megapixels = lensPixels.getAndSet(0L) / 1_000_000.0
            if (slow >= FEW || worst >= STALL_MS) {
                fun avg(i: Int) = (parts[i] / slow).toInt()
                Log.i(
                    TAG,
                    "slow second on $screen: $frames frames, $slow over ${SLOW_MS.toInt()} ms, worst ${worst.toInt()} ms; " +
                        "slow frames average app ${avg(0)} sync ${avg(1)} issue ${avg(2)} swap ${avg(3)} gpu ${avg(4)} ms; " +
                        "drew lens ${counts[0]} (${"%.1f".format(java.util.Locale.US, megapixels)} Mpx) " +
                        "slab ${counts[1]} rays ${counts[2]} curve ${counts[3]}",
                )
            }
            secondStart = now
            frames = 0
            slow = 0
            worst = 0.0
            parts.fill(0.0)
        }
    }

    fun start() = main.post {
        if (running) return@post
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
        val target = window?.get() ?: return@post
        val worker = HandlerThread("FrameStallLog").also { it.start() }
        thread = worker
        listening = target
        secondStart = 0L
        target.addOnFrameMetricsAvailableListener(metrics, Handler(worker.looper))
    }

    fun stop() = main.post {
        running = false
        runCatching { listening?.removeOnFrameMetricsAvailableListener(metrics) }
        listening = null
        thread?.quitSafely()
        thread = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val gap = if (lastNanos == 0L) 0L else (frameTimeNanos - lastNanos) / 1_000_000
        lastNanos = frameTimeNanos
        if (gap >= STALL_MS) Log.i(TAG, "no frame for $gap ms on $screen")
        Choreographer.getInstance().postFrameCallback(this)
    }
}
