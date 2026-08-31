package tf.monochrome.android.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape row's proportions, at the sizes real windows report.
 *
 * Every bug this pins was invisible in code review and obvious on a phone: a
 * scrubber the width of the screen, transport buttons at opposite edges, the
 * whole player packed against the left with the slack piled up on the right.
 * They came of one arithmetic mistake -- giving the chrome whatever the artwork
 * did not take -- and arithmetic is testable.
 */
class PlayerLandscapeMetricsTest {

    private val gap = 20.dp
    private val chromeMax = PlayerDesignTokens.ChromeMaxWidth

    /** A ~2.2:1 phone on its side, after system bars, top bar and the strip. */
    private fun phone() = PlayerLandscapeMetrics.measure(
        rowWidth = 876.dp, rowHeight = 304.dp, gap = gap, chromeMax = chromeMax,
    )

    @Test
    fun `on a phone the artwork is bound by the row's height, not its width`() {
        val m = phone()
        assertEquals(304f, m.artSide.value, 0.01f)
        // The 45% cap would have allowed far more, so it is not what decided
        // this -- which is the whole reason the width cap alone never helped.
        assertTrue(m.artSide < 876.dp * PlayerLandscapeMetrics.ArtWidthFraction)
    }

    @Test
    fun `the chrome takes its cap and not the whole remainder`() {
        val m = phone()
        assertEquals(chromeMax.value, m.chromeWidth.value, 0.01f)
        // What it would have taken if it filled: 552dp, a scrubber wider than a
        // tablet's whole screen.
        assertEquals(552f, (876.dp - m.artSide - gap).value, 0.01f)
    }

    @Test
    fun `the leftover is real, and it is what the row has to centre`() {
        val m = phone()
        assertEquals(92f, m.slack.value, 0.01f)
        // Centred, that is 46dp either side of the pair rather than 92dp all
        // collected on the right.
        assertTrue(m.slack > 0.dp)
    }

    @Test
    fun `the pieces always add up to the row`() {
        val sizes = listOf(
            876.dp to 304.dp,   // 2.2:1 phone
            740.dp to 300.dp,   // 16:9 phone
            1180.dp to 460.dp,  // small foldable, unfolded
            640.dp to 300.dp,   // split-screen half
            360.dp to 300.dp,   // very narrow
        )
        for ((w, h) in sizes) {
            val m = PlayerLandscapeMetrics.measure(w, h, gap, chromeMax)
            assertEquals(
                "row $w x $h", w.value, (m.artSide + gap + m.chromeWidth + m.slack).value, 0.01f,
            )
        }
    }

    @Test
    fun `a wide, tall window is where the width cap finally bites`() {
        // Tall enough that the height no longer binds: unchecked, the square
        // would take 800dp of a 1200dp row and leave the controls a sliver.
        val m = PlayerLandscapeMetrics.measure(1200.dp, 800.dp, gap, chromeMax)
        assertEquals(540f, m.artSide.value, 0.01f)
        assertEquals(chromeMax.value, m.chromeWidth.value, 0.01f)
    }

    @Test
    fun `a narrow row squeezes the chrome rather than overflowing`() {
        val m = PlayerLandscapeMetrics.measure(360.dp, 300.dp, gap, chromeMax)
        assertEquals(162f, m.artSide.value, 0.01f)
        assertEquals(178f, m.chromeWidth.value, 0.01f)
        assertEquals(0f, m.slack.value, 0.01f)
    }

    @Test
    fun `a row too small even for the gap reports nothing negative`() {
        val m = PlayerLandscapeMetrics.measure(10.dp, 10.dp, gap, chromeMax)
        assertTrue(m.artSide >= 0.dp)
        assertTrue(m.chromeWidth >= 0.dp)
        assertTrue(m.slack >= 0.dp)
    }

    @Test
    fun `expanded lyrics close the gap without changing the split`() {
        val withGap = PlayerLandscapeMetrics.measure(876.dp, 304.dp, 20.dp, chromeMax)
        val noGap = PlayerLandscapeMetrics.measure(876.dp, 304.dp, 0.dp, chromeMax)
        assertEquals(withGap.artSide.value, noGap.artSide.value, 0.01f)
        assertEquals(withGap.chromeWidth.value, noGap.chromeWidth.value, 0.01f)
        assertEquals(withGap.slack.value + 20f, noGap.slack.value, 0.01f)
    }
}
