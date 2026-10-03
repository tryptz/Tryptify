package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The waterfall's FPS cap, frame by frame, on simulated displays. */
class WaterfallFramePacingTest {

    /** Draws counted over [seconds] on a [refreshHz] display with this cap. */
    private fun drawsPerSecond(refreshHz: Double, targetFps: Int, vsync: Boolean, seconds: Int = 4): Double {
        val period = 1_000_000_000.0 / refreshHz
        var due = 0L
        var draws = 0
        val frames = (refreshHz * seconds).toLong()
        for (i in 0 until frames) {
            val now = (i * period).toLong() + 1
            if (waterfallFrameDue(i, now, due, period, targetFps, vsync)) {
                draws++
                due = waterfallNextDue(now, due, targetFps)
            }
        }
        return draws.toDouble() / seconds
    }

    @Test
    fun `no cap draws every refresh`() {
        assertEquals(120.0, drawsPerSecond(120.0, 0, vsync = true), 0.5)
        assertEquals(120.0, drawsPerSecond(120.0, 0, vsync = false), 0.5)
    }

    @Test
    fun `vsync snaps to an even step of the refresh`() {
        assertEquals(60.0, drawsPerSecond(120.0, 60, vsync = true), 0.5)
        assertEquals(30.0, drawsPerSecond(120.0, 30, vsync = true), 0.5)
        // 45 on 120 Hz rounds to every third refresh: 40, evenly spaced.
        assertEquals(40.0, drawsPerSecond(120.0, 45, vsync = true), 0.5)
        // Asking for more than the panel gives is every refresh, not more.
        assertEquals(60.0, drawsPerSecond(60.0, 120, vsync = true), 0.5)
    }

    @Test
    fun `without vsync the clock holds the exact rate`() {
        assertEquals(45.0, drawsPerSecond(120.0, 45, vsync = false), 1.5)
        assertEquals(24.0, drawsPerSecond(120.0, 24, vsync = false), 1.0)
        assertEquals(30.0, drawsPerSecond(90.0, 30, vsync = false), 1.0)
    }

    @Test
    fun `a stall restarts the schedule instead of bursting`() {
        val due = waterfallNextDue(nowNanos = 10_000_000_000L, previousDueNanos = 1_000_000_000L, targetFps = 30)
        assertEquals(10_000_000_000L + 33_333_333L, due)
    }
}
