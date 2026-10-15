package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** A long title slides at reading pace, and a short overflow never jumps. */
class BackAndForthTextTest {

    @Test
    fun `nothing to show, no slide`() {
        assertEquals(0, slideMillis(0, 90f))
        assertEquals(0, slideMillis(-5, 90f))
        assertEquals(0, slideMillis(100, 0f))
    }

    @Test
    fun `the slide takes as long as the overflow at the pace`() {
        // 30 dp/s at 3x density is 90 px/s: 900 px of overflow is 10 s.
        assertEquals(10_000, slideMillis(900, 90f))
    }

    @Test
    fun `a few pixels over still glides`() {
        assertEquals(600, slideMillis(3, 90f))
    }
}
