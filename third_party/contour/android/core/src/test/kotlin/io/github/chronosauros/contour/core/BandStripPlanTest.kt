package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Tune band row: nothing is clipped when it all fits, and scrolling numbers are cut at about a cell's middle. */
class BandStripPlanTest {
    // fitDensity gives every screen narrower than 411 dp the 411 dp layout, so 360 dp and 412 dp phones both get a 379 dp row.
    private val rows = listOf(379.0, 395.0, 450.0)

    @Test fun `table for 1 to 10 bands`() {
        for (w in rows) for (n in 1..10) {
            val plus = n < 10
            val p = BandStripPlan.plan(w, n, plus)
            val pinned = if (plus) 2 else 1
            assertEquals(w, p.numbersDp + 2 * BandStripPlan.INSET + pinned * BandStripPlan.BUTTON, 1e-9)
            val inner = p.numbersDp - 2 * BandStripPlan.INSET
            val line = if (!p.scroll) {
                assertTrue(n * p.cellDp <= inner + 1e-9, "$n bands at $w: ${n * p.cellDp} > $inner")
                assertEquals(BandStripPlan.CELL, p.cellDp)
                "fits, cell %.1f, used %.1f of %.1f".format(java.util.Locale.ROOT, p.cellDp, n * p.cellDp, inner)
            } else {
                val whole = (inner / p.cellDp).toInt()
                val cut = inner / p.cellDp - whole
                assertTrue(p.cellDp >= BandStripPlan.CELL && p.cellDp <= BandStripPlan.MAX_CELL, "cell size")
                "scrolls, cell %.1f, numbers %.1f, %d whole + %.0f%% of the next".format(java.util.Locale.ROOT, p.cellDp, p.numbersDp, whole, cut * 100)
            }
            println("PLAN $w dp, $n bands${if (plus) " + plus" else ""}: $line")
        }
    }

    @Test fun `a very narrow row never gives a negative width`() {
        for (w in listOf(0.0, 50.0, 100.0, 103.0, 104.0)) for (n in 1..10) for (plus in listOf(true, false)) {
            val p = BandStripPlan.plan(w, n, plus)
            assertTrue(p.numbersDp >= 0.0 && p.cellDp >= BandStripPlan.CELL, "$w $n $plus $p")
        }
    }

    @Test fun `eight bands fit without scrolling on the Pixel row`() {
        assertFalse(BandStripPlan.plan(395.0, 8, true).scroll)
    }

    @Test fun `scrolling numbers are cut near the middle of a cell`() {
        val p = BandStripPlan.plan(379.0, 10, false)
        assertTrue(p.scroll)
        val inner = p.numbersDp - 2 * BandStripPlan.INSET
        assertEquals(0.5, inner / p.cellDp - (inner / p.cellDp).toInt(), 0.01)
    }
}
