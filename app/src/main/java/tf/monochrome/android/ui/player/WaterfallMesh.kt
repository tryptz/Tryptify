package tf.monochrome.android.ui.player

import kotlin.math.sqrt

/**
 * The waterfall as one triangle mesh, for a single `Canvas.drawVertices`.
 *
 * Why not the obvious drawLines/drawPath per line: a stroke wider than a
 * hairline with round caps is not something the GPU renderer batches.
 * `drawLines` turns every segment into a stroked path of its own — 127 per
 * line, 49 lines, some six thousand paths a frame — and Ridgeline's ground
 * added 49 concave anti-aliased fills on paths that change every frame, so no
 * work could be reused. That, not the history or the arithmetic, is what held
 * the player at half its frame rate.
 *
 * A mesh is the one thing the renderer draws as-is: it uploads the triangles
 * and draws them in order, in one call. Each line becomes a ribbon a few
 * vertices across — a solid core, and on each side a one-pixel fringe whose
 * vertex colours fall to transparent — which is the anti-aliasing the paths
 * used to compute, done once here instead. Ridgeline's ground is a strip from
 * each point down to the line's baseline, laid just before its line so the
 * draw order is the same back-to-front painter's order as before.
 *
 * Colours are per vertex: the screen-space gradient the shader used to give
 * (bright at the top of the box, the base colour at the bottom) is evaluated
 * at each vertex's height, with the line's own fade folded into the alpha.
 *
 * Indices are 16-bit, so one mesh holds at most [MAX_VERTICES] vertices; the
 * caller draws and calls [reset] when [hasRoom] says a line no longer fits.
 * Nothing is allocated after construction.
 */
internal class WaterfallMesh(val maxVertices: Int = MAX_VERTICES) {
    val verts = FloatArray(maxVertices * 2)
    val colors = IntArray(maxVertices)
    val indices = ShortArray(maxVertices / VERTS_PER_POINT_MAX * INDICES_PER_SEGMENT_MAX)

    /** Vertices written so far (each is two floats in [verts]). */
    var vertexCount = 0
        private set
    var indexCount = 0
        private set

    private val px = FloatArray(MAX_POINTS)
    private val py = FloatArray(MAX_POINTS)

    fun reset() {
        vertexCount = 0
        indexCount = 0
    }

    /** Whether a line of [points] points, with a ground under it or not, still fits. */
    fun hasRoom(points: Int, withGround: Boolean): Boolean {
        val v = points * (RIBBON_VERTS + if (withGround) 2 else 0)
        val i = (points - 1) * (RIBBON_INDICES + if (withGround) 6 else 0)
        return vertexCount + v <= maxVertices && indexCount + i <= indices.size
    }

    /**
     * Reads the polyline at [offset] in [segs] — `count` segments of x0 y0 x1
     * y1, the native renderer's layout — into the point scratch. Returns the
     * number of points.
     */
    fun loadPoints(segs: FloatArray, offset: Int, segments: Int): Int {
        val points = (segments + 1).coerceAtMost(MAX_POINTS)
        for (j in 0 until points - 1) {
            px[j] = segs[offset + j * 4]
            py[j] = segs[offset + j * 4 + 1]
        }
        val last = offset + (points - 2) * 4
        px[points - 1] = segs[last + 2]
        py[points - 1] = segs[last + 3]
        return points
    }

    /** The ground under the loaded line: every point down to [baseline], in one flat colour. */
    fun addGround(points: Int, baseline: Float, argb: Int) {
        val base = vertexCount
        for (j in 0 until points) {
            put(px[j], py[j], argb)
            put(px[j], baseline, argb)
        }
        for (j in 0 until points - 1) {
            val a = base + j * 2
            quad(a, a + 1, a + 2, a + 3)
        }
    }

    /**
     * The loaded line as a ribbon [width] px across, coloured by [gradient] at
     * each vertex's height and faded to [alpha]. Narrower than a pixel, the
     * core vanishes and the fringe carries the line at reduced strength, which
     * is how an anti-aliased hairline looks anyway.
     */
    fun addRibbon(points: Int, width: Float, alpha: Float, gradient: VerticalGradient) {
        val half = (width * 0.5f - FRINGE * 0.5f).coerceAtLeast(0f)
        val strength = (alpha * width.coerceAtMost(1f).coerceAtLeast(0f)).coerceIn(0f, 1f)
        val outer = half + FRINGE
        val base = vertexCount
        for (j in 0 until points) {
            // Normal from the neighbours on either side: one per point, so
            // consecutive segments share their edge vertices and the ribbon
            // has no seams or gaps at the joins.
            val j0 = if (j > 0) j - 1 else 0
            val j1 = if (j < points - 1) j + 1 else points - 1
            var dx = px[j1] - px[j0]
            var dy = py[j1] - py[j0]
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-4f) {
                dx /= len
                dy /= len
            } else {
                dx = 1f
                dy = 0f
            }
            val nx = -dy
            val ny = dx
            val x = px[j]
            val y = py[j]
            val solid = gradient.argbAt(y, strength)
            val clear = solid and 0x00FFFFFF
            put(x + nx * outer, y + ny * outer, clear)
            put(x + nx * half, y + ny * half, solid)
            put(x - nx * half, y - ny * half, solid)
            put(x - nx * outer, y - ny * outer, clear)
        }
        for (j in 0 until points - 1) {
            val a = base + j * RIBBON_VERTS
            val b = a + RIBBON_VERTS
            for (k in 0 until RIBBON_VERTS - 1) quad(a + k, a + k + 1, b + k, b + k + 1)
        }
    }

    private fun put(x: Float, y: Float, argb: Int) {
        val v = vertexCount++
        verts[v * 2] = x
        verts[v * 2 + 1] = y
        colors[v] = argb
    }

    /** Two triangles over a quad whose sides are a–b (this point) and c–d (the next). */
    private fun quad(a: Int, b: Int, c: Int, d: Int) {
        val i = indexCount
        indices[i] = a.toShort(); indices[i + 1] = c.toShort(); indices[i + 2] = b.toShort()
        indices[i + 3] = b.toShort(); indices[i + 4] = c.toShort(); indices[i + 5] = d.toShort()
        indexCount = i + 6
    }

    companion object {
        /** 16-bit indices; kept a little under the limit. */
        const val MAX_VERTICES = 32_000
        /** The native renderer's points per line. */
        const val MAX_POINTS = 128
        /** Across a ribbon: fringe, core, core, fringe. */
        const val RIBBON_VERTS = 4
        const val RIBBON_INDICES = (RIBBON_VERTS - 1) * 6
        /** The anti-aliasing fringe each side, in px. */
        const val FRINGE = 1f
        private const val VERTS_PER_POINT_MAX = RIBBON_VERTS
        private const val INDICES_PER_SEGMENT_MAX = RIBBON_INDICES + 6
    }
}

/**
 * A colour that depends only on height, like the vertical LinearGradient the
 * line paint used to carry: [stops] colours at [positions] (0 = top of the box,
 * 1 = its bottom), linearly between them. Evaluated per vertex, so it costs a
 * few multiplies rather than an object.
 */
internal class VerticalGradient {
    private var stops = IntArray(0)
    private var positions = FloatArray(0)
    var height = 1f
        private set

    fun set(height: Float, stops: IntArray, positions: FloatArray) {
        this.height = height.coerceAtLeast(1f)
        this.stops = stops
        this.positions = positions
    }

    /** The colour at [y] with its alpha scaled by [alpha]. */
    fun argbAt(y: Float, alpha: Float): Int {
        val s = stops
        if (s.isEmpty()) return 0
        val t = (y / height).coerceIn(0f, 1f)
        val p = positions
        var k = 0
        while (k < p.size - 2 && t > p[k + 1]) k++
        val c = if (s.size == 1) s[0] else {
            val span = (p[k + 1] - p[k]).coerceAtLeast(1e-6f)
            lerpArgb(s[k], s[k + 1], ((t - p[k]) / span).coerceIn(0f, 1f))
        }
        val a = ((c ushr 24) * alpha.coerceIn(0f, 1f) + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (c and 0x00FFFFFF)
    }

    private fun lerpArgb(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
