package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Idle pacing halves a fast display's frames only at rest, and the frame report adds up. */
class GalaxyPacingTest {

    /** [seconds] of vsyncs at [hz] from [from]: how many moved, and the time after the last. */
    private fun run(pacer: GalaxyPacer, hz: Int, seconds: Float, from: Long, moving: (Long) -> Boolean = { false }): Pair<Int, Long> {
        val vsync = 1_000_000_000L / hz
        var t = from
        var moved = 0
        repeat(kotlin.math.round(seconds * hz).toInt()) {
            if (!pacer.hold(t, moving(t))) moved++
            t += vsync
        }
        return moved to t
    }

    @Test
    fun `at rest a 120 Hz display moves on every other vsync, 60 a second`() {
        val pacer = GalaxyPacer()
        // The first second after the map comes up counts as active.
        val (_, t) = run(pacer, 120, 1.2f, from = 1_000_000_000L)
        assertTrue(pacer.idle)
        val (moved, _) = run(pacer, 120, 2f, from = t)
        assertEquals(120, moved)
    }

    @Test
    fun `while anything moves, or a finger is down, every vsync moves`() {
        val pacer = GalaxyPacer()
        val (_, t) = run(pacer, 120, 1.5f, from = 1_000_000_000L)
        val (moved, t2) = run(pacer, 120, 1f, from = t, moving = { true })
        assertEquals(120, moved)
        assertFalse(pacer.idle)
        // A finger held down, perfectly still, still counts.
        pacer.touch(pressed = true, now = t2)
        val (held, t3) = run(pacer, 120, 2f, from = t2)
        assertEquals(240, held)
        // Lifted: a second later it is at rest again.
        pacer.touch(pressed = false, now = t3)
        val (soon, _) = run(pacer, 120, 0.5f, from = t3)
        assertEquals(60, soon)
        assertFalse(pacer.idle)
    }

    @Test
    fun `60 and 90 Hz displays are never paced`() {
        for (hz in listOf(60, 90)) {
            val pacer = GalaxyPacer()
            val (_, t) = run(pacer, hz, 1.5f, from = 1_000_000_000L)
            val (moved, _) = run(pacer, hz, 2f, from = t)
            assertEquals("at $hz Hz", hz * 2, moved)
            assertTrue(pacer.idle)
        }
    }

    @Test
    fun `percentiles are nearest rank, in ms`() {
        val ns = LongArray(100) { (it + 1) * 1_000_000L }
        assertEquals(50f, percentileMs(ns, 100, 0.5f), 1e-3f)
        assertEquals(95f, percentileMs(ns, 100, 0.95f), 1e-3f)
        assertEquals(100f, percentileMs(ns, 100, 1f), 1e-3f)
        assertEquals(0f, percentileMs(ns, 0, 0.5f), 0f)
        // Unsorted in, the same out.
        val shuffled = ns.reversedArray()
        assertEquals(50f, percentileMs(shuffled, 100, 0.5f), 1e-3f)
    }

    @Test
    fun `a frame is late against its own deadline, or one refresh without one`() {
        val sink = FrameSink(capacity = 8)
        sink.add(totalNs = 9_000_000, uiNs = 2_000_000, rtNs = 1_000_000, gpuNs = 5_000_000, deadlineNs = 8_333_333)
        sink.add(totalNs = 7_000_000, uiNs = 2_000_000, rtNs = 1_000_000, gpuNs = 4_000_000, deadlineNs = 8_333_333)
        val s = sink.drain(120f)
        assertEquals(2, s.frames)
        assertEquals(0.5f, s.lateShare, 1e-6f)
        assertEquals(5f, s.gpuP95!!, 1e-3f)
        // Drained: empty again.
        assertEquals(0, sink.drain(120f).frames)

        // Before Android 12: no GPU time, and late means over one 60 Hz refresh.
        sink.add(totalNs = 20_000_000, uiNs = 1, rtNs = 1, gpuNs = -1, deadlineNs = -1)
        sink.add(totalNs = 10_000_000, uiNs = 1, rtNs = 1, gpuNs = -1, deadlineNs = -1)
        val old = sink.drain(60f)
        assertNull(old.gpuP50)
        assertEquals(0.5f, old.lateShare, 1e-6f)
        assertTrue(old.line(5f, 60f).contains("gpu n/a"))
    }

    @Test
    fun `a full report counts the frames it could not hold`() {
        val sink = FrameSink(capacity = 2)
        repeat(5) { sink.add(totalNs = 1, uiNs = 1, rtNs = 1, gpuNs = 1, deadlineNs = 2) }
        val s = sink.drain(60f)
        assertEquals(2, s.frames)
        assertEquals(3, s.dropped)
        assertTrue(s.line(1f, 60f).contains("unreported 3"))
    }
}
