// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import org.junit.Assert.assertEquals
import org.junit.Test

/** Themes › Sizes: each part between half and twice its size, a bad value its own size. */
class GlyphStyleSizesTest {

    @Test
    fun `sizes stay between half and twice`() {
        val clamped = GlyphStyleSizes(judgement = 0.1f, combo = 7f, dancer = 1.4f).clamped()
        assertEquals(GlyphStyleSizes.MIN, clamped.judgement, 0f)
        assertEquals(GlyphStyleSizes.MAX, clamped.combo, 0f)
        assertEquals(1.4f, clamped.dancer, 0f)
    }

    @Test
    fun `a value that is not a number is the part's own size`() {
        val clamped = GlyphStyleSizes(judgement = Float.NaN, combo = Float.POSITIVE_INFINITY).clamped()
        assertEquals(1f, clamped.judgement, 0f)
        assertEquals(1f, clamped.combo, 0f)
    }
}
