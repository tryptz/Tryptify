package tf.monochrome.android.ui.discover.galaxy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import tf.monochrome.android.ui.components.rememberOnScreen
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * What the galaxy did since the last report, counted where it happens: the
 * clock, the draw blocks and the light passes. Read and reset every few
 * seconds by [GalaxyFrameStats].
 *
 * Main thread only. The draw blocks, the layer blocks and the clock all run
 * there, so plain fields are enough and a frame pays an increment, no lock.
 */
internal object GalaxyProbe {
    /** Vsyncs the clock saw, and how many of them it let pass without moving anything (idle pacing). */
    var vsyncs = 0
    var paced = 0

    /** Times each layer was actually drawn. A cached layer that was not redrawn counts nothing. */
    var skyDraws = 0
    var smokeDraws = 0
    var coreRays = 0
    var starRays = 0

    /** Light passes with a light up but nowhere on screen its rays could reach: skipped whole. */
    var raysUnreachable = 0

    /** The last light pass that ran, in the view's px, for the sample estimate in the report. */
    var lastLightX = 0f
    var lastLightY = 0f
    var lastLightReach = Float.POSITIVE_INFINITY
    var lastWidth = 0f
    var lastHeight = 0f

    /** The live lens's resolution on the map, as a divisor: 1 full, 2 half. */
    var lensDivisor = 1

    fun reset() {
        vsyncs = 0; paced = 0
        skyDraws = 0; smokeDraws = 0; coreRays = 0; starRays = 0; raysUnreachable = 0
    }

    fun light(spot: LightSpot, width: Float, height: Float) {
        lastLightX = spot.x; lastLightY = spot.y; lastLightReach = spot.reach
        lastWidth = width; lastHeight = height
    }
}

/**
 * One line in the debug log every [GalaxyFrameStats.PERIOD_MS] while the map is
 * on screen: how fast frames came, how long the main thread, the render thread
 * and the GPU spent on them, how many missed their deadline, and what the
 * galaxy drew. Phases of GPU work are judged by these numbers, before and after.
 *
 * The timings are Android's own (FrameMetrics), for the whole window; the map
 * is the whole window. GPU time and deadlines need Android 12, and the line
 * says "n/a" for them below that.
 */
@Composable
internal fun GalaxyFrameStats(describe: (seconds: Float) -> String) {
    val view = LocalView.current
    val onScreen = rememberOnScreen()
    val window = remember(view) { view.context.findActivity()?.window }
    val sink = remember { FrameSink() }
    val liveDescribe = rememberUpdatedState(describe)

    if (window != null && onScreen) {
        DisposableEffect(window) {
            val thread = HandlerThread("GalaxyFrameStats").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped -> sink.add(metrics, dropped) }
            val added = runCatching { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }.isSuccess
            onDispose {
                if (added) runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
                thread.quitSafely()
            }
        }
        LaunchedEffect(window) {
            sink.drain(null)
            GalaxyProbe.reset()
            var from = System.nanoTime()
            while (true) {
                delay(GalaxyFrameStats.PERIOD_MS)
                val now = System.nanoTime()
                val refresh = view.display?.refreshRate ?: 60f
                val frames = sink.drain(refresh)
                val seconds = (now - from) / 1e9f
                from = now
                Log.i(GalaxyFrameStats.TAG, frames.line(seconds, refresh) + " | " + liveDescribe.value(seconds))
                GalaxyProbe.reset()
            }
        }
    }
}

internal object GalaxyFrameStats {
    const val TAG = "GalaxyFx"
    const val PERIOD_MS = 5_000L

    /** Frames a report can hold: five seconds at 144 Hz, with room. */
    const val CAPACITY = 1024

    /**
     * What the clock and the passes did, as the report's tail: rates per
     * second over the [seconds] it covers, and the god rays' reads a pixel.
     */
    fun passes(seconds: Float, smokeOn: Boolean, raysOn: Boolean): String {
        val p = GalaxyProbe
        val s = seconds.coerceAtLeast(0.001f)
        fun rate(n: Int) = "%.0f/s".format(n / s)
        return buildString {
            append("clock ").append(rate(p.vsyncs - p.paced))
            if (p.paced > 0) append(" (paced ").append(rate(p.paced)).append(')')
            append(" | sky ").append(rate(p.skyDraws))
            append(" | smoke ").append(if (smokeOn) rate(p.smokeDraws) else "off")
            if (raysOn) {
                append(" | rays star ").append(rate(p.starRays)).append(" core ").append(rate(p.coreRays))
                if (p.raysUnreachable > 0) append(" unreachable ").append(rate(p.raysUnreachable))
                if (p.lastWidth > 0f) {
                    val reads = raySamplesPerPixel(
                        p.lastLightX / LIGHT_PROBE_SCALE, p.lastLightY / LIGHT_PROBE_SCALE,
                        p.lastLightReach / LIGHT_PROBE_SCALE,
                        ceil(p.lastWidth / LIGHT_PROBE_SCALE).toInt(), ceil(p.lastHeight / LIGHT_PROBE_SCALE).toInt(),
                    )
                    append(" reads %.1f of %d a px".format(reads, GalaxyLight.SAMPLES))
                }
            } else {
                append(" | rays off")
            }
            append(" | lens ").append(if (p.lensDivisor > 1) "1/${p.lensDivisor}" else "full")
        }
    }

    /** The report's sample estimate runs on a coarse grid: a point every this many px. */
    private const val LIGHT_PROBE_SCALE = 8f
}

/**
 * How many of the god rays' [GalaxyLight.SAMPLES] reads an average pixel of a
 * [width] × [height] view actually makes, for a light at ([lx], [ly]) whose
 * samples count only within [reach] of it — the shader's own arithmetic, with
 * the jitter at its mean. 32 everywhere is what the pass cost before it
 * skipped the samples that add nothing.
 */
internal fun raySamplesPerPixel(lx: Float, ly: Float, reach: Float, width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 0f
    val samples = GalaxyLight.SAMPLES
    var total = 0.0
    for (y in 0 until height) for (x in 0 until width) {
        val dist = hypot(x + 0.5f - lx, y + 0.5f - ly).coerceAtLeast(0.001f)
        val first = GalaxyLight.firstSample(dist, reach, 0.5f)
        total += (samples - first.coerceIn(0, samples))
    }
    return (total / (width.toDouble() * height)).toFloat()
}

/**
 * Frame timings as they arrive, on the stats thread, until a report drains
 * them on the main thread.
 */
internal class FrameSink(private val capacity: Int = GalaxyFrameStats.CAPACITY) {
    private val lock = Any()
    private val total = LongArray(capacity)
    private val ui = LongArray(capacity)
    private val rt = LongArray(capacity)
    private val gpu = LongArray(capacity)
    private val deadline = LongArray(capacity)
    private var count = 0
    private var dropped = 0

    fun add(m: FrameMetrics, droppedSince: Int) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val main = m.getMetric(FrameMetrics.ANIMATION_DURATION) +
            m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
            m.getMetric(FrameMetrics.DRAW_DURATION)
        val render = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
        val s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        add(
            totalNs = m.getMetric(FrameMetrics.TOTAL_DURATION),
            uiNs = main,
            rtNs = render,
            gpuNs = if (s) m.getMetric(FrameMetrics.GPU_DURATION) else -1L,
            deadlineNs = if (s) m.getMetric(FrameMetrics.DEADLINE) else -1L,
            droppedSince = droppedSince,
        )
    }

    fun add(totalNs: Long, uiNs: Long, rtNs: Long, gpuNs: Long, deadlineNs: Long, droppedSince: Int = 0) {
        synchronized(lock) {
            dropped += droppedSince
            if (count >= capacity) {
                dropped++
                return
            }
            total[count] = totalNs; ui[count] = uiNs; rt[count] = rtNs; gpu[count] = gpuNs; deadline[count] = deadlineNs
            count++
        }
    }

    /**
     * Everything since the last drain, summed up, and the sink emptied. A
     * frame with no deadline (before Android 12) is late if it took longer
     * than one refresh at [refreshHz]; null drains without summing.
     */
    fun drain(refreshHz: Float?): FrameSummary {
        synchronized(lock) {
            val n = count
            val lost = dropped
            count = 0
            dropped = 0
            if (refreshHz == null || n == 0) return FrameSummary(0, lost, 0f, 0f, 0f, 0f, 0f, 0f, null, null, 0f)
            val vsync = (1e9f / refreshHz.coerceAtLeast(1f)).toLong()
            var late = 0
            var hasGpu = true
            for (i in 0 until n) {
                val due = if (deadline[i] > 0L) deadline[i] else vsync
                if (total[i] > due) late++
                if (gpu[i] < 0L) hasGpu = false
            }
            return FrameSummary(
                frames = n,
                dropped = lost,
                totalP50 = percentileMs(total, n, 0.5f),
                totalP95 = percentileMs(total, n, 0.95f),
                uiP50 = percentileMs(ui, n, 0.5f),
                uiP95 = percentileMs(ui, n, 0.95f),
                rtP50 = percentileMs(rt, n, 0.5f),
                rtP95 = percentileMs(rt, n, 0.95f),
                gpuP50 = if (hasGpu) percentileMs(gpu, n, 0.5f) else null,
                gpuP95 = if (hasGpu) percentileMs(gpu, n, 0.95f) else null,
                lateShare = late.toFloat() / n,
            )
        }
    }
}

/** One report's frames: counts, and timings in ms as median and 95th percentile. */
internal data class FrameSummary(
    val frames: Int,
    val dropped: Int,
    val totalP50: Float,
    val totalP95: Float,
    val uiP50: Float,
    val uiP95: Float,
    val rtP50: Float,
    val rtP95: Float,
    val gpuP50: Float?,
    val gpuP95: Float?,
    val lateShare: Float,
) {
    /** The report's head, over [seconds], on a display refreshing at [refreshHz]. */
    fun line(seconds: Float, refreshHz: Float): String = buildString {
        val s = seconds.coerceAtLeast(0.001f)
        append("frames %.1f fps on %.0f Hz".format(frames / s, refreshHz))
        append(" | frame %.1f/%.1f ms".format(totalP50, totalP95))
        append(" | ui %.1f/%.1f".format(uiP50, uiP95))
        append(" | rt %.1f/%.1f".format(rtP50, rtP95))
        if (gpuP50 != null && gpuP95 != null) append(" | gpu %.1f/%.1f".format(gpuP50, gpuP95)) else append(" | gpu n/a")
        append(" | late %.1f%%".format(lateShare * 100f))
        if (dropped > 0) append(" | unreported ").append(dropped)
    }
}

/** The [p] quantile of the first [n] of [values] (ns), in ms. Nearest rank; sorts a copy. */
internal fun percentileMs(values: LongArray, n: Int, p: Float): Float {
    if (n <= 0) return 0f
    val sorted = values.copyOf(n).also { it.sort() }
    val rank = max(0, ceil(p.coerceIn(0f, 1f) * n).toInt() - 1)
    return sorted[rank.coerceAtMost(n - 1)] / 1e6f
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
