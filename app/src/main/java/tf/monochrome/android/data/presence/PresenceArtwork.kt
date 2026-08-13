package tf.monochrome.android.data.presence

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/**
 * Spin the album art as a disc, as an animated WebP.
 *
 * The small-image slot is a fixed circle, so a badge can never span the cover's
 * width — the only surface with that width is the cover itself. Which means the
 * image has to be built here, on the device, because the artwork is different
 * every track.
 *
 * This used to draw a spectrum across the bottom third of the sleeve. It read as
 * a graphic *stuck on* the artwork rather than as anything the artwork was
 * doing, and on a busy cover the curve and the picture fought each other. A
 * turning disc is the same idea told properly: nothing is drawn over the art at
 * all, the art *is* the moving part, and everybody already knows what a record
 * going round means.
 *
 * Deliberately cheaper than the pre-drawn badges. Those are 60fps because a
 * build machine drew them once; this runs on a phone at every track change, and
 * a rotation at 60fps in 320px is a hundred and twenty full-canvas composites
 * and encodes for something rendered at a couple of hundred pixels on somebody
 * else's screen. [FRAMES] is the honest number for a device — and rotation is
 * the one motion where the step between frames is visible as judder, so it buys
 * a few more than the spectrum needed.
 */
object PresenceArtwork {

    const val SIZE = 320

    /**
     * Frames per revolution.
     *
     * Every frame is one whole re-encoded canvas, so this is the cost knob. At
     * 36 the disc steps 10° at a time, which reads as turning; at the 24 the
     * spectrum used it reads as ticking round like a clock hand.
     */
    const val FRAMES = 36
    const val QUALITY = 76

    /** The disc, as a fraction of the canvas. Short of the edge so it reads as round. */
    private const val DISC = 0.94f

    /** The label hole at the centre, as a fraction of the disc's radius. */
    private const val HUB = 0.15f

    /** A rhythm: where the hits land in a 4/4 bar, and how fast the bar runs. */
    data class Groove(val bpm: Int, val kicks: List<Float>, val snares: List<Float>)

    /** Mirrors tools/build_presence_loops.py, which draws the static badges. */
    val GROOVES: Map<String, Groove> = mapOf(
        "four" to Groove(128, listOf(0f, 1f, 2f, 3f), listOf(1f, 3f)),
        "hard" to Groove(150, listOf(0f, 1f, 2f, 3f), listOf(1f, 3f)),
        "dnb" to Groove(174, listOf(0f, 2.5f), listOf(1f, 3f)),
        "boombap" to Groove(90, listOf(0f, 0.75f, 2.5f), listOf(1f, 3f)),
        "trap" to Groove(140, listOf(0f, 1.75f, 2.5f), listOf(2f)),
        "backbeat" to Groove(120, listOf(0f, 2f), listOf(1f, 3f)),
        "halftime" to Groove(70, listOf(0f), listOf(2f)),
        "dembow" to Groove(96, listOf(0f, 1.5f, 2f, 3.5f), listOf(0.75f, 1.75f, 2.75f, 3.75f)),
    )

    /** Beats per revolution. One bar per turn, so the spin is on the music. */
    private const val BEATS = 4f

    /**
     * @return an animated WebP, or null if a frame failed to encode.
     */
    fun render(cover: Bitmap, grooveName: String, tint: Int, bpm: Int? = null): ByteArray? {
        val groove = GROOVES[grooveName] ?: GROOVES.getValue(PresenceBadge.DEFAULT_GROOVE)
        // The track's own tempo when the graph knows it, the groove's canonical
        // one otherwise. A house groove turns at 128 because that is where house
        // lives, but a 140 BPM record spinning at 128 is visibly off the beat —
        // and the genre carries a real range worth using.
        val tempo = bpm?.takeIf { it in 40..300 } ?: groove.bpm

        val centre = SIZE / 2f
        val radius = centre * DISC
        val base = cover.scale(SIZE, SIZE)

        // The sleeve, as a disc. Built once and turned by the canvas, rather
        // than re-clipped every frame.
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(base, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        // Opaque background, not transparency. Discord's media proxy composites
        // alpha onto black, so a disc on a transparent field would arrive on
        // whatever black the proxy chose; picking the colour here means the
        // corners are a deliberate near-black of the cover's own tint instead.
        val backdrop = Paint().apply {
            color = Color.rgb(
                (Color.red(tint) * 0.12f).toInt(),
                (Color.green(tint) * 0.12f).toInt(),
                (Color.blue(tint) * 0.12f).toInt(),
            )
        }

        // Pressed-disc grooves: faint concentric shading that catches the light.
        // Static, because they belong to the disc's shape rather than to the
        // picture printed on it.
        val grooves = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centre, centre, radius,
                intArrayOf(
                    Color.argb(0, 0, 0, 0),
                    Color.argb(26, 255, 255, 255),
                    Color.argb(0, 0, 0, 0),
                    Color.argb(20, 0, 0, 0),
                ),
                floatArrayOf(0.30f, 0.52f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        // The sheen, and the reason the spin is legible at all.
        //
        // A cover with no strong asymmetry — a plain field, a centred logo —
        // turns without appearing to, because every frame looks like the last.
        // A highlight that stays put while the art moves under it is what gives
        // the eye something to measure the rotation against.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(
                centre, centre,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(54, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(38, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.08f, 0.20f, 0.46f, 0.55f, 0.68f, 1f),
            )
        }

        // A soft edge so the disc sits on the backdrop rather than being cut out
        // of it, and the hub the spindle goes through.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.008f
            shader = LinearGradient(
                0f, 0f, 0f, SIZE.toFloat(),
                Color.argb(90, 255, 255, 255),
                Color.argb(40, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
        val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backdrop.color }
        val hubRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.006f
            color = Color.argb(70, 255, 255, 255)
        }

        val frames = ArrayList<ByteArray>(FRAMES)
        val canvasBitmap = createBitmap(SIZE, SIZE)
        val canvas = Canvas(canvasBitmap)

        for (f in 0 until FRAMES) {
            val angle = 360f * f / FRAMES
            canvas.drawPaint(backdrop)

            // Only the artwork turns. Everything after this — grooves, sheen,
            // rim, hub — belongs to the disc as an object sitting still under a
            // fixed light, which is what makes the turning readable.
            canvas.save()
            canvas.rotate(angle, centre, centre)
            canvas.drawCircle(centre, centre, radius, discPaint)
            canvas.restore()

            canvas.drawCircle(centre, centre, radius, grooves)
            canvas.drawCircle(centre, centre, radius, sheen)
            canvas.drawCircle(centre, centre, radius, rim)
            canvas.drawCircle(centre, centre, radius * HUB, hub)
            canvas.drawCircle(centre, centre, radius * HUB, hubRing)

            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val ok = canvasBitmap.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
            if (!ok) return null
            frames += out.toByteArray()
        }
        canvasBitmap.recycle()
        if (base !== cover) base.recycle()

        // One revolution per bar. Seamless by construction: the last frame is
        // one step short of all the way round, so the loop back to zero is the
        // same step as every other.
        val loopMs = (BEATS * 60_000f / tempo).toInt()
        val perFrame = (loopMs / FRAMES).coerceAtLeast(1)
        return runCatching {
            AnimatedWebP.assemble(frames, List(FRAMES) { perFrame }, SIZE, SIZE)
        }.getOrNull()
    }
}
