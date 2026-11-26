package tf.monochrome.android.ui.player

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.debug.FrameStallLog
import tf.monochrome.android.domain.model.Spectrogram3DSettings
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 3D spectrogram is drawn as one mesh with `drawVertices`, which Android
 * draws on the GPU from Android 10 on. Below that the view is not offered.
 */
internal val SPECTROGRAM_3D_SUPPORTED: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/**
 * The cover's 3D spectrogram: the music as a landscape, time sideways (the
 * newest at the right edge), pitch into the distance (bass at the front),
 * drawn as one lit mesh, back to front, every frame of the app's frame clock.
 * It moves by the time between frames, never by counting them, so it scrolls
 * at the same speed whatever the frame rate limit is.
 */
@Composable
internal fun Spectrogram3DOverlay(
    bins: () -> FloatArray,
    settings: Spectrogram3DSettings,
    /** The album's colour: the surface. */
    color: Color,
    /** The album's second colour: the light glowing through the ridges. */
    glow: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** A new value (the next song) clears the landscape. */
    resetKey: Any? = null,
) {
    val scene = remember { Spectrogram3DScene() }
    val paint = remember { android.graphics.Paint().apply { this.color = android.graphics.Color.WHITE } }
    var frame by remember { mutableIntStateOf(0) }
    val currentSettings by rememberUpdatedState(settings)
    val currentBins by rememberUpdatedState(bins)
    LaunchedEffect(resetKey) {
        scene.clear()
        frame++
    }
    // Paused, the landscape stands still and nothing asks for frames.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            frame++
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                scene.advance(currentBins(), currentSettings, dt)
                frame++
            }
        }
    }
    Canvas(modifier.clipToBounds()) {
        // Read here so a new frame redraws without recomposing.
        @Suppress("UNUSED_EXPRESSION") frame
        scene.build(size.width, size.height, currentSettings, color.toArgb(), glow.toArgb())
        drawIntoCanvas { scene.draw(it.nativeCanvas, paint) }
        FrameStallLog.drew(FrameStallLog.Effect.SPECTROGRAM, (size.width * size.height).toLong())
    }
}

/**
 * The landscape's state and geometry, free of Compose so it is held to numbers
 * (`Spectrogram3DSceneTest`): the history of spectra, the camera and the lit
 * mesh built from them.
 *
 * History: a ring of columns, one pushed every [columnSeconds] of playing time
 * from a live column that follows the analyzer (smoothed in time and across
 * pitch). The newest pushed column sits [phase] of a column left of the live
 * one, so the landscape glides rather than steps.
 */
internal class Spectrogram3DScene {
    var rows = 0; private set
    /** Columns across the front edge: [Spectrogram3DSettings.secondsAcross] of music. */
    var baseColumns = 0; private set
    private var capacity = 0
    private var ring = FloatArray(0)
    private var head = 0
    /** History columns pushed so far, up to the ring's size. */
    var filled = 0; private set
    private val live = FloatArray(MAX_ROWS)
    private val sampled = FloatArray(MAX_ROWS)
    private val blurred = FloatArray(MAX_ROWS)
    private val binPos = FloatArray(MAX_ROWS)
    private var acc = 0f
    /** How far, in columns, the newest pushed column has moved off the live one. */
    var phase = 0f; private set
    private var gridKey = ""

    // The mesh, as drawVertices takes it.
    var verts = FloatArray(0); private set
    var colors = IntArray(0); private set
    var indices = ShortArray(0); private set
    var vertexCount = 0; private set
    var indexCount = 0; private set
    private var wx = FloatArray(0)
    private var wy = FloatArray(0)

    /** Seconds of music between two history columns. */
    fun columnSeconds(s: Spectrogram3DSettings): Float = s.secondsAcross / baseColumnsFor(s.quality)

    fun clear() {
        ring.fill(0f)
        live.fill(0f)
        head = 0
        filled = 0
        acc = 0f
        phase = 0f
    }

    /** Sizes the grid for [s]; a new pitch grid starts the landscape afresh. */
    fun prepare(s: Spectrogram3DSettings) {
        val key = "${s.quality}/${s.minHz}/${s.maxHz}"
        if (key == gridKey) return
        gridKey = key
        rows = rowsFor(s.quality)
        baseColumns = baseColumnsFor(s.quality)
        // The widest field (the lowest camera) needs about 1.53 front widths.
        capacity = (baseColumns * MAX_EXTENT).toInt() + 3
        ring = FloatArray(capacity * rows)
        val span = ln(s.maxHz / s.minHz)
        val binSpan = ln(BIN_TOP_HZ / BIN_BOTTOM_HZ)
        for (j in 0 until rows) {
            val hz = s.minHz * exp(span * j / (rows - 1))
            binPos[j] = (ln(hz / BIN_BOTTOM_HZ) / binSpan * (SpectrumAnalyzerTap.OUTPUT_BINS - 1))
                .coerceIn(0f, SpectrumAnalyzerTap.OUTPUT_BINS - 1f)
        }
        clear()
    }

    /** [dt] seconds of playing time with [bins] (the analyzer's dB) as the newest sound. */
    fun advance(bins: FloatArray, s: Spectrogram3DSettings, dt: Float) {
        prepare(s)
        sample(bins, s)
        // A long gap (the screen was off) is not hours of landscape to catch up on.
        val step = dt.coerceIn(0f, MAX_STEP_SECONDS)
        val follow = 1f - exp(-step / (LIVE_TAU_MIN + s.smoothness * LIVE_TAU_RANGE))
        for (j in 0 until rows) live[j] += (blurred[j] - live[j]) * follow
        val colSec = columnSeconds(s)
        acc += step
        while (acc >= colSec) {
            push()
            acc -= colSec
        }
        phase = (acc / colSec).coerceIn(0f, 1f)
    }

    /** The analyzer's bins at this grid's pitches, as heights 0..1, smoothed across pitch. */
    private fun sample(bins: FloatArray, s: Spectrogram3DSettings) {
        val n = bins.size
        for (j in 0 until rows) {
            val p = binPos[j].coerceAtMost(n - 1f)
            val i = p.toInt()
            val f = p - i
            val db = if (n == 0) FLOOR_DB else bins[i] + (bins[min(i + 1, n - 1)] - bins[i]) * f
            sampled[j] = heightOf(if (db.isFinite()) db else FLOOR_DB)
        }
        val sigma = BLUR_MIN + s.smoothness * BLUR_RANGE
        val radius = (sigma * 2.5f).roundToInt().coerceIn(0, 8)
        for (j in 0 until rows) {
            var sum = 0f
            var weight = 0f
            for (k in -radius..radius) {
                val r = (j + k).coerceIn(0, rows - 1)
                val w = exp(-0.5f * k * k / (sigma * sigma))
                sum += sampled[r] * w
                weight += w
            }
            blurred[j] = sum / weight
        }
    }

    private fun push() {
        head = (head - 1 + capacity) % capacity
        System.arraycopy(live, 0, ring, head * rows, rows)
        if (filled < capacity) filled++
    }

    /** History column [k] (0 the newest pushed) at pitch row [j], 0 where nothing was pushed yet. */
    fun historyHeight(k: Int, j: Int): Float =
        if (k < 0 || k >= filled) 0f else ring[((head + k) % capacity) * rows + j]

    fun liveHeight(j: Int): Float = live[j]

    /** Builds the lit mesh for a [width] by [height] box. */
    fun build(width: Float, height: Float, s: Spectrogram3DSettings, base: Int, glow: Int) {
        prepare(s)
        vertexCount = 0
        indexCount = 0
        if (width <= 0f || height <= 0f || rows < 2) return
        val cam = Camera.of(s.angleDeg, width, height)
        // World units per column, so the front edge spans baseColumns columns.
        val dx = 2f * cam.frontHalfWidth / baseColumns
        val right = cam.fieldHalfWidth
        val drawn = min(filled, (2f * right / dx).toInt() + 1)
        val columns = drawn + 1 // and the live one at the right edge
        val count = columns * rows
        if (verts.size < count * 2) {
            verts = FloatArray(count * 2)
            colors = IntArray(count)
            wx = FloatArray(count)
            wy = FloatArray(count)
        }
        val quads = (columns - 1) * (rows - 1)
        if (indices.size < quads * 6) indices = ShortArray(quads * 6)
        val lift = HEIGHT_SCALE * s.peakHeight
        // World positions: column c = 0 is the live one at the right edge.
        for (c in 0 until columns) {
            val x = if (c == 0) right else right - (c - 1 + phase) * dx
            for (j in 0 until rows) {
                val h = if (c == 0) live[j] else historyHeight(c - 1, j)
                val v = c * rows + j
                wx[v] = x
                wy[v] = h * lift
            }
        }
        val light = Lighting(s, base, glow)
        val dz = 1f / (rows - 1)
        for (c in 0 until columns) {
            for (j in 0 until rows) {
                val v = c * rows + j
                val z = j * dz
                // The surface's slope from its neighbours, in world units.
                val cl = max(c - 1, 0)
                val cr = min(c + 1, columns - 1)
                val jl = max(j - 1, 0)
                val jr = min(j + 1, rows - 1)
                val ddx = wx[cl * rows + j] - wx[cr * rows + j]
                val slopeX = if (ddx != 0f) (wy[cl * rows + j] - wy[cr * rows + j]) / ddx else 0f
                val slopeZ = (wy[c * rows + jr] - wy[c * rows + jl]) / ((jr - jl) * dz)
                cam.project(wx[v], wy[v], z, verts, v * 2)
                colors[v] = light.shade(slopeX, slopeZ, if (lift > 0f) wy[v] / lift else 0f, z)
            }
        }
        // Back to front: the strip nearest the back first, so nearer ridges
        // are drawn over what they hide.
        var n = 0
        for (j in rows - 2 downTo 0) {
            for (c in 0 until columns - 1) {
                val a = c * rows + j
                val b = (c + 1) * rows + j
                indices[n++] = (a + 1).toShort(); indices[n++] = (b + 1).toShort(); indices[n++] = a.toShort()
                indices[n++] = (b + 1).toShort(); indices[n++] = b.toShort(); indices[n++] = a.toShort()
            }
        }
        vertexCount = count
        indexCount = n
    }

    fun draw(canvas: android.graphics.Canvas, paint: android.graphics.Paint) {
        if (indexCount == 0) return
        canvas.drawVertices(
            android.graphics.Canvas.VertexMode.TRIANGLES,
            vertexCount * 2, verts, 0,
            null, 0,
            colors, 0,
            indices, 0, indexCount,
            paint,
        )
    }

    /**
     * A camera above the front edge looking down at [angleDeg] onto a field
     * 1 deep (pitch) and as wide as it must be to fill the box to the back,
     * fitted so the front edge sits on the box's bottom and a full-height peak
     * at the back stays inside it.
     */
    class Camera private constructor(
        private val sinA: Float,
        private val cosA: Float,
        private val camY: Float,
        private val camZ: Float,
        private val scale: Float,
        private val originX: Float,
        private val originY: Float,
        /** Half the front edge's visible width, in world units. */
        val frontHalfWidth: Float,
        /** Half the field's width: enough to reach the box's sides at the back. */
        val fieldHalfWidth: Float,
    ) {
        /** Writes the screen position of world point ([x], [y], [z]) into [out] at [at]. */
        fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
            val qy = y - camY
            val qz = z - camZ
            val cy = qy * cosA + qz * sinA
            val cz = max(-qy * sinA + qz * cosA, 0.05f)
            out[at] = originX + x / cz * scale
            out[at + 1] = originY - cy / cz * scale
        }

        companion object {
            fun of(angleDeg: Float, width: Float, height: Float): Camera {
                val a = Math.toRadians(angleDeg.toDouble())
                val sinA = sin(a).toFloat()
                val cosA = cos(a).toFloat()
                val camY = DISTANCE * sinA
                val camZ = TARGET_Z - DISTANCE * cosA
                // Depth along the view of the front and back ground edges.
                val czFront = DISTANCE - TARGET_Z * cosA
                val czBack = DISTANCE + (1f - TARGET_Z) * cosA
                fun py(y: Float, z: Float): Float {
                    val qy = y - camY
                    val qz = z - camZ
                    return (qy * cosA + qz * sinA) / (-qy * sinA + qz * cosA)
                }
                val bottom = py(0f, 0f)
                val top = max(py(HEIGHT_SCALE, 1f), py(HEIGHT_SCALE, 0f))
                // Width-limited: the front edge spans the box. Height-limited:
                // smaller, and the front edge runs past the sides.
                val byWidth = width / 2f * czFront
                val byHeight = height * FIT_HEIGHT / max(top - bottom, 1e-3f)
                val scale = min(byWidth, byHeight)
                val frontHalf = byWidth / scale
                val originY = height * FRONT_EDGE + bottom * scale
                return Camera(
                    sinA, cosA, camY, camZ, scale,
                    originX = width / 2f,
                    originY = originY,
                    frontHalfWidth = frontHalf,
                    fieldHalfWidth = frontHalf * czBack / czFront,
                )
            }
        }
    }

    /**
     * The surface's light at one point: a soft key light from above the
     * camera, a gloss highlight, and the light of a lamp behind the landscape
     * glowing through its ridges in the album's second colour (subsurface
     * scattering, as a thin lit wax would), then the haze toward the back.
     */
    private class Lighting(s: Spectrogram3DSettings, base: Int, glow: Int) {
        private val br = r(base); private val bg = g(base); private val bb = b(base)
        // The glow at full strength in its own hue: the album's second colour is
        // often its dark background colour, which would glow as nothing.
        private val glowPeak = max(max(r(glow), g(glow)), max(b(glow), 0.01f))
        private val gr = r(glow) / glowPeak; private val gg = g(glow) / glowPeak; private val gb = b(glow) / glowPeak
        private val gloss = s.gloss
        private val shine = 6f + s.gloss * 90f
        private val subsurface = s.subsurface
        private val haze = s.haze
        // The camera looks down at the angle; the view vector points back at it.
        private val a = Math.toRadians(s.angleDeg.toDouble())
        private val vy = sin(a).toFloat()
        private val vz = -cos(a).toFloat()

        fun shade(slopeX: Float, slopeZ: Float, h: Float, z: Float): Int {
            // The normal of y = f(x, z).
            var nx = -slopeX
            var ny = 1f
            var nz = -slopeZ
            val nl = 1f / sqrt(nx * nx + ny * ny + nz * nz)
            nx *= nl; ny *= nl; nz *= nl
            // Albedo: deep at the floor, the album colour mid-way, toward white at the peaks.
            val t = h.coerceIn(0f, 1f)
            val k = if (t < 0.5f) t * 2f else (t - 0.5f) * 2f
            var ar: Float; var ag: Float; var ab: Float
            if (t < 0.5f) {
                ar = mix(br * DEEP, br, k); ag = mix(bg * DEEP, bg, k); ab = mix(bb * DEEP, bb, k)
            } else {
                ar = mix(br, mix(br, 1f, PEAK_WHITE), k); ag = mix(bg, mix(bg, 1f, PEAK_WHITE), k); ab = mix(bb, mix(bb, 1f, PEAK_WHITE), k)
            }
            // Key light, wrapped so the shaded side is not black.
            val nDotL = nx * KEY_X + ny * KEY_Y + nz * KEY_Z
            val diffuse = ((nDotL + WRAP) / (1f + WRAP)).coerceAtLeast(0f)
            // Gloss: Blinn-Phong on the half vector between the key light and the view.
            val hx = KEY_X; val hy = KEY_Y + vy; val hz = KEY_Z + vz
            val hl = 1f / sqrt(hx * hx + hy * hy + hz * hz)
            val nDotH = ((nx * hx + ny * hy + nz * hz) * hl).coerceAtLeast(0f)
            val spec = if (gloss > 0f && nDotH > 0f) gloss * 1.1f * exp(shine * ln(nDotH)) else 0f
            // Subsurface: light from behind, bent by the surface, reaching the eye,
            // and the rim where the surface turns away from the camera.
            var dx = -(BACK_X + nx * BEND); var dy = -(BACK_Y + ny * BEND); var dzv = -(BACK_Z + nz * BEND)
            val dl = 1f / sqrt(dx * dx + dy * dy + dzv * dzv)
            dx *= dl; dy *= dl; dzv *= dl
            val through = (dy * vy + dzv * vz).coerceAtLeast(0f)
            val nDotV = (ny * vy + nz * vz).coerceIn(0f, 1f)
            val rim = (1f - nDotV) * (1f - nDotV)
            val sss = subsurface * (1.1f * through * through * through + 0.8f * rim) * (0.3f + 0.7f * t)
            val lit = AMBIENT + (1f - AMBIENT) * diffuse
            var cr = ar * lit + gr * sss + spec
            var cg = ag * lit + gg * sss + spec
            var cb = ab * lit + gb * sss + spec
            // Haze: the back darkens toward the deep colour and fades into the cover.
            val far = z * z
            val fog = haze * 0.75f * far
            cr = mix(cr, br * DEEP, fog); cg = mix(cg, bg * DEEP, fog); cb = mix(cb, bb * DEEP, fog)
            val alpha = 1f - haze * smooth(0.35f, 1f, z)
            return argb(alpha, cr, cg, cb)
        }

        private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    companion object {
        const val MAX_ROWS = 64
        /** The analyzer's bins run log-spaced from 20 Hz to 20 kHz. */
        const val BIN_BOTTOM_HZ = 20f
        const val BIN_TOP_HZ = 20_000f
        /** dB (around the analyzer's 0 dB midband) at the floor and at a full peak. */
        const val FLOOR_DB = -20f
        const val TOP_DB = 28f
        /** Quiet sound stays near the floor; the peaks rise out of it. */
        const val SHAPE = 1.5f
        /** World height of a full peak at peak height 1 (the field is 1 deep, about 2 wide). */
        const val HEIGHT_SCALE = 0.28f
        const val DISTANCE = 2.4f
        const val TARGET_Z = 0.5f
        /** The front edge's place in the box, and the share of the box's height the landscape may fill. */
        const val FRONT_EDGE = 0.985f
        const val FIT_HEIGHT = 0.95f
        /** The field at the lowest camera is this many front widths wide. */
        const val MAX_EXTENT = 1.6f
        const val MAX_STEP_SECONDS = 0.1f
        const val LIVE_TAU_MIN = 0.015f
        const val LIVE_TAU_RANGE = 0.12f
        const val BLUR_MIN = 0.35f
        const val BLUR_RANGE = 2.0f

        private const val DEEP = 0.22f
        private const val PEAK_WHITE = 0.7f
        private const val AMBIENT = 0.16f
        private const val WRAP = 0.4f
        private const val BEND = 0.6f
        // Key light: above the camera, a little to the left (unit vector).
        private const val KEY_X = -0.33f
        private const val KEY_Y = 0.85f
        private const val KEY_Z = -0.41f
        // The lamp behind the landscape (unit vector toward it).
        private const val BACK_X = 0.18f
        private const val BACK_Y = 0.32f
        private const val BACK_Z = 0.93f

        /** Pitch rows by quality 1..4. */
        fun rowsFor(quality: Int): Int = intArrayOf(24, 36, 48, 64)[quality.coerceIn(1, 4) - 1]

        /** Columns across the front edge by quality 1..4. */
        fun baseColumnsFor(quality: Int): Int = intArrayOf(64, 104, 144, 192)[quality.coerceIn(1, 4) - 1]

        /** The analyzer's dB as a height 0..1. */
        fun heightOf(db: Float): Float {
            val t = ((db - FLOOR_DB) / (TOP_DB - FLOOR_DB)).coerceIn(0f, 1f)
            return if (t <= 0f) 0f else exp(SHAPE * ln(t))
        }

        private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
        private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
        private fun b(c: Int) = (c and 0xFF) / 255f
        private fun argb(a: Float, r: Float, g: Float, b: Float): Int {
            fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}
