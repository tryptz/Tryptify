package tf.monochrome.android.data.local.scanner

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.data.db.MusicDatabase
import tf.monochrome.android.data.local.db.LocalTrackEntity

/**
 * A scan's regrouping keeps every album's, artist's and genre's id.
 *
 * They were cleared and inserted again, so a scan that found one new file gave
 * every album and artist a new id: an open album page, or the next list a
 * queue would carry on into, pointed at an album that was gone, and every
 * track's row was written again to follow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RegroupTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.localMediaDao()

    @After
    fun close() = db.close()

    private fun track(path: String, album: String, artist: String, genre: String? = null) = LocalTrackEntity(
        filePath = path,
        fileSizeBytes = 1,
        lastModified = 1,
        title = path.substringAfterLast('/'),
        artist = artist,
        albumArtist = artist,
        album = album,
        genre = genre,
        codec = "FLAC",
        sampleRate = 44_100,
        bitRate = 900,
        channels = 2,
        durationSeconds = 200,
    )

    private suspend fun regroup() = MediaScanner.regroup(db, dao) { it }

    private suspend fun albumIds() = dao.getAlbumsOnce().associate { it.title to it.id }
    private suspend fun artistIds() = dao.getArtistsOnce().associate { it.name to it.id }
    private suspend fun genreIds() = dao.getGenresOnce().associate { it.name to it.id }

    @Test
    fun `a new file keeps every album and artist where it was`() = runBlocking {
        dao.insertTracks(
            listOf(
                track("/m/a/1.flac", "Album A", "X", "Rock"),
                track("/m/a/2.flac", "Album A", "X", "Rock"),
                track("/m/b/1.flac", "Album B", "Y", "Jazz"),
            )
        )
        regroup()
        val albums = albumIds()
        val artists = artistIds()
        val genres = genreIds()

        // A file lands, and the library is regrouped, as LibraryWatcher does.
        dao.insertTracks(listOf(track("/m/c/1.flac", "Album C", "Z", "Rock")))
        regroup()

        assertEquals(albums, albumIds() - "Album C")
        assertEquals(artists, artistIds() - "Z")
        assertEquals(genres, genreIds())
        // Every track points at its album's row, the new one included.
        val byTitle = dao.getAlbumsOnce().associateBy { it.id }
        for (t in dao.getAllTracksSnapshot()) {
            assertEquals(t.album, byTitle[t.albumId]?.title)
        }
        assertEquals(3, dao.getGenresOnce().first { it.name == "Rock" }.trackCount)
    }

    @Test
    fun `a file read again points at the same album`() = runBlocking {
        dao.insertTracks(listOf(track("/m/a/1.flac", "Album A", "X"), track("/m/a/2.flac", "Album A", "X")))
        regroup()
        val album = albumIds().getValue("Album A")
        val artist = artistIds().getValue("X")

        // A re-read replaces the row whole, without its grouping.
        dao.insertTracks(listOf(track("/m/a/2.flac", "Album A", "X")))
        assertNull(dao.findByPath("/m/a/2.flac")?.albumId)
        regroup()

        val reread = dao.findByPath("/m/a/2.flac")!!
        assertEquals(album, reread.albumId)
        assertEquals(artist, reread.artistId)
        assertEquals(album, albumIds().getValue("Album A"))
    }

    @Test
    fun `an album no track has any more goes, and the rest stay`() = runBlocking {
        dao.insertTracks(
            listOf(
                track("/m/a/1.flac", "Album A", "X", "Rock"),
                track("/m/b/1.flac", "Album B", "Y", "Jazz"),
            )
        )
        regroup()
        val album = albumIds().getValue("Album A")
        val artist = artistIds().getValue("X")

        dao.deleteTracksByPaths(listOf("/m/b/1.flac"))
        regroup()

        assertEquals(mapOf("Album A" to album), albumIds())
        assertEquals(mapOf("X" to artist), artistIds())
        assertEquals(setOf("Rock"), genreIds().keys)
    }

    @Test
    fun `an album whose songs changed is rewritten in place`() = runBlocking {
        dao.insertTracks(listOf(track("/m/a/1.flac", "Album A", "X")))
        regroup()
        val album = albumIds().getValue("Album A")

        dao.insertTracks(listOf(track("/m/a/2.flac", "Album A", "X")))
        regroup()

        val row = dao.getAlbumById(album)!!
        assertEquals(2, row.trackCount)
        assertEquals(400, row.totalDuration)
        assertTrue(dao.getAllTracksSnapshot().all { it.albumId == album })
    }
}
