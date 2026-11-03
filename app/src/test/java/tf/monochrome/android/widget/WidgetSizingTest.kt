package tf.monochrome.android.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget picks its layout from its real size. A 5x2 on ColorOS, a little
 * under 140dp tall, once got the 4x1 bar in a tall empty pane.
 */
class WidgetSizingTest {

    @Test
    fun aColorOsFiveByTwoGetsTheMediumLayout() {
        for (height in listOf(128, 132, 136, 141, 150)) {
            assertEquals("373x$height", WidgetLayout.Medium, pickLayout(373.dp, height.dp, showProgress = true))
        }
        // Without the progress line it fits shorter still.
        assertEquals(WidgetLayout.Medium, pickLayout(373.dp, 115.dp, showProgress = false))
    }

    @Test
    fun eachUsualSizeGetsItsLayout() {
        assertEquals(WidgetLayout.Bar, pickLayout(330.dp, 70.dp, showProgress = true))      // 4x1
        assertEquals(WidgetLayout.Bar, pickLayout(373.dp, 80.dp, showProgress = true))      // 5x1
        assertEquals(WidgetLayout.Square, pickLayout(165.dp, 141.dp, showProgress = true))  // 2x2
        assertEquals(WidgetLayout.Large, pickLayout(330.dp, 300.dp, showProgress = true))   // 4x4
        assertEquals(WidgetLayout.Large, pickLayout(373.dp, 300.dp, showProgress = true))   // 5x4
        assertEquals(WidgetLayout.Compact, pickLayout(165.dp, 70.dp, showProgress = true))  // 2x1
    }

    @Test
    fun theMediumCoverFillsTheHeightOfAFiveByTwo() {
        // 141 tall, no progress line: the cover takes the row, not a fixed 88.
        assertEquals(113.dp, mediumCover(373.dp, 141.dp, showProgress = false))
    }

    @Test
    fun theMediumLayoutAlwaysFitsWhereItIsPicked() {
        for (w in 200..500 step 5) for (h in 60..240 step 3) for (progress in listOf(true, false)) {
            val width = w.dp; val height = h.dp
            if (pickLayout(width, height, progress) != WidgetLayout.Medium) continue
            val cover = mediumCover(width, height, progress)
            val extra = if (progress) PROGRESS_SPACE else 0.dp
            assertTrue("height ${w}x$h", MEDIUM_PAD * 2 + maxOf(cover, TEXT_BLOCK) + extra <= height)
            assertTrue("width ${w}x$h", MEDIUM_PAD * 2 + cover + COVER_GAP + MEDIUM_TRANSPORT <= width)
            assertTrue("text column ${w}x$h", cover >= TEXT_BLOCK)
        }
    }
}
