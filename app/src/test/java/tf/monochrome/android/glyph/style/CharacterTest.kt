// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.style.StepManiaStyleScan.Kind

/**
 * StepMania's characters: MilkShape's text model and dances read as the
 * archive's files write them, a dance put on a skeleton by bone name, and the
 * archive's downloads told apart by their files.
 */
class CharacterTest {

    /** Two bones, a hip and a leg hanging below it; one triangle on each. */
    private val model = """
        // MilkShape 3D ASCII

        Frames: 30
        Frame: 1

        Meshes: 2
        "body" 0 0
        3
        0 0.0 0.0 0.0 0.0 0.0 0
        0 1.0 0.0 0.0 1.0 0.0 0
        0 0.0 1.0 0.0 0.0 1.0 0
        1
        0 0.0 0.0 1.0
        1
        0 0 1 2 0 0 0 1
        "leg" 0 1
        3
        0 0.0 -1.0 0.0 0.0 0.0 1
        0 0.0 -2.0 0.0 0.0 1.0 1
        0 0.5 -2.0 0.0 1.0 1.0 1
        1
        0 0.0 0.0 1.0
        1
        0 0 1 2 0 0 0 1

        Materials: 1
        "skin"
        0.2 0.2 0.2 1.0
        1.0 1.0 1.0 1.0
        1.0 1.0 1.0 1.0
        0.0 0.0 0.0 1.0
        4.0
        1.0
        "skin.png"
        ""

        Bones: 2
        "hip"
        ""
        8 0.0 0.0 0.0 0.0 0.0 0.0
        0
        0
        "leg"
        "hip"
        8 0.0 -1.0 0.0 0.0 0.0 0.0
        0
        0
    """.trimIndent()

    /** The leg turned a quarter turn about z by the dance, the hip still. */
    private val dance = """
        Bones: 2
        "Hip"
        ""
        8 0.0 0.0 0.0 0.0 0.0 0.0
        1
        1.0 0.0 0.0 0.0
        1
        1.0 0.0 0.0 0.0
        "LEG"
        "hip"
        8 0.0 -1.0 0.0 0.0 0.0 0.0
        2
        1.0 0.0 0.0 0.0
        11.0 0.0 0.0 0.0
        2
        1.0 0.0 0.0 0.0
        11.0 0.0 0.0 ${PI / 2}
    """.trimIndent()

    @Test
    fun aModelReadsAsTheArchivesFilesWriteIt() {
        val m = Milkshape.parse(model)
        assertEquals(2, m.meshes.size)
        assertEquals(listOf("hip", "leg"), m.bones.map { it.name })
        assertEquals("skin.png", m.materials.single().texture)
        assertEquals(1, m.meshes[1].bones[0])
        assertEquals(listOf(0, 1, 2), m.meshes[0].triangles.toList())
        assertEquals(11f, Milkshape.parse(dance).length)
    }

    @Test
    fun aBrokenModelIsRefusedNotCrashedOn() {
        assertThrows(Milkshape.FormatException::class.java) { Milkshape.parse("Meshes: 1\n\"x\" 0 0\n1\n0 1 2\n") }
        assertThrows(Milkshape.FormatException::class.java) {
            // A triangle naming a vertex that is not there.
            Milkshape.parse("Meshes: 1\n\"x\" 0 0\n1\n0 0 0 0 0 0 -1\n0\n1\n0 0 1 9 0 0 0 1\n")
        }
    }

    @Test
    fun atRestTheModelIsWhereItWasMade() {
        val m = Milkshape.parse(model)
        val pose = CharacterPose(m)
        val out = FloatArray(pose.vertexCount * 3)
        pose.pose(null, 1f, out)
        val made = m.meshes.flatMap { it.positions.toList() }
        made.forEachIndexed { i, v -> assertEquals(v, out[i], 1e-5f) }
    }

    @Test
    fun aDanceTurnsABoneAndWhatHangsFromIt() {
        val m = Milkshape.parse(model)
        val pose = CharacterPose(m)
        val bound = pose.bind(Milkshape.parse(dance))
        // Matched by name, whatever its case.
        assertTrue(bound.all { it != null })
        val out = FloatArray(pose.vertexCount * 3)
        // Frame 11: the leg turned a quarter about z at its joint (0, -1), so
        // its foot, one below the joint, swings out to the right of it.
        pose.pose(bound, 11f, out)
        val foot = 4 * 3 // the leg's second vertex, (0, -2) at rest
        assertEquals(1f, out[foot], 1e-4f)
        assertEquals(-1f, out[foot + 1], 1e-4f)
        // The hip's own vertices stay where they were.
        assertEquals(1f, out[3], 1e-5f)
        // Halfway, it is halfway round (an eighth turn), along the arc.
        pose.pose(bound, 6f, out)
        assertEquals(kotlin.math.sin(PI / 4).toFloat(), out[foot], 1e-3f)
        assertTrue(abs(out[foot + 1] - (-1f - kotlin.math.cos(PI / 4).toFloat())) < 1e-3f)
    }

    @Test
    fun charactersAndTheDancesTheyShareAreFound() {
        val found = StepManiaStyleScan.scan(
            listOf(
                "Megaman Star Force - Harp Note/model.txt",
                "Megaman Star Force - Harp Note/character.ini",
                "Megaman Star Force - Harp Note/bs003_00.png",
                "Megaman Star Force - Harp Note/Dance/Dance0001.redir",
                "_DDRPC_common_Dance0001.bones.txt",
                "_DDRPC_common_Dance0002.bones.txt",
                "_DDRPC_common_Rest.bones.txt",
                "DancePad-DDR.txt",
            ),
        )
        assertEquals(
            listOf(
                StepManiaStyleScan.Found(Kind.CHARACTER, "Megaman Star Force - Harp Note", "Megaman Star Force - Harp Note"),
                StepManiaStyleScan.Found(Kind.DANCES, "Dances", ""),
            ),
            found,
        )
    }
}
