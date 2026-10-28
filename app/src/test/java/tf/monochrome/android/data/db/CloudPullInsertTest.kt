package tf.monochrome.android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity
import tf.monochrome.android.data.db.entity.PlayEventEntity
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity

/**
 * The cloud pull's inserts, a section at a time instead of a row at a time,
 * with the rule they had one by one: a row already on the device stays as it
 * is (set membership, local data wins), and a row it does not have is added.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CloudPullInsertTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = db.close()

    @Test
    fun `a pulled favourite already here keeps the device's copy`() = runBlocking {
        val dao = db.favoriteDao()
        dao.insertFavoriteTrack(FavoriteTrackEntity(id = 1, title = "Mine", addedAt = 10))
        dao.insertTracksIfNotExist(
            listOf(
                FavoriteTrackEntity(id = 1, title = "Cloud's", addedAt = 20),
                FavoriteTrackEntity(id = 2, title = "New", addedAt = 30),
            )
        )
        val rows = dao.getFavoriteTracksSnapshot().associateBy { it.id }
        assertEquals("Mine", rows.getValue(1).title)
        assertEquals(10L, rows.getValue(1).addedAt)
        assertEquals("New", rows.getValue(2).title)
    }

    @Test
    fun `pulled playlists and their songs join what is here`() = runBlocking {
        val dao = db.playlistDao()
        dao.insertPlaylist(UserPlaylistEntity(id = "p1", name = "Mine"))
        dao.insertPlaylistsIfNotExist(listOf(UserPlaylistEntity(id = "p1", name = "Cloud's"), UserPlaylistEntity(id = "p2", name = "New")))
        assertEquals(mapOf("p1" to "Mine", "p2" to "New"), dao.getAllPlaylistsSnapshot().associate { it.id to it.name })

        dao.insertPlaylistTrack(PlaylistTrackEntity(playlistId = "p1", trackId = 7, title = "Mine", position = 0))
        dao.insertTracksIfNotExist(
            listOf(
                PlaylistTrackEntity(playlistId = "p1", trackId = 7, title = "Cloud's", position = 5),
                PlaylistTrackEntity(playlistId = "p2", trackId = 7, title = "New", position = 0),
            )
        )
        assertEquals("Mine", dao.getPlaylistTrack("p1", 7)?.title)
        assertEquals(0, dao.getPlaylistTrack("p1", 7)?.position)
        assertEquals("New", dao.getPlaylistTrack("p2", 7)?.title)
    }

    @Test
    fun `pulling the same plays twice counts them once`() = runBlocking {
        val dao = db.playEventDao()
        val plays = (1L..3L).map { PlayEventEntity(trackId = it, title = "t$it", playedAt = it, cloudRowId = 100 + it) }
        dao.insertAllFromCloud(plays)
        dao.insertAllFromCloud(plays + PlayEventEntity(trackId = 4, title = "t4", playedAt = 4, cloudRowId = 104))
        assertEquals(4, dao.playCount().first())
    }
}
