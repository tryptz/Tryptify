package tf.monochrome.android.ui.discover.galaxy

import kotlin.math.hypot
import kotlin.math.min

/**
 * Dashes laid along a polyline as plain line segments, for one `drawLines`.
 *
 * A `DashPathEffect` looks the same and costs far more: HWUI cannot draw a
 * dashed path on the GPU, so it rasterises the stroke on the CPU and uploads
 * the mask every frame (the `GrGpu::writePixels` in the logs). The galaxy's
 * timeline spiral spans the screen, the way from "you are here" can run off
 * it, and every glass pane over the map was replaying both.
 *
 * The pattern runs on unbroken from one segment to the next, as a dashed
 * path's does, and only what is on screen (with a margin) becomes dashes: a
 * line to a point just past the camera is a long way off the edge.
 */
internal class Dasher(capacity: Int) {
    val out = FloatArray(capacity * 4)

    /** Floats written to [out]: four a dash. */
    var count = 0
        private set

    private var on = 1f
    private var period = 2f
    /** Where along the pattern the pen is, 0 until [period]; dashes are the first [on] of it. */
    private var pos = 0f
    private var hasLast = false
    private var lx = 0f
    private var ly = 0f
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f

    /** A new run of [onPx] dashes and [offPx] gaps, kept inside a [width] × [height] view with [margin]. */
    fun start(onPx: Float, offPx: Float, width: Float, height: Float, margin: Float) {
        count = 0
        on = onPx.coerceAtLeast(0.5f)
        period = on + offPx.coerceAtLeast(0f)
        pos = 0f
        hasLast = false
        minX = -margin; minY = -margin; maxX = width + margin; maxY = height + margin
    }

    /** The polyline breaks here (a point behind the camera); the pattern carries on. */
    fun lift() {
        hasLast = false
    }

    fun to(x: Float, y: Float) {
        if (hasLast) segment(lx, ly, x, y)
        lx = x; ly = y; hasLast = true
    }

    /** One straight line, dashed on its own. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
        lift(); to(x0, y0); to(x1, y1)
    }

    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len <= 1e-3f) return
        // Liang–Barsky: the part of the line inside the view, as lengths along it.
        var t0 = 0f
        var t1 = 1f
        fun clip(p: Float, q: Float): Boolean {
            if (p == 0f) return q >= 0f
            val r = q / p
            if (p < 0f) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
            return true
        }
        val visible = clip(-dx, x0 - minX) && clip(dx, maxX - x0) && clip(-dy, y0 - minY) && clip(dy, maxY - y0)
        if (!visible) {
            advance(len)
            return
        }
        val s0 = t0 * len
        val s1 = t1 * len
        advance(s0)
        var s = s0
        val ux = dx / len
        val uy = dy / len
        while (s < s1 - 1e-3f) {
            val dash = pos < on
            val step = min(if (dash) on - pos else period - pos, s1 - s)
            if (dash && count + 4 <= out.size) {
                out[count] = x0 + ux * s
                out[count + 1] = y0 + uy * s
                out[count + 2] = x0 + ux * (s + step)
                out[count + 3] = y0 + uy * (s + step)
                count += 4
            }
            s += step
            pos += step
            if (pos >= period - 1e-4f) pos = 0f
        }
        advance(len - s1)
    }

    private fun advance(by: Float) {
        if (by > 0f) pos = (pos + by) % period
    }
}
