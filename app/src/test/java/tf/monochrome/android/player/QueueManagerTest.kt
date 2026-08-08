package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track

class QueueManagerTest {

    private fun track(id: Long) = Track(id = id, title = "Track $id")

    private fun manager(size: Int, startIndex: Int): QueueManager =
        QueueManager().apply { setQueue((1..size).map { track(it.toLong()) }, startIndex) }

    // --- peekNext (drives gapless pre-queuing) ---

    @Test
    fun `peekNext reports the next track without moving`() {
        val qm = manager(5, startIndex = 2)
        assertEquals(4L, qm.peekNext()?.id)
        // Still on the same track — peeking must not advance the queue.
        assertEquals(3L, qm.currentTrack.value?.id)
        assertEquals(2, qm.currentQueueIndex)
    }

    @Test
    fun `peekNext agrees with what next actually plays`() {
        val qm = manager(5, startIndex = 1)
        val peeked = qm.peekNext()?.id
        assertEquals(peeked, qm.next()?.id)
    }

    @Test
    fun `peekNext is null at the end of the queue`() {
        val qm = manager(3, startIndex = 2)
        assertNull(qm.peekNext())
    }

    @Test
    fun `peekNext wraps under repeat all`() {
        val qm = manager(3, startIndex = 2)
        qm.setRepeatMode(RepeatMode.ALL)
        assertEquals(1L, qm.peekNext()?.id)
    }

    @Test
    fun `peekNext is null under repeat one`() {
        // The current track repeats, so there is no next track to pre-queue —
        // handing the player one would jump the queue.
        val qm = manager(5, startIndex = 1)
        qm.setRepeatMode(RepeatMode.ONE)
        assertNull(qm.peekNext())
    }

    @Test
    fun `peekNext is null for an empty queue`() {
        assertNull(QueueManager().peekNext())
    }

    @Test
    fun `peekNext follows the shuffled order`() {
        val qm = manager(6, startIndex = 0)
        qm.toggleShuffle()
        // Shuffle reorders the queue in place, so the following index really is
        // what plays next.
        assertEquals(qm.currentQueue[1].id, qm.peekNext()?.id)
        assertEquals(qm.peekNext()?.id, qm.next()?.id)
    }

    // --- clearUpcoming ---

    @Test
    fun `clearUpcoming keeps only the current track`() {
        val qm = manager(5, startIndex = 2)
        qm.clearUpcoming()
        assertEquals(listOf(3L), qm.currentQueue.map { it.id })
        assertEquals(0, qm.currentQueueIndex)
        assertEquals(3L, qm.currentTrack.value?.id)
    }

    @Test
    fun `clearUpcoming with no current track empties the queue`() {
        val qm = QueueManager()
        qm.clearUpcoming()
        assertTrue(qm.currentQueue.isEmpty())
        assertEquals(-1, qm.currentQueueIndex)
    }

    // --- removeFromQueue ---

    @Test
    fun `removeAt before current decrements current index`() {
        val qm = manager(5, startIndex = 2)
        qm.removeFromQueue(0)
        assertEquals(1, qm.currentQueueIndex)
        assertEquals(3L, qm.currentTrack.value?.id)
    }

    @Test
    fun `removeAt after current preserves current index`() {
        val qm = manager(5, startIndex = 2)
        qm.removeFromQueue(4)
        assertEquals(2, qm.currentQueueIndex)
        assertEquals(3L, qm.currentTrack.value?.id)
    }

    @Test
    fun `removeAt current moves to the next track`() {
        val qm = manager(5, startIndex = 2)
        qm.removeFromQueue(2)
        assertEquals(2, qm.currentQueueIndex)
        assertEquals(4L, qm.currentTrack.value?.id)
    }

    // --- removeMany ---

    @Test
    fun `removeMany preserves current track when not selected`() {
        val qm = manager(5, startIndex = 2)
        qm.removeMany(setOf(0, 4))
        assertEquals(listOf(2L, 3L, 4L), qm.currentQueue.map { it.id })
        assertEquals(1, qm.currentQueueIndex)
        assertEquals(3L, qm.currentTrack.value?.id)
    }

    @Test
    fun `removeMany lands on the slid-in track when current is selected`() {
        val qm = manager(5, startIndex = 2)
        qm.removeMany(setOf(1, 2))
        assertEquals(listOf(1L, 4L, 5L), qm.currentQueue.map { it.id })
        assertEquals(1, qm.currentQueueIndex)
        assertEquals(4L, qm.currentTrack.value?.id)
    }

    @Test
    fun `removeMany of everything empties the queue`() {
        val qm = manager(3, startIndex = 1)
        qm.removeMany(setOf(0, 1, 2))
        assertTrue(qm.currentQueue.isEmpty())
        assertEquals(-1, qm.currentQueueIndex)
        assertEquals(null, qm.currentTrack.value)
    }

    // --- move ---

    @Test
    fun `move preserves current track identity`() {
        val qm = manager(5, startIndex = 2)
        qm.move(fromIndex = 4, toIndex = 0)
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), qm.currentQueue.map { it.id })
        assertEquals(3, qm.currentQueueIndex)
        assertEquals(3L, qm.currentTrack.value?.id)
    }

    @Test
    fun `move current track keeps it playing`() {
        val qm = manager(5, startIndex = 2)
        qm.move(fromIndex = 2, toIndex = 0)
        assertEquals(0, qm.currentQueueIndex)
        assertEquals(3L, qm.currentTrack.value?.id)
    }

    @Test
    fun `move ignores out-of-range indices`() {
        val qm = manager(3, startIndex = 0)
        qm.move(fromIndex = 5, toIndex = 0)
        qm.move(fromIndex = 0, toIndex = 9)
        assertEquals(listOf(1L, 2L, 3L), qm.currentQueue.map { it.id })
    }

    // --- moveToPlayNext ---

    @Test
    fun `moveToPlayNext places the item right after the current track`() {
        val qm = manager(5, startIndex = 1)
        qm.moveToPlayNext(4)
        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), qm.currentQueue.map { it.id })
        assertEquals(1, qm.currentQueueIndex)
        assertEquals(2L, qm.currentTrack.value?.id)
    }

    @Test
    fun `moveToPlayNext from before current keeps current playing`() {
        val qm = manager(5, startIndex = 2)
        qm.moveToPlayNext(0)
        assertEquals(3L, qm.currentTrack.value?.id)
        // Item 1 now sits directly after the current track.
        val current = qm.currentQueueIndex
        assertEquals(1L, qm.currentQueue[current + 1].id)
    }
}
