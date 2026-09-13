package tf.monochrome.android.ui.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry behind the play/pause morph.
 *
 * The first cut of this shipped visibly wrong, and none of it was wrong in a
 * way the compiler or a screenshot-free review could see: the two halves were
 * rounded independently, so the seam down the middle of the triangle was given
 * a corner radius, and the shape pinched into an hourglass with the point
 * hanging off it as if it were a separate glyph. What follows is the set of
 * properties that had to hold for it to be one shape.
 */
class PlayPauseMorphTest {

    private val d = 64f
    private val cx = 32f
    private val cy = 32f

    private fun quads(morph: Float) = playPauseQuads(morph, cx, cy, d, scale = 1f)

    private fun assertSame(expected: Offset, actual: Offset, what: String) {
        assertEquals("$what x", expected.x, actual.x, 0.001f)
        assertEquals("$what y", expected.y, actual.y, 0.001f)
    }

    @Test
    fun `at rest the two halves share the seam exactly`() {
        val (left, right) = quads(0f)
        // Left quad's right edge (TR, BR) is the right quad's left edge (TL, BL).
        // A gap here is a hairline of glass through the middle of the triangle;
        // an overlap is a double-cleared band. Both read as a broken glyph.
        assertSame(left[1], right[0], "top of seam")
        assertSame(left[2], right[3], "bottom of seam")
    }

    @Test
    fun `at rest the point is a point`() {
        val (_, right) = quads(0f)
        // The apex is carried as two coincident corners so it has somewhere to
        // travel to; roundedPolygon folds them back into one.
        assertSame(right[1], right[2], "apex pair")
        assertEquals(cy, right[1].y, 0.001f)
    }

    @Test
    fun `at rest the outline is the triangle it always was`() {
        val (left, right) = quads(0f)
        val w = d * 0.32f
        val h = d * 0.34f
        val tcx = cx - d * 0.32f * 0.06f
        // The three corners of the original glyph, unchanged: flat back edge at
        // full height, apex on the centre line.
        assertSame(Offset(tcx - w * 0.40f, cy - h / 2f), left[0], "back top")
        assertSame(Offset(tcx - w * 0.40f, cy + h / 2f), left[3], "back bottom")
        assertSame(Offset(tcx + w * 0.60f, cy), right[1], "apex")
        // Halfway along, a triangle is half as tall — this is what makes the
        // left half a trapezoid and not a rectangle.
        assertEquals(cy - h / 4f, left[1].y, 0.001f)
        assertEquals(cy + h / 4f, left[2].y, 0.001f)
    }

    @Test
    fun `at the far end the halves are the two bars`() {
        val (left, right) = quads(1f)
        val barW = d * 0.11f
        val barH = d * 0.34f
        val gap = d * 0.10f
        assertSame(Offset(cx - gap / 2f - barW, cy - barH / 2f), left[0], "left bar TL")
        assertSame(Offset(cx - gap / 2f, cy + barH / 2f), left[2], "left bar BR")
        assertSame(Offset(cx + gap / 2f, cy - barH / 2f), right[0], "right bar TL")
        assertSame(Offset(cx + gap / 2f + barW, cy + barH / 2f), right[2], "right bar BR")
        // Separated by the gap, which is the whole point of arriving here.
        assertTrue("bars must not touch", right[0].x > left[1].x)
    }

    @Test
    fun `corner order is TL TR BR BL throughout the morph`() {
        // Wrong ordering does not fail — it folds the shape through itself
        // partway across, which is only visible in motion.
        var t = 0f
        while (t <= 1f) {
            for ((name, q) in listOf("left" to quads(t).first, "right" to quads(t).second)) {
                assertTrue("$name TL above BL at $t", q[0].y <= q[3].y)
                assertTrue("$name TR above BR at $t", q[1].y <= q[2].y)
                assertTrue("$name TL left of TR at $t", q[0].x <= q[1].x)
                assertTrue("$name BL left of BR at $t", q[3].x <= q[2].x)
            }
            t += 0.05f
        }
    }

    @Test
    fun `the seam is sharp at rest and round once the bars are apart`() {
        val cut = 3f
        val (l0, r0) = playPauseCuts(cut, 0f)
        // Left quad's TR/BR and right quad's TL/BL are the seam.
        assertEquals(0f, l0[1], 0f)
        assertEquals(0f, l0[2], 0f)
        assertEquals(0f, r0[0], 0f)
        assertEquals(0f, r0[3], 0f)
        // Everything on the outline stays round, including the apex pair.
        assertEquals(cut, l0[0], 0f)
        assertEquals(cut, l0[3], 0f)
        assertEquals(cut, r0[1], 0f)
        assertEquals(cut, r0[2], 0f)

        val (l1, r1) = playPauseCuts(cut, 1f)
        assertTrue("every corner of a bar is round", (l1 + r1).all { it == cut })
    }

    @Test
    fun `the seam radius opens monotonically`() {
        val cut = 3f
        var previous = -1f
        var t = 0f
        while (t <= 1f) {
            val seam = playPauseCuts(cut, t).first[1]
            assertTrue("seam radius must not go backwards at $t", seam >= previous)
            assertTrue("seam radius must not exceed the corner radius", seam <= cut + 1e-4f)
            previous = seam
            t += 0.05f
        }
    }

    @Test
    fun `morph is clamped so a spring overshoot cannot invert the glyph`() {
        assertEquals(quads(0f).first[0].x, quads(-0.4f).first[0].x, 0.001f)
        assertEquals(quads(1f).second[1].x, quads(1.4f).second[1].x, 0.001f)
        assertEquals(0f, playPauseCuts(3f, -0.4f).first[1], 0f)
        assertEquals(3f, playPauseCuts(3f, 1.4f).first[1], 0f)
    }
}
