package tf.monochrome.android.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.VisualizerPreset

/**
 * The walk-back rules behind the ambient overlay's Back button.
 *
 * Worth pinning because every failure here is quiet: Previous that bounces
 * between two presets, a Back button lit with nowhere to go, or a stack that
 * grows for the length of a shuffling session all look like nothing at all
 * until someone is staring at the wrong preset.
 */
class PresetHistoryTest {

    private fun preset(id: String) = VisualizerPreset(
        id = id,
        displayName = id,
        filePath = "$id.milk",
    )

    @Test
    fun `starts with nowhere to go`() {
        val history = PresetHistory()
        assertFalse(history.canGoBack())
        assertNull(history.back())
    }

    @Test
    fun `walks back in reverse order`() {
        val history = PresetHistory()
        history.record(preset("a"), preset("b"))
        history.record(preset("b"), preset("c"))

        assertTrue(history.canGoBack())
        assertEquals("b", history.back()?.id)
        assertEquals("a", history.back()?.id)
        assertNull(history.back())
        assertFalse(history.canGoBack())
    }

    @Test
    fun `ignores a move that changes nothing`() {
        val history = PresetHistory()
        // Re-selecting what is already showing is not a step you can undo.
        history.record(preset("a"), preset("a"))
        assertFalse(history.canGoBack())
        assertEquals(0, history.depth())
    }

    @Test
    fun `ignores a move from nothing`() {
        val history = PresetHistory()
        // First preset of the session: there is no outgoing one to remember.
        history.record(null, preset("a"))
        assertFalse(history.canGoBack())
    }

    @Test
    fun `evicts the oldest past the depth cap`() {
        val history = PresetHistory(maxDepth = 3)
        listOf("a", "b", "c", "d", "e").forEach { history.record(preset(it), preset("next")) }

        assertEquals(3, history.depth())
        // a and b evicted; the three most recent survive, newest first.
        assertEquals("e", history.back()?.id)
        assertEquals("d", history.back()?.id)
        assertEquals("c", history.back()?.id)
        assertNull(history.back())
    }

    @Test
    fun `does not record its own walk-back`() {
        // The repository calls record() only on a forward move, so a Back
        // leaves the stack shorter rather than pushing what it just left.
        // Guarding it here keeps that contract from silently inverting into
        // a two-preset ping-pong.
        val history = PresetHistory()
        history.record(preset("a"), preset("b"))
        history.record(preset("b"), preset("c"))

        assertEquals("b", history.back()?.id)
        assertEquals(1, history.depth())
        assertEquals("a", history.back()?.id)
        assertEquals(0, history.depth())
    }

    @Test
    fun `clear empties the stack`() {
        val history = PresetHistory()
        history.record(preset("a"), preset("b"))
        history.clear()
        assertFalse(history.canGoBack())
        assertEquals(0, history.depth())
    }
}
