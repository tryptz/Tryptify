package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.Track

class QueueManagerTest {

    @Test
    fun clearUpcomingKeepsCurrentTrackAndResetsIndex() {
        val manager = QueueManager()
        val tracks = tracks(4)
        manager.setQueue(tracks, startIndex = 2)

        manager.clearUpcoming()

        assertEquals(listOf(tracks[2]), manager.currentQueue)
        assertEquals(0, manager.currentQueueIndex)
        assertEquals(tracks[2], manager.currentTrack.value)
    }

    @Test
    fun removeAtBeforeCurrentDecrementsCurrentIndex() {
        val manager = QueueManager()
        val tracks = tracks(4)
        manager.setQueue(tracks, startIndex = 2)

        manager.removeAt(0)

        assertEquals(1, manager.currentQueueIndex)
        assertEquals(tracks[2], manager.currentTrack.value)
    }

    @Test
    fun removeAtCurrentMovesToNextValidTrack() {
        val manager = QueueManager()
        val tracks = tracks(3)
        manager.setQueue(tracks, startIndex = 1)

        manager.removeAt(1)

        assertEquals(1, manager.currentQueueIndex)
        assertEquals(tracks[2], manager.currentTrack.value)
    }

    @Test
    fun removeManyPreservesCurrentTrackWhenNotSelected() {
        val manager = QueueManager()
        val tracks = tracks(5)
        manager.setQueue(tracks, startIndex = 3)

        manager.removeMany(setOf(0, 2))

        assertEquals(listOf(tracks[1], tracks[3], tracks[4]), manager.currentQueue)
        assertEquals(1, manager.currentQueueIndex)
        assertEquals(tracks[3], manager.currentTrack.value)
    }

    @Test
    fun removeManyHandlesCurrentTrackSelected() {
        val manager = QueueManager()
        val tracks = tracks(4)
        manager.setQueue(tracks, startIndex = 2)

        manager.removeMany(setOf(1, 2))

        assertEquals(listOf(tracks[0], tracks[3]), manager.currentQueue)
        assertEquals(1, manager.currentQueueIndex)
        assertEquals(tracks[3], manager.currentTrack.value)
    }

    @Test
    fun movePreservesCurrentTrackIdentity() {
        val manager = QueueManager()
        val tracks = tracks(4)
        manager.setQueue(tracks, startIndex = 2)

        manager.move(fromIndex = 3, toIndex = 0)

        assertEquals(listOf(tracks[3], tracks[0], tracks[1], tracks[2]), manager.currentQueue)
        assertEquals(3, manager.currentQueueIndex)
        assertEquals(tracks[2], manager.currentTrack.value)
    }

    @Test
    fun moveToPlayNextPlacesItemAfterCurrent() {
        val manager = QueueManager()
        val tracks = tracks(5)
        manager.setQueue(tracks, startIndex = 1)

        manager.moveToPlayNext(4)

        assertEquals(listOf(tracks[0], tracks[1], tracks[4], tracks[2], tracks[3]), manager.currentQueue)
        assertEquals(1, manager.currentQueueIndex)
        assertEquals(tracks[1], manager.currentTrack.value)
    }

    @Test
    fun removingLastTrackClearsCurrentTrack() {
        val manager = QueueManager()
        val track = track(1)
        manager.setQueue(listOf(track), startIndex = 0)

        manager.removeAt(0)

        assertEquals(emptyList<Track>(), manager.currentQueue)
        assertEquals(-1, manager.currentQueueIndex)
        assertNull(manager.currentTrack.value)
    }

    @Test
    fun addToQueueAndSelectFirstStartsIdleQueueAtFirstAppendedTrack() {
        val manager = QueueManager()
        val tracks = tracks(3)

        val selected = manager.addToQueueAndSelectFirst(tracks)

        assertEquals(true, selected)
        assertEquals(tracks, manager.currentQueue)
        assertEquals(0, manager.currentQueueIndex)
        assertEquals(tracks[0], manager.currentTrack.value)
    }

    @Test
    fun addToQueueAndSelectFirstPreservesExistingQueueAndSelectsFirstAppendedTrack() {
        val manager = QueueManager()
        val existing = tracks(2)
        val appended = listOf(track(10), track(11))
        manager.setQueue(existing, startIndex = 0)

        val selected = manager.addToQueueAndSelectFirst(appended)

        assertEquals(true, selected)
        assertEquals(existing + appended, manager.currentQueue)
        assertEquals(2, manager.currentQueueIndex)
        assertEquals(appended[0], manager.currentTrack.value)
    }

    private fun tracks(count: Int): List<Track> = (0 until count).map { track(it.toLong()) }

    private fun track(id: Long): Track = Track(
        id = id,
        title = "Track $id",
        duration = 180,
    )
}
