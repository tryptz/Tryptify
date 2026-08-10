package tf.monochrome.android.data.presence

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Draw the spectrum across the album art, as an animated WebP.
 *
 * The small-image slot is a fixed circle, so a badge can never span the cover's
 * width — the only surface with that width is the cover itself. Which means the
 * image has to be built here, on the device, because the artwork is different
 * every track.
 *
 * Deliberately cheaper than the pre-drawn badges. Those are 60fps because a
 * build machine drew them once; this runs on a phone at every track change, and
 * a bar of 60fps at 320px is a hundred and twenty full-canvas composites and
 * encodes for something rendered at a couple of hundred pixels on somebody
 * else's screen. [FRAMES] is the honest number for a device.
 */
object PresenceArtwork {

    const val SIZE = 320
    /** Frames per loop. A bar spread over this many; 60fps is a build-machine luxury. */
    const val FRAMES = 24
    /** The lower band of the cover the spectrum occupies. */
    const val BAND = 0.34f
    const val QUALITY = 76

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

    private const val BEATS = 4f
    private const val OCTAVES = 10f

    /**
     * @return an animated WebP, or null if a frame failed to encode.
     */
    fun render(cover: Bitmap, grooveName: String, tint: Int, bpm: Int? = null): ByteArray? {
        val groove = GROOVES[grooveName] ?: GROOVES.getValue(PresenceBadge.DEFAULT_GROOVE)
        // The track's own tempo when the graph knows it, the groove's canonical
        // one otherwise. A house groove is drawn at 128 because that is where
        // house lives, but a 140 BPM record playing at 128 is visibly off the
        // beat — and the genre carries a real range worth using.
        val tempo = bpm?.takeIf { it in 40..300 } ?: groove.bpm
        val base = Bitmap.createScaledBitmap(cover, SIZE, SIZE, true)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Opaque: Discord's media proxy composites alpha onto black, so a
            // translucent curve would arrive as a dark smear over the artwork
            // rather than as a wash of colour on it. Solid, with the shadow
            // beneath it doing the separating instead.
            color = Color.argb(255, Color.red(tint), Color.green(tint), Color.blue(tint))
        }
        // A dark scrim under the band, so a pale cover doesn't swallow the curve.
        val scrim = Paint().apply { color = Color.argb(90, 0, 0, 0) }

        val frames = ArrayList<ByteArray>(FRAMES)
        val canvasBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val path = Path()

        for (f in 0 until FRAMES) {
            val t = f / FRAMES.toFloat()
            canvas.drawBitmap(base, 0f, 0f, null)
            canvas.drawRect(0f, SIZE * (1f - BAND), SIZE.toFloat(), SIZE.toFloat(), scrim)

            path.reset()
            path.moveTo(0f, SIZE.toFloat())
            var x = 0
            while (x <= SIZE) {
                val level = level(x / SIZE.toFloat(), t, groove)
                path.lineTo(x.toFloat(), SIZE - level * SIZE * BAND)
                x += 2
            }
            path.lineTo(SIZE.toFloat(), SIZE.toFloat())
            path.close()
            canvas.drawPath(path, fill)

            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val ok = canvasBitmap.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
            if (!ok) return null
            frames += out.toByteArray()
        }
        canvasBitmap.recycle()
        if (base !== cover) base.recycle()

        val loopMs = (BEATS * 60_000f / tempo).toInt()
        val perFrame = (loopMs / FRAMES).coerceAtLeast(1)
        return runCatching {
            AnimatedWebP.assemble(frames, List(FRAMES) { perFrame }, SIZE, SIZE)
        }.getOrNull()
    }

    /** Height at [x] across the frame, at [t] through the bar, as 0..1. */
    internal fun level(x: Float, t: Float, groove: Groove): Float {
        val octave = x * OCTAVES
        val pink = 0.40f + (0.10f - 0.40f) * x

        val kick = 0.52f * gauss(octave, 1.3f, 1.0f) *
            (groove.kicks.maxOfOrNull { envelope(t, it, 0.34f) } ?: 0f)
        val snare = 0.30f * gauss(octave, 5.4f, 2.6f) *
            (groove.snares.maxOfOrNull { envelope(t, it, 0.22f) } ?: 0f)

        // Detail is fixed in time on purpose. Animating it scrolled the whole
        // curve sideways, so the shape slid left and right under the hits
        // instead of the hits standing still and pumping — which is backwards:
        // in a spectrum the horizontal axis is frequency, and frequency does
        // not move. Only the hit envelopes animate now, and they move the curve
        // up and down at the place they belong: the kick low, the snare in the
        // mids.
        val busy = min(1f, octave / 2.5f)
        val detail = 0.075f * busy * (
            0.55f * sin(TAU * (3f * x)) +
                0.30f * sin(TAU * (5f * x + 0.4f)) +
                0.15f * sin(TAU * (8f * x + 0.8f))
            ) / 0.55f

        val air = 1f - maxOf(0f, (octave - 8.2f) / 1.8f).let { it * it }
        return ((pink + kick + snare + detail) * maxOf(0f, air)).coerceIn(0f, 1f)
    }

    /** A hit's remaining loudness, wrapped around the bar so the loop is seamless. */
    private fun envelope(t: Float, onsetBeats: Float, decayBeats: Float): Float {
        var since = (t * BEATS - onsetBeats) % BEATS
        if (since < 0f) since += BEATS
        return exp(-since / decayBeats)
    }

    private fun gauss(at: Float, centre: Float, width: Float): Float {
        val d = (at - centre) / width
        return exp(-(d * d))
    }

    private const val TAU = 6.2831855f
}
