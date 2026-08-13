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

    /**
     * Canvas size. Smaller than the spectrum's 320 for one reason: total file
     * size (see [MAX_BYTES]). A spinning disc is a full, distinct picture every
     * frame — the spectrum reused one near-static cover — so at 320 it more than
     * doubled the spectrum's bytes and blew past Discord's animation cap. 288 is
     * still sharper than the slot renders it at.
     */
    const val SIZE = 288

    /**
     * Frames per revolution.
     *
     * Every frame is one whole re-encoded canvas, so this is the cost knob for
     * both time and bytes. 28 steps the disc ~13° at a time, which still reads
     * as turning rather than ticking.
     */
    const val FRAMES = 28

    /**
     * The ceiling the assembled animation is kept under.
     *
     * Discord's media proxy will not animate an asset past roughly a quarter of
     * a megabyte — over it, the client shows the first frame and nothing else,
     * which on a disc at rest is just a round cover that never turns. That is
     * exactly the "animation isn't showing" this exists to prevent. The number
     * is a little under 256 KiB to leave room for the container around the
     * frames. [render] steps the encoder's quality down until it fits.
     */
    private const val MAX_BYTES = 248_000

    /** Where the quality search starts, and the floor it will not go below. */
    private const val QUALITY_MAX = 74
    private const val QUALITY_MIN = 40
    const val QUALITY = QUALITY_MAX

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

        // Draw every frame once, as a bitmap. Encoding is separate and may be
        // repeated at a lower quality to fit the budget, and re-drawing the
        // rotation each time would be wasted work — so the pixels are held and
        // only re-compressed.
        val frameBitmaps = ArrayList<Bitmap>(FRAMES)
        for (f in 0 until FRAMES) {
            val angle = 360f * f / FRAMES
            val frame = createBitmap(SIZE, SIZE)
            val canvas = Canvas(frame)
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
            frameBitmaps += frame
        }
        if (base !== cover) base.recycle()

        val loopMs = (BEATS * 60_000f / tempo).toInt()

        // Encode under the cap, on two levers. First quality: a busy cover at
        // full quality can be over the budget, so it steps down until the whole
        // animation fits, and a simple cover keeps its crispness because it was
        // already small. If quality bottoms out and it still won't fit, frames
        // are dropped — every second one — which halves the bytes and only
        // widens the step the disc turns in. Both are better than a pristine
        // animation Discord freezes on its first frame.
        //
        // Frame counts are strides of the full set, so the angles stay evenly
        // spaced and one bar still turns the disc exactly once.
        val result = try {
            var best: ByteArray? = null
            for (stride in intArrayOf(1, 2)) {
                val frames = frameBitmaps.filterIndexed { i, _ -> i % stride == 0 }
                val count = frames.size
                val perFrame = (loopMs / count).coerceAtLeast(1)
                val durations = List(count) { perFrame }

                var quality = QUALITY_MAX
                var fitAtThisStride: ByteArray? = null
                while (quality >= QUALITY_MIN) {
                    val encoded = frames.map { bmp ->
                        val out = ByteArrayOutputStream()
                        @Suppress("DEPRECATION")
                        if (!bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)) {
                            return@map null
                        }
                        out.toByteArray()
                    }
                    if (encoded.any { it == null }) break
                    @Suppress("UNCHECKED_CAST")
                    val webp = AnimatedWebP.assemble(
                        encoded as List<ByteArray>, durations, SIZE, SIZE,
                    )
                    // Keep the smallest thing seen, so even the pathological
                    // case returns something rather than nothing.
                    if (best == null || webp.size < best!!.size) best = webp
                    if (webp.size <= MAX_BYTES) {
                        fitAtThisStride = webp
                        break
                    }
                    quality -= 8
                }
                if (fitAtThisStride != null) {
                    best = fitAtThisStride
                    break
                }
            }
            best
        } catch (t: Throwable) {
            null
        }

        frameBitmaps.forEach { it.recycle() }
        return result
    }
}
