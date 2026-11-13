package tf.monochrome.android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.dao.PlaylistOrder
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity

/**
 * A playlist put in a new order (issue #155): the arithmetic on its own
 * ([PlaylistOrder]), then through the database, where the order has to be
 * what comes back and only the rows that moved are reported for the cloud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaylistReorderTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao: PlaylistDao = db.playlistDao()

    @After
    fun close() = db.close()

    // ── The arithmetic ──────────────────────────────────────────────────────

    @Test
    fun `the same order changes nothing`() {
        val rows = listOf(10L to 0, 20L to 1, 30L to 2)
        assertEquals(emptyList<Pair<Long, Int>>(), PlaylistOrder.renumbered(rows, listOf(10, 20, 30)))
    }

    @Test
    fun `only the rows between the old place and the new one move`() {
        val rows = listOf(10L to 0, 20L to 1, 30L to 2, 40L to 3, 50L to 4)
        // 40 dragged up to second.
        val moved = PlaylistOrder.renumbered(rows, listOf(10, 40, 20, 30, 50))
        assertEquals(listOf(40L to 1, 20L to 2, 30L to 3), moved)
    }

    @Test
    fun `the first to the last moves every row`() {
        val rows = listOf(10L to 0, 20L to 1, 30L to 2)
        assertEquals(listOf(20L to 0, 30L to 1, 10L to 2), PlaylistOrder.renumbered(rows, listOf(20, 30, 10)))
    }

    @Test
    fun `a song the order does not name stays, at the end`() {
        // 40 was added while the list was being arranged.
        val rows = listOf(10L to 0, 20L to 1, 30L to 2, 40L to 3)
        assertEquals(listOf(30L to 0, 10L to 1, 20L to 2), PlaylistOrder.renumbered(rows, listOf(30, 10, 20)))
    }

    @Test
    fun `a song no longer in the playlist is skipped`() {
        // 20 was removed while the list was being arranged.
        val rows = listOf(10L to 0, 30L to 2)
        assertEquals(listOf(30L to 0, 10L to 1), PlaylistOrder.renumbered(rows, listOf(30, 20, 10)))
    }

    @Test
    fun `a song named twice counts where it is first`() {
        val rows = listOf(10L to 0, 20L to 1, 30L to 2)
        assertEquals(listOf(20L to 0, 10L to 1), PlaylistOrder.renumbered(rows, listOf(20, 10, 20, 30)))
    }

    @Test
    fun `shared positions and gaps come out as 0 1 2`() {
        // What a removal followed by an add used to leave: two rows on 2, none on 0.
        val rows = listOf(20L to 1, 30L to 2, 40L to 2, 50L to 7)
        val moved = PlaylistOrder.renumbered(rows, listOf(20, 30, 40, 50))
        assertEquals(listOf(20L to 0, 30L to 1, 50L to 3), moved)
    }

    @Test
    fun `an empty playlist has nothing to move`() {
        assertEquals(emptyList<Pair<Long, Int>>(), PlaylistOrder.renumbered(emptyList(), listOf(1, 2, 3)))
    }

    // ── Through the database ────────────────────────────────────────────────

    private fun song(id: Long, playlist: String = "p") =
        PlaylistTrackEntity(playlistId = playlist, trackId = id, title = "Song $id")

    private suspend fun playlistOf(vararg ids: Long, playlist: String = "p") {
        dao.insertPlaylist(UserPlaylistEntity(id = playlist, name = playlist, updatedAt = 1))
        ids.forEach { dao.addTrackToPlaylist(playlist, song(it, playlist)) }
    }

    private suspend fun order(playlist: String = "p") = dao.getPlaylistTracksSnapshot(playlist).map { it.trackId }

    private suspend fun positions(playlist: String = "p") =
        dao.getPlaylistTracksSnapshot(playlist).associate { it.trackId to it.position }

    @Test
    fun `a dragged song is where it was dropped, and stays there`() = runBlocking {
        playlistOf(1, 2, 3, 4, 5)
        val moved = dao.reorderTracks("p", listOf(1, 4, 2, 3, 5))
        assertEquals(listOf(1L, 4L, 2L, 3L, 5L), order())
        assertEquals(mapOf(1L to 0, 4L to 1, 2L to 2, 3L to 3, 5L to 4), positions())
        // Only the three rows that changed place are the cloud's to hear about.
        assertEquals(setOf(4L, 2L, 3L), moved.toSet())
    }

    @Test
    fun `the same order writes nothing and leaves the edit time alone`() = runBlocking {
        playlistOf(1, 2, 3)
        val before = dao.getPlaylist("p")!!.updatedAt
        assertEquals(emptyList<Long>(), dao.reorderTracks("p", listOf(1, 2, 3)))
        assertEquals(before, dao.getPlaylist("p")!!.updatedAt)
    }

    @Test
    fun `a reorder is an edit of the playlist`() = runBlocking {
        dao.insertPlaylist(UserPlaylistEntity(id = "p", name = "p", updatedAt = 1))
        dao.insertPlaylistTrack(song(1).copy(position = 0))
        dao.insertPlaylistTrack(song(2).copy(position = 1))
        dao.updatePlaylist(dao.getPlaylist("p")!!.copy(updatedAt = 1))
        dao.reorderTracks("p", listOf(2, 1))
        assertTrue(dao.getPlaylist("p")!!.updatedAt > 1)
    }

    @Test
    fun `everything but the order survives`() = runBlocking {
        dao.insertPlaylist(UserPlaylistEntity(id = "p", name = "p"))
        val first = PlaylistTrackEntity(
            playlistId = "p", trackId = 1, title = "One", duration = 201, artistName = "A", albumId = 9,
            albumTitle = "Album", albumCover = "cover", position = 0, addedAt = 111, source = "qobuz",
        )
        val second = first.copy(trackId = 2, title = "Two", position = 1, addedAt = 222, source = "local")
        dao.insertPlaylistTrack(first)
        dao.insertPlaylistTrack(second)
        dao.reorderTracks("p", listOf(2, 1))
        assertEquals(listOf(second.copy(position = 0), first.copy(position = 1)), dao.getPlaylistTracksSnapshot("p"))
    }

    @Test
    fun `one playlist's order leaves another's alone`() = runBlocking {
        playlistOf(1, 2, 3, playlist = "p")
        playlistOf(1, 2, 3, playlist = "q")
        dao.reorderTracks("p", listOf(3, 2, 1))
        assertEquals(listOf(3L, 2L, 1L), order("p"))
        assertEquals(listOf(1L, 2L, 3L), order("q"))
    }

    @Test
    fun `a song added during the drag is kept at the end`() = runBlocking {
        playlistOf(1, 2, 3)
        // The screen arranged 1, 2, 3; song 4 arrived before the drop was written.
        dao.addTrackToPlaylist("p", song(4))
        dao.reorderTracks("p", listOf(3, 1, 2))
        assertEquals(listOf(3L, 1L, 2L, 4L), order())
    }

    @Test
    fun `a song added after one was removed goes last, on a position of its own`() = runBlocking {
        playlistOf(1, 2, 3)
        dao.removeTrackFromPlaylist("p", 1)
        dao.addTrackToPlaylist("p", song(4))
        // It used to take the row count, 2, which the last row already had.
        assertEquals(mapOf(2L to 1, 3L to 2, 4L to 3), positions())
        assertEquals(listOf(2L, 3L, 4L), order())
    }

    @Test
    fun `adding after a reorder and a removal still goes last`() = runBlocking {
        playlistOf(1, 2, 3, 4)
        dao.reorderTracks("p", listOf(4, 3, 2, 1))
        dao.removeTrackFromPlaylist("p", 3)
        dao.addTrackToPlaylist("p", song(5))
        assertEquals(listOf(4L, 2L, 1L, 5L), order())
    }

    @Test
    fun `the first song of an empty playlist is at 0`() = runBlocking {
        playlistOf(7)
        assertEquals(mapOf(7L to 0), positions())
    }
}
