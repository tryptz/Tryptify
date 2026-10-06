package tf.monochrome.android.ui.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LensClipShapeTest {

    private val density = Density(3f)
    private val disc = Size(216f, 216f)

    private fun corner(outline: Outline): Float =
        (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x

    @Test
    fun `an infinite rounded corner throws, which is what crashed the player`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RoundedCornerShape(Dp.Infinity).createOutline(disc, LayoutDirection.Ltr, density)
        }
        assertTrue(e.message.orEmpty().contains("NaN"))
    }

    @Test
    fun `an infinite lens corner clips to a full circle`() {
        val outline = lensClipShape(Dp.Infinity).createOutline(disc, LayoutDirection.Ltr, density)
        assertEquals(108f, corner(outline), 0.001f)
    }

    @Test
    fun `a pill keeps round ends on its short side`() {
        val outline = lensClipShape(Dp.Infinity).createOutline(Size(900f, 192f), LayoutDirection.Ltr, density)
        assertEquals(96f, corner(outline), 0.001f)
    }

    @Test
    fun `a finite corner is passed through unchanged`() {
        val outline = lensClipShape(16.dp).createOutline(Size(1020f, 192f), LayoutDirection.Ltr, density)
        assertEquals(48f, corner(outline), 0.001f)
    }
}
