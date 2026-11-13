package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track

/** Which songs' covers ride in beside the playing one as the cover is swiped. */
class SwipeNeighboursTest {

    private fun track(id: Long) = Track(id = id, title = "Track $id")
    private val queue = listOf(track(1), track(2), track(3))

    private fun ids(n: SwipeNeighbours) = n.previous?.id to n.next?.id

    @Test
    fun `in the middle, the songs either side`() {
        assertEquals(1L to 3L, ids(SwipeNeighbours.of(queue, 1, RepeatMode.OFF)))
    }

    @Test
    fun `at the ends with no repeat there is nothing to show that way`() {
        assertEquals(null to 2L, ids(SwipeNeighbours.of(queue, 0, RepeatMode.OFF)))
        assertEquals(2L to null, ids(SwipeNeighbours.of(queue, 2, RepeatMode.OFF)))
    }

    @Test
    fun `repeat all wraps round, as the queue does`() {
        assertEquals(3L to 2L, ids(SwipeNeighbours.of(queue, 0, RepeatMode.ALL)))
        assertEquals(2L to 1L, ids(SwipeNeighbours.of(queue, 2, RepeatMode.ALL)))
    }

    @Test
    fun `repeat one goes on to this song again, and back to the one before`() {
        assertEquals(1L to 2L, ids(SwipeNeighbours.of(queue, 1, RepeatMode.ONE)))
        assertEquals(null to 1L, ids(SwipeNeighbours.of(queue, 0, RepeatMode.ONE)))
    }

    @Test
    fun `one song, an empty queue or no position has none`() {
        assertEquals(null to null, ids(SwipeNeighbours.of(listOf(track(1)), 0, RepeatMode.OFF)))
        assertEquals(1L to 1L, ids(SwipeNeighbours.of(listOf(track(1)), 0, RepeatMode.ALL)))
        assertEquals(SwipeNeighbours.NONE, SwipeNeighbours.of(emptyList(), 0, RepeatMode.ALL))
        assertEquals(SwipeNeighbours.NONE, SwipeNeighbours.of(queue, -1, RepeatMode.OFF))
        assertEquals(SwipeNeighbours.NONE, SwipeNeighbours.of(queue, 3, RepeatMode.OFF))
    }
}
