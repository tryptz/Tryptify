// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stripes that close over the field at a run's end. */
class GlyphEndStripesTest {

    private val count = 12

    @Test
    fun everyStripeIsOpenAtTheStart() {
        for (i in 0 until count) assertEquals(0f, endStripeCover(0f, i, count), 0f)
    }

    /** The results cut in from black: a stripe still open would show the field. */
    @Test
    fun everyStripeIsShutAtTheEnd() {
        for (i in 0 until count) assertEquals(1f, endStripeCover(1f, i, count), 0f)
    }

    @Test
    fun eachStripeStartsAfterTheOneAbove() {
        val p = 0.2f
        for (i in 1 until count) {
            assertTrue("stripe $i", endStripeCover(p, i, count) <= endStripeCover(p, i - 1, count))
        }
        assertTrue(endStripeCover(p, 0, count) > endStripeCover(p, count - 1, count))
    }

    @Test
    fun aStripeOnlyEverCloses() {
        for (i in 0 until count) {
            var last = 0f
            for (step in 0..100) {
                val cover = endStripeCover(step / 100f, i, count)
                assertTrue(cover >= last)
                assertTrue(cover in 0f..1f)
                last = cover
            }
        }
    }
}
