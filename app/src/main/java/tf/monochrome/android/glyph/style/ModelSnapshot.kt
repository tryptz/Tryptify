// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

/**
 * A picture of a noteskin part that StepMania draws as a 3D model (a
 * MilkShape `.txt` its Lua loads with `Def.Model`), taken once at import as
 * the game would show it at rest: face on, from the front (+z), y up, its
 * origin the note's centre, in StepMania's units, where a lane is 64 wide.
 * A model larger than that is fitted instead, so none of it is cut off.
 *
 * Textured triangles, far to near, edges smoothed by drawing at twice the
 * size and halving. Its animation (a texture that scrolls, a bone that
 * turns) is not taken: the part is baked as one still picture, like every
 * other. Pure Kotlin on ARGB pixels, so it draws the same on the phone and
 * in a test.
 */
object ModelSnapshot {
    /** A decoded texture, ARGB, row by row. */
    class Texture(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * [model] in a [size]×[size] picture (ARGB, row by row), each material's
     * texture from [texture] (null draws it plain grey). Null when it has
     * nothing to draw.
     */
    fun render(model: Milkshape.Model, size: Int, texture: (Int) -> Texture?): IntArray? {
        val triangles = model.meshes.sumOf { it.triangles.size / 3 }
        if (triangles == 0 || size <= 0) return null
        var reach = 0f
        for (mesh in model.meshes) {
            for (v in 0 until mesh.vertexCount) {
                reach = maxOf(reach, kotlin.math.abs(mesh.positions[v * 3]), kotlin.math.abs(mesh.positions[v * 3 + 1]))
            }
        }
        if (reach <= 0f) return null
        val half = maxOf(LANE / 2, reach * FIT_MARGIN)
        val big = size * SUPERSAMPLE
        val scale = big / (2 * half)
        val canvas = IntArray(big * big)

        // Every triangle with its depth, drawn from the back.
        val order = ArrayList<Triple<Milkshape.Mesh, Int, Float>>(triangles)
        for (mesh in model.meshes) {
            for (t in 0 until mesh.triangles.size / 3) {
                var depth = 0f
                for (k in 0..2) depth += mesh.positions[mesh.triangles[t * 3 + k] * 3 + 2]
                order += Triple(mesh, t, depth)
            }
        }
        order.sortBy { it.third }
        val textures = HashMap<Int, Texture?>()
        val xs = FloatArray(3)
        val ys = FloatArray(3)
        val us = FloatArray(3)
        val vs = FloatArray(3)
        for ((mesh, t, _) in order) {
            for (k in 0..2) {
                val v = mesh.triangles[t * 3 + k]
                xs[k] = big / 2f + mesh.positions[v * 3] * scale
                ys[k] = big / 2f - mesh.positions[v * 3 + 1] * scale
                us[k] = mesh.uvs[v * 2]
                vs[k] = mesh.uvs[v * 2 + 1]
            }
            val material = mesh.material
            val image = if (material >= 0) textures.getOrPut(material) { texture(material) } else null
            val alpha = model.materials.getOrNull(material)?.alpha ?: 1f
            fill(canvas, big, xs, ys, us, vs, image, alpha)
        }
        return halved(canvas, big)
    }

    /** One triangle, its texture sampled at each pixel's centre and laid over what is there. */
    private fun fill(
        canvas: IntArray, side: Int,
        xs: FloatArray, ys: FloatArray, us: FloatArray, vs: FloatArray,
        texture: Texture?, alpha: Float,
    ) {
        val area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0])
        if (kotlin.math.abs(area) < 1e-6f) return
        val minX = maxOf(0, kotlin.math.floor(minOf(xs[0], xs[1], xs[2])).toInt())
        val maxX = minOf(side - 1, kotlin.math.ceil(maxOf(xs[0], xs[1], xs[2])).toInt())
        val minY = maxOf(0, kotlin.math.floor(minOf(ys[0], ys[1], ys[2])).toInt())
        val maxY = minOf(side - 1, kotlin.math.ceil(maxOf(ys[0], ys[1], ys[2])).toInt())
        for (py in minY..maxY) {
            val cy = py + 0.5f
            for (px in minX..maxX) {
                val cx = px + 0.5f
                // Barycentric weights; inside when all three agree in sign with the area.
                val w0 = ((xs[1] - cx) * (ys[2] - cy) - (xs[2] - cx) * (ys[1] - cy)) / area
                val w1 = ((xs[2] - cx) * (ys[0] - cy) - (xs[0] - cx) * (ys[2] - cy)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val colour = if (texture == null) {
                    PLAIN
                } else {
                    val u = w0 * us[0] + w1 * us[1] + w2 * us[2]
                    val v = w0 * vs[0] + w1 * vs[1] + w2 * vs[2]
                    sample(texture, u, v)
                }
                val i = py * side + px
                canvas[i] = over(colour, canvas[i], alpha)
            }
        }
    }

    /** The texel at (u, v), wrapping, as the textures repeat. */
    private fun sample(texture: Texture, u: Float, v: Float): Int {
        val x = Math.floorMod(kotlin.math.floor(u * texture.width).toInt(), texture.width)
        val y = Math.floorMod(kotlin.math.floor(v * texture.height).toInt(), texture.height)
        return texture.pixels[y * texture.width + x]
    }

    /** [top] (its alpha scaled by [alpha]) over [under], both straight ARGB. */
    private fun over(top: Int, under: Int, alpha: Float): Int {
        val ta = ((top ushr 24) * alpha).toInt().coerceIn(0, 255)
        if (ta == 255) return top or (0xFF shl 24)
        if (ta == 0) return under
        val ua = under ushr 24
        val outA = ta + ua * (255 - ta) / 255
        if (outA == 0) return 0
        fun channel(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val u = (under shr shift) and 0xFF
            return ((t * ta + u * ua * (255 - ta) / 255) / outA).coerceIn(0, 255)
        }
        return (outA shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Each 2×2 block averaged, weighted by alpha, into one pixel. */
    private fun halved(canvas: IntArray, big: Int): IntArray {
        val side = big / SUPERSAMPLE
        val out = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dy in 0 until SUPERSAMPLE) for (dx in 0 until SUPERSAMPLE) {
                    val p = canvas[(y * SUPERSAMPLE + dy) * big + x * SUPERSAMPLE + dx]
                    val pa = p ushr 24
                    a += pa
                    r += ((p shr 16) and 0xFF) * pa
                    g += ((p shr 8) and 0xFF) * pa
                    b += (p and 0xFF) * pa
                }
                if (a == 0) continue
                out[y * side + x] = ((a / (SUPERSAMPLE * SUPERSAMPLE)) shl 24) or
                    ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
            }
        }
        return out
    }

    /** StepMania's lane, in its units. */
    private const val LANE = 64f
    private const val FIT_MARGIN = 1.04f
    private const val SUPERSAMPLE = 2
    private const val PLAIN = 0xFFC8C8C8.toInt()
}
