package tf.monochrome.android.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio the visualizer is allowed to see, and when.
 *
 * The tap sits upstream of the output device, so the picture always runs a
 * little ahead of the sound; over Bluetooth that gap is wide enough to watch.
 * What is asserted here is the withholding itself -- that a frame is not handed
 * over until it has aged past the delay -- because that is the whole mechanism,
 * and the thing that would silently revert to the old behaviour.
 */
class ProjectMAudioBusTest {

    private fun bus() = ProjectMAudioBus().apply { acquire() }

    private fun ProjectMAudioBus.publishOne() =
        publish(FloatArray(256) { 0.1f }, channelCount = 2, sampleRate = 48_000)

    @Test
    fun `with no delay every frame is handed over at once`() {
        val bus = bus()
        repeat(3) { bus.publishOne() }
        assertEquals(3, bus.drainAll().size)
    }

    @Test
    fun `a delay withholds audio that has not come due`() {
        val bus = bus()
        bus.setDelayMs(400)
        bus.publishOne()
        // Published this instant, so it is 400ms short of being due.
        assertTrue("a fresh frame must not be released", bus.drainAll().isEmpty())
    }

    @Test
    fun `audio held back is kept, not dropped`() {
        val bus = bus()
        bus.setDelayMs(400)
        // Comfortably more than the eight frames the queue used to cap at: the
        // bound is the delay window now, and trimming to a frame count would
        // throw this audio away before it ever came due.
        repeat(40) { bus.publishOne() }
        bus.setDelayMs(0)
        assertEquals(40, bus.drainAll().size)
    }

    @Test
    fun `the delay is clamped to the range the slider offers`() {
        val bus = bus()
        bus.setDelayMs(-50)
        bus.publishOne()
        assertEquals("a negative delay must behave as none", 1, bus.drainAll().size)

        bus.setDelayMs(ProjectMAudioBus.MAX_DELAY_MS * 10)
        bus.publishOne()
        assertTrue(bus.drainAll().isEmpty())
    }

    @Test
    fun `the waveform snapshot is not delayed`() {
        // It feeds an overlay that draws whether or not the engine is running,
        // so holding it back here would freeze it whenever nothing drained.
        val bus = bus()
        bus.setDelayMs(400)
        bus.publishOne()
        assertTrue(bus.peekSamples() != null)
    }
}
