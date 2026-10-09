package tf.monochrome.android.ui.discover.galaxy

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The camera over the galaxy: it orbits a target, at a distance, from an
 * angle. Every field is snapshot state, so anything drawn from it redraws
 * when it moves and nothing recomposes.
 */
@Stable
class GalaxyCamera {
    var targetX by mutableFloatStateOf(0f)
    var targetY by mutableFloatStateOf(0f)
    var targetZ by mutableFloatStateOf(0f)

    /** Around the vertical axis, radians. */
    var yaw by mutableFloatStateOf(0f)

    /** Above the disc, radians: 0 edge-on, π/2 straight down. */
    var pitch by mutableFloatStateOf(OVERVIEW_PITCH)

    var distance by mutableFloatStateOf(2400f)

    /**
     * The genre the camera stays on, or -1. Kept so the target moves with the
     * star while the layout morphs; two fingers dragging the view let go of it.
     * Not snapshot state: it is read each frame by the clock, never drawn.
     */
    var follow: Int = -1

    /** A planet of [follow]'s system the camera stays on as it orbits, or -1. */
    var followPlanet: Int = -1

    fun orbit(dxPx: Float, dyPx: Float) {
        yaw -= dxPx * ORBIT_RATE
        pitch = (pitch + dyPx * ORBIT_RATE * 0.8f).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    fun zoom(factor: Float) {
        if (factor > 0f) distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** Slides the target across the view, so the scene follows two fingers. */
    fun pan(dxPx: Float, dyPx: Float, frame: CameraFrame) {
        follow = -1
        followPlanet = -1
        val k = distance / frame.focal
        targetX += (-frame.rx * dxPx + frame.ux * dyPx) * k
        targetY += (-frame.ry * dxPx + frame.uy * dyPx) * k
        targetZ += (-frame.rz * dxPx + frame.uz * dyPx) * k
    }

    /**
     * This instant's view, for a viewport [width] × [height] px whose
     * visible middle is at [centerY] — above the panels, not the screen's
     * middle, or every genre you look at would sit under its own panel.
     */
    fun frame(width: Float, height: Float, centerY: Float = height / 2f): CameraFrame =
        CameraFrame(
            targetX, targetY, targetZ, yaw, pitch, distance,
            width, height, centerY,
        )

    companion object {
        const val FOV_DEGREES = 60f
        /** Near enough to read a planet's moons. */
        const val MIN_DISTANCE = 15f
        const val MAX_DISTANCE = 9000f
        const val MIN_PITCH = 0.06f
        const val MAX_PITCH = 1.5f
        const val OVERVIEW_PITCH = 0.62f
        private const val ORBIT_RATE = 0.005f

        /**
         * How far back the overview sits so the galaxy's [radius] fits across a
         * viewport of [aspect] (width / height) — a portrait phone is limited by
         * its width, a tablet in landscape by its height. Slightly inside the
         * rim, the way the demo framed it: the galaxy should fill the screen,
         * not float small in the middle of it.
         */
        fun fitDistance(radius: Float, aspect: Float): Float {
            val halfV = Math.toRadians(FOV_DEGREES / 2.0).toFloat()
            val halfH = atan(tan(halfV) * aspect.coerceAtLeast(0.1f))
            val limiting = minOf(halfV, halfH)
            return (radius * 0.85f / tan(limiting)).coerceIn(1200f, MAX_DISTANCE)
        }
    }
}

/**
 * One frame of the camera: where the eye is, which way is right and up, and
 * how to put a point in the scene on the screen. Pure arithmetic.
 */
class CameraFrame(
    tx: Float, ty: Float, tz: Float,
    yaw: Float, pitch: Float, val distance: Float,
    val width: Float, val height: Float, val centerY: Float,
) {
    val ex: Float
    val ey: Float
    val ez: Float

    /** Forward, right and up, unit length. */
    val fx: Float; val fy: Float; val fz: Float
    val rx: Float; val ry: Float; val rz: Float
    val ux: Float; val uy: Float; val uz: Float

    /** Pixels per unit at one unit of depth. */
    val focal: Float = (height / 2f) / tan(Math.toRadians(GalaxyCamera.FOV_DEGREES / 2.0).toFloat())
    val centerX: Float = width / 2f

    init {
        val cp = cos(pitch)
        ex = tx + distance * cp * sin(yaw)
        ey = ty + distance * sin(pitch)
        ez = tz + distance * cp * cos(yaw)
        var x = tx - ex; var y = ty - ey; var z = tz - ez
        var n = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        fx = x / n; fy = y / n; fz = z / n
        // right = forward × world-up (0,1,0)
        x = -fz; y = 0f; z = fx
        n = sqrt(x * x + z * z).coerceAtLeast(1e-6f)
        rx = x / n; ry = 0f; rz = z / n
        // up = right × forward
        ux = ry * fz - rz * fy
        uy = rz * fx - rx * fz
        uz = rx * fy - ry * fx
    }

    /**
     * Puts the point ([x], [y], [z]) on the screen: writes its x, y in px and
     * its depth to [out] from [at], and returns false (depth -1) when it is
     * behind the camera or too close to draw.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int): Boolean {
        val dx = x - ex; val dy = y - ey; val dz = z - ez
        val depth = dx * fx + dy * fy + dz * fz
        if (depth < NEAR) {
            out[at + 2] = -1f
            return false
        }
        val s = focal / depth
        out[at] = centerX + (dx * rx + dy * ry + dz * rz) * s
        out[at + 1] = centerY - (dx * ux + dy * uy + dz * uz) * s
        out[at + 2] = depth
        return true
    }

    /** A direction at infinity (the far sky): only the camera's turn moves it. */
    fun projectDirection(x: Float, y: Float, z: Float, out: FloatArray, at: Int): Boolean {
        val depth = x * fx + y * fy + z * fz
        if (depth < 0.05f) return false
        val s = focal / depth
        out[at] = centerX + (x * rx + y * ry + z * rz) * s
        out[at + 1] = centerY - (x * ux + y * uy + z * uz) * s
        return true
    }

    /** Screen px across that a [size] scene units wide thing at [depth] covers. */
    fun scaleAt(depth: Float): Float = focal / depth

    companion object {
        const val NEAR = 1f
    }
}
