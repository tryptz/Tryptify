package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a city's station list into a queue.
 *
 * Radio used to play as a queue of exactly one, which is why Next did nothing
 * on a station. It is an ordinary queue now, and the only awkward part — that
 * the directory can list one station twice — is here where it can be tested.
 */
class RadioQueueTest {

    private fun plan(ids: List<String>, tapped: Int) = planRadioQueue(ids, tapped)

    @Test
    fun `an ordinary list is kept whole and in order`() {
        val ids = listOf("a", "b", "c", "d")
        val p = plan(ids, 2)
        assertEquals(listOf(0, 1, 2, 3), p.keep)
        assertEquals(2, p.startIndex)
    }

    @Test
    fun `tapping the first row starts at the first row`() {
        assertEquals(0, plan(listOf("a", "b", "c"), 0).startIndex)
    }

    @Test
    fun `tapping the last row still leaves a queue behind it`() {
        // The point of the whole change: the queue is the city, not the tap.
        val p = plan(listOf("a", "b", "c"), 2)
        assertEquals(3, p.keep.size)
        assertEquals(2, p.startIndex)
    }

    @Test
    fun `a repeated station is listed once`() {
        // Two entries with one id would fight over a single slot in the
        // unified-track registry and both resolve to the same stream, while
        // the queue claimed to hold two.
        val ids = listOf("a", "b", "a", "c")
        val p = plan(ids, 0)
        assertEquals(listOf(0, 1, 3), p.keep)
        assertEquals(listOf("a", "b", "c"), p.keep.map { ids[it] })
    }

    @Test
    fun `dropping a row in front does not move the tap onto its neighbour`() {
        // "b" is at index 2 of the list but index 1 of the queue once the
        // repeated "a" is gone. Off-by-one here plays the wrong station.
        val ids = listOf("a", "a", "b", "c")
        val p = plan(ids, 2)
        assertEquals("b", ids[p.keep[p.startIndex]])
    }

    @Test
    fun `tapping a row that is itself a duplicate plays that station`() {
        // The tapped row was dropped, so the surviving row with the same id is
        // the one to start on — it is the same station.
        val ids = listOf("a", "b", "a")
        val p = plan(ids, 2)
        assertEquals("a", ids[p.keep[p.startIndex]])
        assertEquals(0, p.startIndex)
    }

    @Test
    fun `the tapped station always survives into the queue`() {
        // The property the two cases above are examples of, over every list
        // and every tap.
        val lists = listOf(
            listOf("a"),
            listOf("a", "b", "c"),
            listOf("a", "a", "a"),
            listOf("a", "b", "a", "b", "c"),
            listOf("x", "x", "y", "z", "z", "y"),
        )
        lists.forEach { ids ->
            ids.indices.forEach { tapped ->
                val p = plan(ids, tapped)
                assertEquals(
                    "tapping $tapped of $ids played the wrong station",
                    ids[tapped],
                    ids[p.keep[p.startIndex]],
                )
            }
        }
    }

    @Test
    fun `the queue never repeats an id`() {
        val lists = listOf(
            listOf("a", "a", "a"),
            listOf("a", "b", "a", "b", "c"),
            listOf("x", "x", "y", "z", "z", "y"),
        )
        lists.forEach { ids ->
            val kept = plan(ids, 0).keep.map { ids[it] }
            assertEquals("$ids collapsed wrongly", kept.size, kept.toSet().size)
        }
    }

    @Test
    fun `an index off the end is clamped to a real row rather than dropped`() {
        // A caller and a list that disagree about what is on screen. Playing
        // the nearest real row beats a tap that does nothing.
        val ids = listOf("a", "b")
        assertEquals(1, plan(ids, 9).startIndex)
        assertEquals(0, plan(ids, -3).startIndex)
    }

    @Test
    fun `an empty list plans nothing`() {
        val p = plan(emptyList(), 0)
        assertTrue(p.keep.isEmpty())
        assertEquals(0, p.startIndex)
    }

    @Test
    fun `the start index addresses the queue, not the original list`() {
        // keep is indices into the list handed in; startIndex indexes keep.
        // Confusing the two is the bug this whole file exists to prevent.
        val ids = listOf("a", "a", "a", "b")
        val p = plan(ids, 3)
        assertEquals(listOf(0, 3), p.keep)
        assertEquals(1, p.startIndex)
        assertEquals("b", ids[p.keep[p.startIndex]])
    }
}
