package tf.monochrome.android.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.visualizer.effectiveFrameCap
import tf.monochrome.android.visualizer.frameWaitNanos
import tf.monochrome.android.visualizer.shouldDropFrame
import tf.monochrome.android.visualizer.wantedFrameRate
import kotlin.math.abs

class FrameRateLimitTest {

    /** Frames kept out of [seconds] of a [hz] display at [fps]. */
    private fun kept(hz: Double, fps: Int, seconds: Int = 4): Int {
        val pacer = FramePacer()
        val vsync = 1_000_000_000.0 / hz
        var count = 0
        // The display's own jitter: a vsync up to half a millisecond off.
        for (i in 0 until (hz * seconds).toInt()) {
            val jitter = if (i % 3 == 0) 400_000L else if (i % 3 == 1) -300_000L else 0L
            if (pacer.due(1_000_000L + (i * vsync).toLong() + jitter, fps)) count++
        }
        return count / seconds
    }

    @Test
    fun `no limit keeps every frame`() {
        assertEquals(120, kept(120.0, FrameRateLimit.MAX))
        assertEquals(60, kept(60.0, FrameRateLimit.MAX))
    }

    @Test
    fun `a limit the display divides keeps exactly that many`() {
        assertEquals(60, kept(120.0, 60))
        assertEquals(30, kept(120.0, 30))
        assertEquals(30, kept(60.0, 30))
        assertEquals(120, kept(120.0, 120))
    }

    @Test
    fun `a limit the display does not divide still averages out to it`() {
        // 90 on 120 Hz and 45 on 60 Hz: uneven gaps, the right count.
        assertTrue(abs(kept(120.0, 90) - 90) <= 1)
        assertTrue(abs(kept(60.0, 45) - 45) <= 1)
        assertTrue(abs(kept(144.0, 60) - 60) <= 1)
    }

    @Test
    fun `a limit above the display's rate changes nothing`() {
        assertEquals(60, kept(60.0, 90))
        assertEquals(60, kept(60.0, 120))
    }

    @Test
    fun `after a stall it starts afresh instead of catching up`() {
        val pacer = FramePacer()
        assertTrue(pacer.due(0L + 1, 60))
        // Two seconds with no frames asked for, then a burst of vsyncs.
        val back = 2_000_000_000L
        var kept = 0
        for (i in 0 until 12) if (pacer.due(back + i * 8_333_333L, 60)) kept++
        assertEquals(6, kept)
    }

    @Test
    fun `the visualizer holds the lower of its own cap and the app's`() {
        // Vsync on: Target FPS is not a ceiling there, the app's limit is.
        assertEquals(0, effectiveFrameCap(targetFps = 120, vsync = true, limit = 0))
        assertEquals(60, effectiveFrameCap(targetFps = 120, vsync = true, limit = 60))
        // Vsync off: both apply, the lower wins.
        assertEquals(120, effectiveFrameCap(targetFps = 120, vsync = false, limit = 0))
        assertEquals(60, effectiveFrameCap(targetFps = 120, vsync = false, limit = 60))
        assertEquals(45, effectiveFrameCap(targetFps = 45, vsync = false, limit = 60))
    }

    @Test
    fun `with vsync on a held frame is let go before its vsync, and never dropped after waiting`() {
        val slack = 4_000_000L
        val wait = frameWaitNanos(0L, 0L, 60, slack)
        assertEquals(1_000_000_000L / 60 - slack, wait)
        assertTrue(!shouldDropFrame(wait, 0L, 60, slack))
    }

    @Test
    fun `the visualizer's surface asks for no more than the limit`() {
        assertEquals(120f, wantedFrameRate(120f, limit = 0))
        assertEquals(60f, wantedFrameRate(120f, limit = 60))
        assertEquals(60f, wantedFrameRate(60f, limit = 90))
    }

    @Test
    fun `the display is asked for the slowest rate that holds the limit, and nothing at Max`() {
        val ltpo = listOf(1f, 10f, 24f, 30f, 60f, 90f, 120f)
        val plain = listOf(60f, 120f)
        assertEquals(0f, FrameRateLimit.refreshRateFor(FrameRateLimit.MAX, plain))
        assertEquals(30f, FrameRateLimit.refreshRateFor(30, ltpo))
        assertEquals(60f, FrameRateLimit.refreshRateFor(30, plain))
        assertEquals(60f, FrameRateLimit.refreshRateFor(45, ltpo))
        assertEquals(120f, FrameRateLimit.refreshRateFor(90, plain))
        // A limit above the panel's fastest asks for its fastest.
        assertEquals(60f, FrameRateLimit.refreshRateFor(120, listOf(60f)))
        assertEquals(59.94f, FrameRateLimit.refreshRateFor(60, listOf(59.94f, 90f)))
    }
}
