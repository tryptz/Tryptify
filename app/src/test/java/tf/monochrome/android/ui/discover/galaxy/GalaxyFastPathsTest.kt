package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/** The galaxy's cheap stand-ins look the same as what they replaced. */
class GalaxyFastPathsTest {

    @Test
    fun `fast sine is sine to a fraction of a pixel, over any range`() {
        var worst = 0f
        var x = -200f
        while (x < 200f) {
            worst = maxOf(worst, abs(fastSin(x) - sin(x)))
            x += 0.0137f
        }
        assertTrue("worst error $worst", worst < 2e-4f)
    }

    /** The dashes of one run: their lengths, and the gaps between their ends along a straight line. */
    private fun dashes(d: Dasher): List<Pair<Float, Float>> = (0 until d.count / 4).map { k ->
        val o = k * 4
        Pair(d.out[o], hypot(d.out[o + 2] - d.out[o], d.out[o + 3] - d.out[o + 1]))
    }

    @Test
    fun `a straight line is dashed on, off, on at the pattern's lengths`() {
        val d = Dasher(64)
        d.start(10f, 6f, 1000f, 1000f, 0f)
        d.line(0f, 500f, 100f, 500f)
        val runs = dashes(d)
        assertEquals(7, runs.size) // 0-10, 16-26, ..., 96-100
        runs.dropLast(1).forEachIndexed { k, (x, len) ->
            assertEquals(16f * k, x, 1e-3f)
            assertEquals(10f, len, 1e-3f)
        }
        assertEquals(4f, runs.last().second, 1e-3f)
    }

    @Test
    fun `the pattern runs on across the corners of a polyline, as a dashed path's does`() {
        val d = Dasher(64)
        d.start(10f, 6f, 1000f, 1000f, 0f)
        d.to(0f, 0f); d.to(5f, 0f); d.to(5f, 20f)
        // 5 on the first leg, then 5 more on the second (one dash of 10 split
        // at the corner), a gap of 6, then 9 of the next dash.
        val lens = dashes(d).map { it.second }
        assertEquals(listOf(5f, 5f, 9f), lens.map { (it * 1000).toInt() / 1000f })
    }

    @Test
    fun `only what is on screen becomes dashes, and the rhythm holds across the edge`() {
        val clipped = Dasher(4096)
        clipped.start(10f, 6f, 100f, 100f, 0f)
        clipped.line(-100_000f, 50f, 50f, 50f)
        // A line from far off the left edge: only the last 50 px are dashed.
        assertTrue(clipped.count / 4 <= 4)
        val whole = Dasher(100_000)
        whole.start(10f, 6f, 1e9f, 1e9f, 0f)
        whole.line(-100_000f, 50f, 50f, 50f)
        val onScreen = (0 until whole.count / 4).map { whole.out[it * 4 + 2] }.filter { it >= 0f }
        val shown = (0 until clipped.count / 4).map { clipped.out[it * 4 + 2] }
        assertEquals(onScreen.size, shown.size)
        onScreen.zip(shown).forEach { (a, b) -> assertEquals(a, b, 0.05f) }
    }

    @Test
    fun `a run never writes past its buffer`() {
        val d = Dasher(8)
        d.start(1f, 1f, 1e9f, 1e9f, 0f)
        d.line(0f, 0f, 10_000f, 0f)
        assertEquals(32, d.count)
    }
}
