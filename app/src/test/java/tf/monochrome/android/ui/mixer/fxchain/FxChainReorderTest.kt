package tf.monochrome.android.ui.mixer.fxchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FxChainReorderTest {

    // Three cards stacked at y=[0,100),[100,200),[200,300). The `others` list
    // excludes the dragged card (its index is passed via the ReorderItem indices).

    private val topCard = ReorderItem(index = 0, top = 0, size = 100)
    private val midCard = ReorderItem(index = 1, top = 100, size = 100)
    private val botCard = ReorderItem(index = 2, top = 200, size = 100)

    @Test
    fun `dragging middle card down into bottom span targets bottom index`() {
        val others = listOf(topCard, botCard)
        assertEquals(2, computeSwap(draggedCenterY = 250f, others = others))
    }

    @Test
    fun `dragging middle card up into top span targets top index`() {
        val others = listOf(topCard, botCard)
        assertEquals(0, computeSwap(draggedCenterY = 50f, others = others))
    }

    @Test
    fun `center resting in the dragged card's own gap is a no-op`() {
        val others = listOf(topCard, botCard)
        assertNull(computeSwap(draggedCenterY = 150f, others = others))
    }

    @Test
    fun `dragging the last card past the end clamps to no move`() {
        // Dragging the bottom card: others are the two above it.
        val others = listOf(topCard, midCard)
        assertNull(computeSwap(draggedCenterY = 400f, others = others))
    }

    @Test
    fun `dragging the first card above the top clamps to no move`() {
        // Dragging the top card: others are the two below it.
        val others = listOf(midCard, botCard)
        assertNull(computeSwap(draggedCenterY = -50f, others = others))
    }

    @Test
    fun `center exactly on a boundary counts as inside that card`() {
        val others = listOf(topCard, botCard)
        // 200 is the top edge of the bottom card [200,300].
        assertEquals(2, computeSwap(draggedCenterY = 200f, others = others))
    }
}
