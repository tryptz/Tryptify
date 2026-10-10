package tf.monochrome.android.ui.discover.galaxy

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The black hole at the middle of the galaxy, drawn the way it is usually
 * pictured since Interstellar, and cheaply:
 *
 * - the **accretion disk**, lying in the galaxy's plane: white-hot at its inner
 *   edge, orange, then a dull red at the rim, with streaks that turn as it
 *   spins. One baked texture, mapped onto the plane by the projection of two
 *   of its axes — a circle in the plane is an ellipse on screen, and at these
 *   distances perspective inside the disk is too small to see;
 * - **Doppler beaming**: the side of the disk turning toward the camera is
 *   brighter, a second baked texture laid where that side is this frame;
 * - the **shadow**, black, over the far half of the disk and under the near;
 * - the **lensed arc**: the far side of the disk, bent up over the top of the
 *   shadow by the hole's gravity, strongest seen edge-on;
 * - the **photon ring**, a thin bright line hugging the shadow.
 *
 * The near and far halves are split along the disk's long axis on screen, so a
 * hole seen from above the plane has its disk passing in front of it below and
 * behind it above. Seen face-on there is no split to make: the disk's inner
 * edge is outside the shadow, so it is drawn whole.
 */
internal class BlackHoleArt {
    private val disk: Bitmap = bakeDisk(TEX, doppler = false)
    private val beam: Bitmap = bakeDisk(TEX, doppler = true)
    private val shadow: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                64f, 64f, 64f,
                intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.86f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(64f, 64f, 64f, paint)
    }

    private val add = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { additive() }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        additive()
    }
    private val matrix = Matrix()
    /** This frame's brightness, 1 at rest: the bass swells it. */
    private var boost = 1f
    private val values = FloatArray(9)
    private val rect = RectF()
    private val p = FloatArray(3)
    private val q = FloatArray(3)

    /**
     * Draws the hole for camera [f] at [time] seconds. [rays] is the god-ray
     * emitter pass: only what shines — the disk and the ring, no shadow, no
     * split — at full strength.
     */
    fun draw(canvas: Canvas, f: CameraFrame, time: Float, dp: Float, rays: Boolean = false, boost: Float = 1f) {
        this.boost = boost.coerceIn(0.5f, 2f)
        if (!f.project(0f, 0f, 0f, p, 0)) return
        val cx = p[0]; val cy = p[1]; val depth = p[2]
        val shadowR = GalaxyScene.HOLE_SHADOW * f.scaleAt(depth)
        if (shadowR < 0.6f * dp) return

        // The camera's direction from the hole, in the galaxy's own frame.
        var vx = f.ex; var vy = f.ey; var vz = f.ez
        val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-3f)
        vx /= vl; vy /= vl; vz /= vl
        val edgeOn = 1f - kotlin.math.abs(vy)

        // The disk turns toward smaller angles, with the galaxy. The side
        // coming toward the camera is where the velocity (sin a, 0, -cos a)
        // points at it.
        val approaching = atan2(vx, -vz)
        val spin = -time * DISK_SPIN

        if (rays) {
            drawDisk(canvas, f, disk, spin, 1f)
            drawDisk(canvas, f, beam, approaching, 0.8f)
            drawRing(canvas, cx, cy, shadowR, dp, 1f)
            return
        }

        // Which way is near: the point of the disk's rim toward the camera.
        val near = atan2(vz, vx)
        val r = GalaxyScene.DISK_OUTER
        val split = f.project(cos(near) * r, 0f, sin(near) * r, q, 0) &&
            hypot(q[0] - cx, q[1] - cy) > shadowR * 0.35f
        val towardNear = if (split) atan2(q[1] - cy, q[0] - cx) else 0f
        // The long axis on screen, perpendicular to the way to the near rim.
        val axisDeg = Math.toDegrees((towardNear - PI / 2).toDouble()).toFloat()
        val big = 20_000f

        if (split) {
            // The far half, behind the hole.
            canvas.save()
            canvas.rotate(axisDeg, cx, cy)
            canvas.clipRect(cx - big, cy - big, cx + big, cy)
            canvas.rotate(-axisDeg, cx, cy)
            drawDisk(canvas, f, disk, spin, 1f)
            drawDisk(canvas, f, beam, approaching, 0.7f)
            canvas.restore()
        } else {
            drawDisk(canvas, f, disk, spin, 1f)
            drawDisk(canvas, f, beam, approaching, 0.7f)
        }

        // The shadow.
        val s = shadowR * 1.14f
        rect.set(cx - s, cy - s, cx + s, cy + s)
        canvas.drawBitmap(shadow, null, rect, plain)

        if (split) {
            // The far side of the disk, bent up over the top of the shadow.
            // Strongest edge-on, where it is the disk's only visible far half.
            canvas.save()
            canvas.rotate(axisDeg, cx, cy)
            canvas.clipRect(cx - big, cy - big, cx + big, cy)
            ring.color = LENSED
            ring.alpha = (255 * (0.25f + 0.55f * edgeOn)).toInt()
            ring.strokeWidth = shadowR * 0.32f
            rect.set(cx - shadowR * 1.75f, cy - shadowR * 1.32f, cx + shadowR * 1.75f, cy + shadowR * 1.32f)
            canvas.drawOval(rect, ring)
            ring.alpha = (255 * (0.2f + 0.4f * edgeOn)).toInt()
            ring.strokeWidth = shadowR * 0.1f
            rect.set(cx - shadowR * 1.5f, cy - shadowR * 1.2f, cx + shadowR * 1.5f, cy + shadowR * 1.2f)
            canvas.drawOval(rect, ring)
            canvas.restore()
        }

        drawRing(canvas, cx, cy, shadowR, dp, 0.9f)

        if (split) {
            // The near half, in front of it.
            canvas.save()
            canvas.rotate(axisDeg, cx, cy)
            canvas.clipRect(cx - big, cy, cx + big, cy + big)
            canvas.rotate(-axisDeg, cx, cy)
            drawDisk(canvas, f, disk, spin, 1f)
            drawDisk(canvas, f, beam, approaching, 0.7f)
            canvas.restore()
        }
    }

    /** The photon ring: a thin bright line on the shadow's edge, and its glow. */
    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, shadowR: Float, dp: Float, strength: Float) {
        ring.color = PHOTON
        ring.alpha = (255 * 0.22f * strength * boost).toInt().coerceIn(0, 255)
        ring.strokeWidth = shadowR * 0.22f
        canvas.drawCircle(cx, cy, shadowR * 1.04f, ring)
        ring.alpha = (255 * strength).toInt()
        ring.strokeWidth = (shadowR * 0.045f).coerceAtLeast(1f * dp)
        canvas.drawCircle(cx, cy, shadowR * 1.02f, ring)
    }

    /**
     * Lays [texture] (the disk, unit circle filling it) on the galaxy plane,
     * turned by [angle]: the bitmap's axes go to the screen images of two
     * perpendicular radii of the disk.
     */
    private fun drawDisk(canvas: Canvas, f: CameraFrame, texture: Bitmap, angle: Float, strength: Float) {
        val r = GalaxyScene.DISK_OUTER
        if (!f.project(0f, 0f, 0f, p, 0)) return
        val cx = p[0]; val cy = p[1]
        if (!f.project(cos(angle) * r, 0f, sin(angle) * r, q, 0)) return
        val ax = q[0] - cx; val ay = q[1] - cy
        if (!f.project(-sin(angle) * r, 0f, cos(angle) * r, q, 0)) return
        val bx = q[0] - cx; val by = q[1] - cy
        val half = texture.width / 2f
        // Bitmap px -> unit square (-1..1) -> the plane's screen image.
        values[0] = ax / half; values[1] = bx / half; values[2] = cx - ax - bx
        values[3] = ay / half; values[4] = by / half; values[5] = cy - ay - by
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        add.alpha = (255 * strength * boost).toInt().coerceIn(0, 255)
        canvas.drawBitmap(texture, matrix, add)
    }

    private companion object {
        const val TEX = 256

        /** How fast the disk's streaks turn, radians a second. */
        const val DISK_SPIN = 0.22f

        val PHOTON = 0xFFFFE9C8.toInt()
        val LENSED = 0xFFFFC27A.toInt()

        fun Paint.additive() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.PLUS
            else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        /**
         * The disk, or its Doppler half ([doppler]): an annulus from the inner
         * edge to the rim, hotter inside. The disk carries streaks and clumps
         * so its turning shows; the Doppler texture is plain, a glow brightest
         * at +x that is laid toward the approaching side.
         */
        fun bakeDisk(px: Int, doppler: Boolean): Bitmap {
            val inner = GalaxyScene.DISK_INNER / GalaxyScene.DISK_OUTER
            val out = IntArray(px * px)
            val half = px / 2f
            for (j in 0 until px) for (i in 0 until px) {
                val x = (i + 0.5f) / half - 1f
                val y = (j + 0.5f) / half - 1f
                val r = sqrt(x * x + y * y)
                if (r >= 1f || r < inner * 0.9f) continue
                val t = ((r - inner) / (1f - inner)).coerceIn(0f, 1f)
                val a = atan2(y, x)
                val edgeIn = ((r - inner * 0.9f) / (inner * 0.12f)).coerceIn(0f, 1f)
                val edgeOut = ((1f - r) / 0.08f).coerceIn(0f, 1f)
                var v: Float
                if (doppler) {
                    val side = (0.5f + 0.5f * cos(a)).pow(2.5f)
                    v = side * (1f - t).pow(1.3f) * 0.75f
                } else {
                    // Soft: a stripy disk read as a record, not as gas.
                    val streak = 0.84f + 0.16f * sin(r * 55f + 2.0f * sin(a * 3f + r * 7f))
                    val clump = 0.75f + 0.25f * sin(a * 5f + r * 22f) * sin(a * 2f - r * 13f)
                    v = (1f - t).pow(1.7f) * streak * clump * 1.15f + 0.08f * exp(-t * 3f)
                }
                v *= edgeIn * edgeOut
                // Temperature: white-yellow inside, orange, a dull red at the rim.
                val rr: Float; val gg: Float; val bb: Float
                if (t < 0.35f) {
                    val k = t / 0.35f
                    rr = 1f; gg = 0.95f - 0.33f * k; bb = 0.85f - 0.6f * k
                } else {
                    val k = (t - 0.35f) / 0.65f
                    rr = 1f - 0.4f * k; gg = 0.62f - 0.47f * k; bb = 0.25f - 0.17f * k
                }
                val alpha = (v.coerceIn(0f, 1f) * 255f).toInt()
                out[j * px + i] = (alpha shl 24) or
                    ((rr * 255).toInt() shl 16) or ((gg * 255).toInt() shl 8) or (bb * 255).toInt()
            }
            return Bitmap.createBitmap(out, px, px, Bitmap.Config.ARGB_8888)
        }
    }
}
