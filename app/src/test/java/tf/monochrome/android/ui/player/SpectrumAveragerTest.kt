package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.SpectrumAnalysisType
import tf.monochrome.android.domain.model.SpectrumWaterfallSettings
import kotlin.math.exp

/**
 * The overlay's Type and Avg Time. The real-time average at the default time
 * has to be the overlay it replaced, step for step: anyone who never opens the
 * new controls should not see the spectrum move differently.
 */
class SpectrumAveragerTest {

    private val floor = -24f
    private val frame = 1f / 60f
    private val defaultMs = SpectrumWaterfallSettings.DEFAULT_AVG_TIME_MS

    private fun averager() = SpectrumAverager(4, floor)

    @Test
    fun `default real-time average matches the old fixed attack and release`() {
        val a = averager()
        // Rise from the floor toward 0 dB: the old attack was 0.55 per 60 Hz frame.
        a.process(floatArrayOf(0f, 0f, 0f, 0f), frame, SpectrumAnalysisType.RT_AVG, defaultMs)
        val oldAttack = 1f - exp(-0.55f)
        assertEquals(floor + (0f - floor) * oldAttack, a.values[0], 0.05f)

        // Fall back toward the floor: the old release was 0.12 per frame.
        val before = a.values[0]
        a.process(floatArrayOf(floor, floor, floor, floor), frame, SpectrumAnalysisType.RT_AVG, defaultMs)
        val oldRelease = 1f - exp(-0.12f)
        assertEquals(before + (floor - before) * oldRelease, a.values[0], 0.05f)
    }

    @Test
    fun `a longer averaging time falls more slowly`() {
        val fast = averager()
        val slow = averager()
        val loud = floatArrayOf(0f, 0f, 0f, 0f)
        val quiet = floatArrayOf(floor, floor, floor, floor)
        repeat(60) {
            fast.process(loud, frame, SpectrumAnalysisType.RT_AVG, 100f)
            slow.process(loud, frame, SpectrumAnalysisType.RT_AVG, 2000f)
        }
        repeat(10) {
            fast.process(quiet, frame, SpectrumAnalysisType.RT_AVG, 100f)
            slow.process(quiet, frame, SpectrumAnalysisType.RT_AVG, 2000f)
        }
        assertTrue("slow ${slow.values[0]} should sit above fast ${fast.values[0]}", slow.values[0] > fast.values[0] + 5f)
    }

    @Test
    fun `zero averaging time follows the signal exactly`() {
        val a = averager()
        a.process(floatArrayOf(-3f, -6f, -9f, -12f), frame, SpectrumAnalysisType.RT_AVG, 0f)
        assertEquals(-3f, a.values[0], 1e-4f)
        assertEquals(-12f, a.values[3], 1e-4f)
    }

    @Test
    fun `real-time max holds for the hold time and then falls`() {
        val a = averager()
        a.process(floatArrayOf(0f, 0f, 0f, 0f), frame, SpectrumAnalysisType.RT_MAX, 500f)
        assertEquals("a peak is taken at once", 0f, a.values[0], 1e-4f)
        val quiet = floatArrayOf(floor, floor, floor, floor)
        // 0.4 s in: still inside the 0.5 s hold.
        repeat(24) { a.process(quiet, frame, SpectrumAnalysisType.RT_MAX, 500f) }
        assertEquals(0f, a.values[0], 1e-4f)
        // Another 0.5 s: the hold has run out and it has been falling.
        repeat(30) { a.process(quiet, frame, SpectrumAnalysisType.RT_MAX, 500f) }
        assertTrue("should have fallen, is ${a.values[0]}", a.values[0] < -3f)
        assertTrue("never below the signal", a.values[0] >= floor)
    }

    @Test
    fun `long-term average is a power average`() {
        val a = averager()
        // Equal time at 0 dB and at -60 dB: half the power of 0 dB, so -3 dB,
        // where a dB average would have said -30.
        repeat(50) { a.process(floatArrayOf(0f, 0f, 0f, 0f), frame, SpectrumAnalysisType.AVG, 0f) }
        repeat(50) { a.process(floatArrayOf(-60f, -60f, -60f, -60f), frame, SpectrumAnalysisType.AVG, 0f) }
        assertEquals(-3.01f, a.values[0], 0.05f)
    }

    @Test
    fun `long-term max keeps the highest and reset forgets it`() {
        val a = averager()
        a.process(floatArrayOf(-2f, -2f, -2f, -2f), frame, SpectrumAnalysisType.MAX, 0f)
        a.process(floatArrayOf(-20f, -20f, -20f, -20f), frame, SpectrumAnalysisType.MAX, 0f)
        assertEquals(-2f, a.values[0], 1e-4f)
        a.reset()
        a.process(floatArrayOf(-20f, -20f, -20f, -20f), frame, SpectrumAnalysisType.MAX, 0f)
        assertEquals(-20f, a.values[0], 1e-4f)
    }

    @Test
    fun `switching type starts the history over`() {
        val a = averager()
        a.process(floatArrayOf(0f, 0f, 0f, 0f), frame, SpectrumAnalysisType.MAX, 0f)
        a.process(floatArrayOf(-20f, -20f, -20f, -20f), frame, SpectrumAnalysisType.AVG, 0f)
        assertEquals("the max must not carry into the average", -20f, a.values[0], 0.01f)
    }

    @Test
    fun `reports how far the picture moved, and nothing once it has settled`() {
        val a = averager()
        val moved = a.process(floatArrayOf(0f, 0f, 0f, 0f), frame, SpectrumAnalysisType.RT_AVG, 0f)
        assertEquals(24f, moved, 1e-3f)
        val still = a.process(floatArrayOf(0f, 0f, 0f, 0f), frame, SpectrumAnalysisType.RT_AVG, 0f)
        assertEquals(0f, still, 1e-6f)
    }
}
