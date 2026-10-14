// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import tf.monochrome.android.domain.model.PlayerGlassSettings

/**
 * The app's glass, as a note can wear it.
 *
 * A chart puts a hundred notes on screen at once, every frame, and the glass
 * shader the panels use samples what is behind it — affordable for a mini
 * player, not for a stream of arrows. So the note skin's glass is baked: each
 * image is turned into glass once, at load, and drawn as an ordinary bitmap
 * after that. What it is baked *from* is the listener's own UI-panel glass
 * ([PlayerGlassSettings], the material the mini player, the search bars and
 * the panels share), so moving those sliders moves the notes too.
 *
 * Which slider does what:
 * - [PlayerGlassSettings.bodyOpacity]: how much of the note's body is solid.
 * - [PlayerGlassSettings.rimBrightness] and [PlayerGlassSettings.edgeWidth]:
 *   how bright and how wide the lit rim is.
 * - [PlayerGlassSettings.lightAngleDeg], [PlayerGlassSettings.gloss] and
 *   [PlayerGlassSettings.reflection]: where the highlight sits and how strong.
 * - [PlayerGlassSettings.shadowDepth]: the darkening on the side away from it.
 * - [PlayerGlassSettings.frost] and [PlayerGlassSettings.tintColor]: a milky
 *   body, and the listener's colour cast.
 */
data class GlyphGlassLook(
    val fillAlpha: Float,
    val rimMix: Float,
    val rimWidth: Int,
    val lightX: Float,
    val lightY: Float,
    val gloss: Float,
    val reflection: Float,
    val shadow: Float,
    val frost: Float,
    /** ARGB, or 0 for none. */
    val tintArgb: Int,
) {
    companion object {
        /**
         * The look for [settings], or null when the listener has turned glass
         * off — "Remove liquid glass" means flat, so the plain skin is drawn.
         */
        fun from(settings: PlayerGlassSettings): GlyphGlassLook? {
            if (!settings.enabled) return null
            val s = settings.clamped()
            // The direction LiquidGlass.kt's shader lights from — the vector
            // it builds as float2(cos, -sin) in screen space — so a note is lit
            // from the side the panels around it are.
            val radians = Math.toRadians(s.lightAngleDeg.toDouble())
            return GlyphGlassLook(
                // Notes have to read at a glance, so the body never goes as
                // clear as a panel can: the colour is the information.
                fillAlpha = (0.35f + 0.5f * s.bodyOpacity).coerceIn(0.15f, 0.95f),
                rimMix = (0.1f + 0.25f * s.rimBrightness).coerceIn(0f, 0.7f),
                // At least two source pixels: the art's own outline is about
                // three, and a one-pixel rim vanishes once a lane is 200 wide.
                rimWidth = 2 + (s.edgeWidth * 2f).roundToInt(),
                lightX = cos(radians).toFloat(),
                lightY = -sin(radians).toFloat(),
                gloss = s.gloss,
                reflection = s.reflection,
                shadow = s.shadowDepth,
                frost = s.frost,
                tintArgb = s.tintColor,
            )
        }
    }
}

object GlyphGlass {

    /** How far in, in source pixels, the edge measure looks. */
    private const val MAX_DEPTH = 4

    /**
     * [pixels] (ARGB, not premultiplied, row by row) turned to glass.
     *
     * [tileVertically] is for hold bodies, which are drawn as a repeating
     * strip: their top and bottom rows are not edges, and the highlight does
     * not change along their length, or every tile would carry a band.
     * [openBottom] is for a hold's end cap, whose bottom row joins the body:
     * lit as an edge, it draws a line across every hold.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        look: GlyphGlassLook,
        tileVertically: Boolean = false,
        openBottom: Boolean = false,
    ): IntArray {
        require(pixels.size == width * height) { "pixel count does not match the size" }
        val depth = edgeDepth(pixels, width, height, tileVertically, openBottom)

        // The shape's own colour: the most vivid one near its edge — on these
        // arrows the beat colour, on a hold the body's. Not the plain average
        // of the outer ring, which on pixel art is its darkest outline and
        // tints the glass mud-brown. Grey art (receptors) has no vivid colour
        // and takes its brightest edge instead.
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        var bestScore = 0f
        var brightest = 0f
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (depth[i] == 0) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (depth[i] <= KEY_DEPTH) {
                bestScore = maxOf(bestScore, vividness(pixels[i]))
                brightest = maxOf(brightest, value(pixels[i]))
            }
        }
        if (maxX < 0) return pixels.copyOf()
        val useVivid = bestScore >= MIN_VIVID
        var rr = 0L
        var gg = 0L
        var bb = 0L
        var n = 0L
        for (i in pixels.indices) {
            if (depth[i] == 0 || depth[i] > KEY_DEPTH) continue
            val c = pixels[i]
            val keep = if (useVivid) vividness(c) >= bestScore * 0.8f else value(c) >= brightest * 0.9f
            if (!keep) continue
            rr += (c shr 16) and 0xFF
            gg += (c shr 8) and 0xFF
            bb += c and 0xFF
            n += 1
        }
        val edgeR = (rr / n).toInt()
        val edgeG = (gg / n).toInt()
        val edgeB = (bb / n).toInt()
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val halfW = ((maxX - minX) / 2f).coerceAtLeast(1f)
        val halfH = ((maxY - minY) / 2f).coerceAtLeast(1f)

        val out = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val d = depth[i]
            val source = pixels[i]
            if (d == 0) {
                out[i] = source
                continue
            }
            // Which way this pixel faces, from -1 (away from the light) to 1.
            val px = (x - cx) / halfW
            val py = if (tileVertically) 0f else (y - cy) / halfH
            val facing = (px * look.lightX + py * look.lightY).coerceIn(-1f, 1f)

            var r = ((source shr 16) and 0xFF) / 255f
            var g = ((source shr 8) and 0xFF) / 255f
            var b = (source and 0xFF) / 255f
            val sourceAlpha = ((source ushr 24) and 0xFF) / 255f
            var a: Float

            if (d <= look.rimWidth) {
                // The rim: the note's own colour, dark outline pixels lifted to
                // it, then catching light — brightest where it faces it.
                r = maxOf(r, edgeR / 255f)
                g = maxOf(g, edgeG / 255f)
                b = maxOf(b, edgeB / 255f)
                val lift = (look.rimMix + 0.3f * look.gloss * facing.coerceAtLeast(0f)).coerceIn(0f, 1f)
                r = mix(r, 1f, lift)
                g = mix(g, 1f, lift)
                b = mix(b, 1f, lift)
                a = sourceAlpha
            } else {
                // The body: the note's colour and nothing of the art's fill, a
                // little lightened so dark colours still read through it.
                r = mix(edgeR / 255f, 1f, 0.12f)
                g = mix(edgeG / 255f, 1f, 0.12f)
                b = mix(edgeB / 255f, 1f, 0.12f)
                if (look.tintArgb != 0) {
                    r = mix(r, ((look.tintArgb shr 16) and 0xFF) / 255f, 0.35f)
                    g = mix(g, ((look.tintArgb shr 8) and 0xFF) / 255f, 0.35f)
                    b = mix(b, (look.tintArgb and 0xFF) / 255f, 0.35f)
                }
                val frost = 0.5f * look.frost
                r = mix(r, 1f, frost)
                g = mix(g, 1f, frost)
                b = mix(b, 1f, frost)
                a = look.fillAlpha * sourceAlpha

                // A soft highlight on the lit side...
                val shine = look.gloss * (0.35f + 0.15f * look.reflection) * smoothstep(0.1f, 0.9f, facing)
                r = mix(r, 1f, shine)
                g = mix(g, 1f, shine)
                b = mix(b, 1f, shine)
                a = maxOf(a, shine * 0.9f)
                // ...and the far side a little darker and denser.
                val shade = 0.5f * look.shadow * smoothstep(0.2f, 0.9f, -facing)
                r *= 1f - shade
                g *= 1f - shade
                b *= 1f - shade
                a = (a + shade * 0.3f).coerceAtMost(1f)
            }
            out[i] = argb(a, r, g, b)
        }
        return out
    }

    /**
     * For each pixel, how many steps in from the outside it is: 0 outside,
     * 1 on the outermost ring, up to [MAX_DEPTH] for anything deeper.
     */
    internal fun edgeDepth(
        pixels: IntArray,
        width: Int,
        height: Int,
        tileVertically: Boolean,
        openBottom: Boolean = false,
    ): IntArray {
        val depth = IntArray(pixels.size) { if ((pixels[it] ushr 24) > ALPHA_FLOOR) MAX_DEPTH else 0 }
        for (ring in 1 until MAX_DEPTH) {
            val next = depth.copyOf()
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                if (depth[i] < MAX_DEPTH) continue
                if (touches(depth, width, height, x, y, ring - 1, tileVertically, openBottom)) next[i] = ring
            }
            System.arraycopy(next, 0, depth, 0, depth.size)
        }
        return depth
    }

    private fun touches(
        depth: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        level: Int,
        tileVertically: Boolean,
        openBottom: Boolean,
    ): Boolean {
        fun at(nx: Int, ny: Int): Int {
            if (nx < 0 || nx >= width) return 0
            val wy = when {
                ny in 0 until height -> ny
                tileVertically -> (ny + height) % height
                // Past the bottom of an end cap is the body: deep inside.
                openBottom && ny >= height -> return MAX_DEPTH
                else -> return 0
            }
            return depth[wy * width + nx]
        }
        return at(x - 1, y) == level || at(x + 1, y) == level ||
            at(x, y - 1) == level || at(x, y + 1) == level
    }

    private const val ALPHA_FLOOR = 16

    /** How deep into the shape its key colour is looked for, in source pixels. */
    private const val KEY_DEPTH = 3

    /** Below this the art is grey, and its brightest edge stands in for a colour. */
    private const val MIN_VIVID = 0.25f

    private fun value(c: Int): Float =
        maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) / 255f

    /** Saturation times brightness: how strongly a pixel carries a colour. */
    private fun vividness(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val max = maxOf(r, g, b)
        if (max == 0) return 0f
        val min = minOf(r, g, b)
        return (max - min).toFloat() / max * (max / 255f)
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun argb(a: Float, r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
