package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The waterfall's mesh: the shape the old per-line drawLines/drawPath drew,
 * built as one buffer. What matters on a device — that it is a single draw —
 * cannot be tested here; what can is that the geometry is the same picture and
 * never walks off the end of its buffers.
 */
class WaterfallMeshTest {

    /** A flat line at [y] from x = 0 to 127, in the native layout (segments of x0 y0 x1 y1). */
    private fun flatLine(y: Float, points: Int = 128): FloatArray {
        val segs = FloatArray((points - 1) * 4)
        for (j in 0 until points - 1) {
            segs[j * 4] = j.toFloat()
            segs[j * 4 + 1] = y
            segs[j * 4 + 2] = (j + 1).toFloat()
            segs[j * 4 + 3] = y
        }
        return segs
    }

    private val white = VerticalGradient().apply {
        set(100f, intArrayOf(0xFFFFFFFF.toInt()), floatArrayOf(0f))
    }

    @Test
    fun `loads every point of a line, the last from the final segment's end`() {
        val mesh = WaterfallMesh()
        val n = mesh.loadPoints(flatLine(10f), 0, 127)
        assertEquals(128, n)
        mesh.addRibbon(n, 4f, 1f, white)
        // The rightmost vertex sits at x = 127, the end of the last segment.
        var maxX = 0f
        for (v in 0 until mesh.vertexCount) maxX = maxOf(maxX, mesh.verts[v * 2])
        assertEquals(127f, maxX, 1e-3f)
    }

    @Test
    fun `a ribbon is as wide as the line, with a transparent fringe each side`() {
        val mesh = WaterfallMesh()
        val n = mesh.loadPoints(flatLine(50f), 0, 127)
        mesh.addRibbon(n, 4f, 1f, white)
        assertEquals(n * WaterfallMesh.RIBBON_VERTS, mesh.vertexCount)
        // First point's four vertices, top to bottom across the line.
        val ys = (0 until 4).map { mesh.verts[it * 2 + 1] }.sorted()
        assertEquals("outer edge to outer edge is width + fringe", 5f, ys.last() - ys.first(), 1e-3f)
        val alphas = (0 until 4).map { mesh.colors[it] ushr 24 }
        assertEquals(listOf(0, 255, 255, 0), alphas)
    }

    @Test
    fun `a sub-pixel line is carried by its fringe at reduced strength`() {
        val mesh = WaterfallMesh()
        val n = mesh.loadPoints(flatLine(50f), 0, 127)
        mesh.addRibbon(n, 0.5f, 1f, white)
        val coreAlpha = mesh.colors[1] ushr 24
        assertTrue("half a pixel should be about half strength, was $coreAlpha", coreAlpha in 120..135)
    }

    @Test
    fun `ridgeline ground reaches from the line down to its baseline`() {
        val mesh = WaterfallMesh()
        val n = mesh.loadPoints(flatLine(20f), 0, 127)
        mesh.addGround(n, 90f, 0x80000000.toInt())
        assertEquals(n * 2, mesh.vertexCount)
        assertEquals(20f, mesh.verts[1], 1e-4f)
        assertEquals(90f, mesh.verts[3], 1e-4f)
        assertEquals((n - 1) * 6, mesh.indexCount)
    }

    @Test
    fun `every index points at a vertex that was written`() {
        val mesh = WaterfallMesh()
        var lines = 0
        while (mesh.hasRoom(128, withGround = true)) {
            val n = mesh.loadPoints(flatLine(lines.toFloat()), 0, 127)
            mesh.addGround(n, 100f, 0xFF000000.toInt())
            mesh.addRibbon(n, 2f, 0.5f, white)
            lines++
        }
        assertTrue("a mesh should hold a useful number of lines, held $lines", lines >= 40)
        assertTrue(mesh.vertexCount <= mesh.maxVertices)
        for (i in 0 until mesh.indexCount) {
            val v = mesh.indices[i].toInt() and 0xFFFF
            assertTrue("index $i = $v past ${mesh.vertexCount}", v < mesh.vertexCount)
        }
        assertFalse(mesh.hasRoom(128, withGround = true))
        mesh.reset()
        assertTrue(mesh.hasRoom(128, withGround = true))
    }

    @Test
    fun `the full waterfall fits in two draws`() {
        // 49 lines with Ridgeline's ground, the most a frame can hold.
        val mesh = WaterfallMesh()
        var draws = 1
        repeat(49) {
            if (!mesh.hasRoom(128, withGround = true)) {
                draws++
                mesh.reset()
            }
            val n = mesh.loadPoints(flatLine(it.toFloat()), 0, 127)
            mesh.addGround(n, 100f, 0xFF000000.toInt())
            mesh.addRibbon(n, 2f, 1f, white)
        }
        assertEquals(2, draws)
    }

    @Test
    fun `gradient runs from the top colour to the bottom one`() {
        val g = VerticalGradient().apply {
            set(100f, intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 1f))
        }
        assertEquals(0xFFFFFFFF.toInt(), g.argbAt(0f, 1f))
        assertEquals(0xFF000000.toInt(), g.argbAt(100f, 1f))
        val mid = g.argbAt(50f, 1f)
        assertTrue(((mid ushr 16) and 0xFF) in 127..128)
        assertEquals("alpha scales", 64, g.argbAt(0f, 0.25f) ushr 24)
    }
}
