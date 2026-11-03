// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.asset.GlyphBeatDivision

/**
 * Noteskin parts StepMania draws as 3D models (Opal's tap note and mine):
 * found as the model, never as the texture sheet wrapped round it, and drawn
 * face on, the right way up, nearest in front.
 */
class NoteSkinModelTest {

    private fun model(texture0: String, texture1: String) = """
        // MilkShape 3D ASCII

        Frames: 30
        Frame: 1

        Meshes: 2
        "front" 0 1
        3
        0 -4.0 4.0 5.0 0.0 0.0 -1
        0 4.0 4.0 5.0 0.0 0.0 -1
        0 0.0 12.0 5.0 0.0 0.0 -1
        1
        0 0.0 0.0 1.0
        1
        0 0 1 2 0 0 0 1
        "back" 0 0
        4
        0 -16.0 0.0 0.0 0.0 1.0 -1
        0 16.0 0.0 0.0 1.0 1.0 -1
        0 16.0 16.0 0.0 1.0 0.0 -1
        0 -16.0 16.0 0.0 0.0 0.0 -1
        1
        0 0.0 0.0 1.0
        2
        0 0 1 2 0 0 0 1
        0 0 2 3 0 0 0 1

        Materials: 2
        "back"
        0.2 0.2 0.2 1.0
        0.8 0.8 0.8 1.0
        0.0 0.0 0.0 1.0
        0.0 0.0 0.0 1.0
        0.0
        1.0
        "$texture0"
        ""
        "front"
        0.2 0.2 0.2 1.0
        0.8 0.8 0.8 1.0
        0.0 0.0 0.0 1.0
        0.0 0.0 0.0 1.0
        0.0
        1.0
        "$texture1"
        ""

        Bones: 0
    """.trimIndent()

    @Test
    fun aModelTapNoteIsTheModelAndItsTexturesAreFoundThroughAnimatedOnes() {
        val files = mapOf(
            "o/DDR Arrow Set.txt" to "A readme, not a model.",
            "o/Down Tap Note.lua" to "local t = Def.Model {\n\tMeshes=NOTESKIN:GetPath('_down','tap note model');\n};\nreturn t;",
            "o/_down tap note model.txt" to model("textures\\Tap Note parts (mipmaps).png", "textures\\Tap Note ani texture.ini"),
            "o/textures/Tap Note parts (mipmaps).png" to "",
            "o/textures/Tap Note ani texture.ini" to "[AnimatedTexture]\nTexVelocityY=0.125\nFrame0000=Tap Note parts (mipmaps).png\nDelay0000=1.0\n",
        )
        val resolver = NoteSkinResolver(files.keys, "o") { files[it] }
        val tap = resolver.taps()[GlyphBeatDivision.QUARTER]
        assertNotNull(tap)
        assertTrue(tap!!.image.isModel)
        assertEquals("o/_down tap note model.txt", tap.image.path)
        val parsed = Milkshape.parse(files.getValue(tap.image.path))
        for (material in parsed.materials) {
            assertEquals("o/textures/Tap Note parts (mipmaps).png", resolver.modelTexture(tap.image.path, material.texture))
        }
    }

    @Test
    fun aPlainImageIsStillPreferredToAModel() {
        val files = mapOf(
            "s/Down Tap Note 4x4.png" to "",
            "s/Down Tap Note.txt" to model("a.png", "b.png"),
        )
        val tap = NoteSkinResolver(files.keys, "s") { files[it] }.taps().getValue(GlyphBeatDivision.QUARTER)
        assertFalse(tap.image.isModel)
        assertEquals("s/Down Tap Note 4x4.png", tap.image.path)
    }

    @Test
    fun aModelIsDrawnFaceOnUpTheRightWayWithTheNearestInFront() {
        val parsed = Milkshape.parse(model("halves.png", "green.png"))
        // Red on the left half, blue on the right; green for the front triangle.
        val halves = ModelSnapshot.Texture(intArrayOf(RED, BLUE), 2, 1)
        val green = ModelSnapshot.Texture(intArrayOf(GREEN), 1, 1)
        val size = 64
        val pixels = ModelSnapshot.render(parsed, size) { if (it == 0) halves else green }!!
        // A 64-unit lane in 64 pixels: one unit a pixel, the origin in the middle, y up.
        fun at(x: Int, y: Int) = pixels[(size / 2 - y) * size + size / 2 + x]
        assertEquals(RED, at(-12, 2))
        assertEquals(BLUE, at(12, 2))
        // The front triangle (z 5) over the back quad, though it comes first in the file.
        assertEquals(GREEN, at(0, 7))
        // Nothing below the origin: the quad is drawn above it, not flipped.
        assertEquals(0, at(0, -8) ushr 24)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
