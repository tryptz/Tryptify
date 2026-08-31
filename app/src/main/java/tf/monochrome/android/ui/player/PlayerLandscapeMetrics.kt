package tf.monochrome.android.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the landscape row divides its width between the artwork and the chrome.
 *
 * This is arithmetic rather than layout on purpose. The proportions here are
 * the part of the landscape player that has actually gone wrong -- a scrubber
 * stretched to the width of the screen, the transport thrown to opposite ends,
 * the whole composition leaning left -- and every one of those was a modifier
 * whose effect could only be seen on a device. Pulled out here they are
 * numbers, and [PlayerLandscapeMetricsTest] can hold them at the sizes real
 * phones actually report.
 */
object PlayerLandscapeMetrics {

    /**
     * Most of the row's width the artwork may claim.
     *
     * It is a cap and not a target: the artwork is square and the row is short,
     * so on a phone the height binds first and this never engages. It exists
     * for a window that is wide *and* tall enough to reach it -- a split-screen
     * half, a cover display -- where a square taking half the row would leave
     * the controls nothing worth having.
     */
    const val ArtWidthFraction = 0.45f

    /**
     * The row, resolved.
     *
     * [slack] is what neither takes. It is the number that decides whether the
     * player reads as composed or as packed against one edge, because it is
     * what the row's arrangement has left to distribute.
     */
    data class Row(val artSide: Dp, val chromeWidth: Dp, val slack: Dp)

    fun measure(rowWidth: Dp, rowHeight: Dp, gap: Dp, chromeMax: Dp): Row {
        val art = minOf(rowHeight, rowWidth * ArtWidthFraction).coerceAtLeast(0.dp)
        val forChrome = (rowWidth - art - gap).coerceAtLeast(0.dp)
        val chrome = minOf(chromeMax, forChrome)
        return Row(artSide = art, chromeWidth = chrome, slack = forChrome - chrome)
    }
}
