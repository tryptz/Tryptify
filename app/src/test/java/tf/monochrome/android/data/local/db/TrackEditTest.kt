// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.local.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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
import tf.monochrome.android.data.local.repository.LocalMediaRepository.Companion.toUnifiedTrack

/** Edit info: the listener's corrections to a local song's tags, and the artist a song is played under. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TrackEditTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.localMediaDao()

    @After
    fun close() = db.close()

    /** A song off a label's compilation, as its file tags it. */
    private val file = LocalTrackEntity(
        filePath = "/music/Irui/11.flac",
        fileSizeBytes = 1,
        lastModified = 1,
        title = "声",
        artist = "Feryquitous",
        albumArtist = "Diverse System",
        album = "Irui",
        genre = "Electronic",
        year = 2017,
        trackNumber = 11,
        discNumber = 1,
        codec = "FLAC",
        sampleRate = 44_100,
        bitRate = 1036,
        channels = 2,
        durationSeconds = 241,
    )

    @Test
    fun `a song plays under its own artist, not its album's`() {
        val song = file.toUnifiedTrack()
        assertEquals("Feryquitous", song.artistName)
        assertEquals("Diverse System", song.albumArtistName)
        // Without an artist tag, the album artist still names it.
        assertEquals("Diverse System", file.copy(artist = null).toUnifiedTrack().artistName)
    }

    @Test
    fun `an edit lies over the file's tags, and only where it says`() {
        val edited = file.withEdit(LocalTrackEditEntity(file.filePath, artist = "Feryquitous feat. Someone", year = 2018))
        assertEquals("Feryquitous feat. Someone", edited.artist)
        assertEquals(2018, edited.year)
        assertEquals("Diverse System", edited.albumArtist)
        assertEquals("声", edited.title)
        assertEquals(file, file.withEdit(null))
    }

    @Test
    fun `an emptied field clears the tag`() {
        val edited = file.withEdit(LocalTrackEditEntity(file.filePath, albumArtist = ""))
        assertNull(edited.albumArtist)
    }

    @Test
    fun `a new title is searched by`() {
        val edited = file.withEdit(LocalTrackEditEntity(file.filePath, title = "Koe"))
        assertEquals("Koe", edited.title)
        assertEquals(tf.monochrome.android.player.LocalTrackMatching.searchKey("Koe"), edited.titleSearchKey)
    }

    @Test
    fun `only what differs from the file is kept`() {
        val untouched = trackEditFrom(file.filePath, file, TypedTags.of(file))
        assertTrue(untouched.isEmpty)

        val typed = TypedTags.of(file).copy(artist = "  Someone Else ", albumArtist = "", trackNumber = "x", year = "2017")
        val edit = trackEditFrom(file.filePath, file, typed)
        assertEquals("Someone Else", edit.artist)
        assertEquals("", edit.albumArtist)
        // Not a number, or the file's own: the file's stays.
        assertNull(edit.trackNumber)
        assertNull(edit.year)
        assertNull(edit.title)
    }

    @Test
    fun `an edit is kept by its file's path and can be dropped`() = runBlocking {
        val edit = LocalTrackEditEntity(file.filePath, artist = "Someone")
        dao.upsertTrackEdit(edit)
        assertEquals(edit, dao.getTrackEdit(file.filePath))
        assertEquals(listOf(edit), dao.getAllTrackEdits())
        dao.deleteTrackEdit(file.filePath)
        assertNull(dao.getTrackEdit(file.filePath))
    }

    /** The table MIGRATION_23_24 makes is the one Room expects, column for column. */
    @Test
    fun `the migration makes the table the app reads`() {
        fun columns(db: SupportSQLiteDatabase): List<String> =
            db.query("PRAGMA table_info(`local_track_edits`)").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add("${c.getString(1)} ${c.getString(2)} notnull=${c.getInt(3)} pk=${c.getInt(5)}")
                    }
                }.sorted()
            }
        val expected = columns(db.openHelper.writableDatabase)
        val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val w = fresh.openHelper.writableDatabase
            w.execSQL("DROP TABLE `local_track_edits`")
            MusicDatabase.MIGRATION_23_24.migrate(w)
            assertEquals(expected, columns(w))
            assertTrue(expected.isNotEmpty())
        } finally {
            fresh.close()
        }
    }
}
