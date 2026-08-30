package tf.monochrome.android.visualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Target FPS cap, which only runs with vsync off.
 *
 * Both ways of getting this wrong are silent rather than loud: a cap that
 * always drops leaves the visualizer frozen, and one that never drops leaves it
 * free-running at whatever the GPU will hand over, which is the state the cap
 * exists to end.
 */
class VisualizerFrameCapTest {

    private val oneSecond = 1_000_000_000L

    @Test
    fun `a frame arriving too soon is dropped`() {
        // 240fps is a frame every ~4.17ms; 2ms in is half an interval.
        assertTrue(shouldDropFrame(2_000_000L, 0L, 240))
    }

    @Test
    fun `a frame arriving on time is drawn`() {
        assertFalse(shouldDropFrame(oneSecond / 240, 0L, 240))
        assertFalse(shouldDropFrame(oneSecond, 0L, 240))
    }

    @Test
    fun `no cap means nothing is ever dropped`() {
        for (cap in listOf(0, -1, -240)) {
            assertFalse("cap $cap must not limit", shouldDropFrame(1L, 0L, cap))
        }
    }

    @Test
    fun `the cap admits about as many frames a second as it names`() {
        // The property that matters, rather than one boundary: walk a second of
        // arrivals far finer than the cap and count what gets through.
        for (cap in listOf(30, 60, 120, 240)) {
            var last = 0L
            var drawn = 0
            val step = oneSecond / 2_000
            var t = step
            while (t <= oneSecond) {
                if (!shouldDropFrame(t, last, cap)) {
                    drawn++
                    last = t
                }
                t += step
            }
            assertTrue(
                "cap $cap let $drawn frames through in a second",
                drawn in (cap - cap / 10)..(cap + 1),
            )
        }
    }
}
