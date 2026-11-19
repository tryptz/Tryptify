package tf.monochrome.android.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.data.local.db.LocalDepartedTrackEntity
import tf.monochrome.android.data.local.db.LocalTrackEntity

/**
 * v25 → v26 adds the table of removed tracks. The migrated table is the one a
 * fresh install creates, or Room refuses to open the upgraded database; and a
 * track written again under its id keeps that id, which the whole idea of a
 * moved file rests on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DepartedTracksMigrationTest {

    private val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = fresh.close()

    /** name → (type, notnull, default, pk) of the table's columns. */
    private fun columns(db: SupportSQLiteDatabase): Map<String, List<Any?>> =
        db.query("PRAGMA table_info(`local_departed_tracks`)").use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getString(1), listOf(c.getString(2), c.getInt(3), c.getString(4), c.getInt(5)))
            }
        }

    @Test
    fun `the migrated table is the one a fresh install has`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val migrated = helper.writableDatabase
        MusicDatabase.MIGRATION_25_26.migrate(migrated)
        assertEquals(columns(fresh.openHelper.writableDatabase), columns(migrated))
        assertEquals(6, columns(migrated).size)
        helper.close()
    }

    private fun track(path: String, id: Long = 0, title: String = "Song") = LocalTrackEntity(
        id = id, filePath = path, fileSizeBytes = 1000, lastModified = 5_000, title = title, codec = "FLAC", sampleRate = 44100, bitRate = 0,
        channels = 2, durationSeconds = 100,
    )

    @Test
    fun `a track read again, or moved, keeps its row number`() = runBlocking {
        val dao = fresh.localMediaDao()
        dao.insertTracks(listOf(track("/old/Song.flac"), track("/old/Other.flac")))
        val before = dao.getAllTrackScanInfo().associateBy { it.filePath }
        val id = before.getValue("/old/Song.flac").id

        // Read again where it is: the same row, the new tags.
        dao.insertTracks(listOf(track("/old/Song.flac", id = id, title = "Retagged")))
        assertEquals(id, dao.getAllTrackScanInfo().single { it.filePath == "/old/Song.flac" }.id)
        assertEquals("Retagged", dao.getAllTrackScanInfo().single { it.id == id }.title)

        // Moved: the same row at another path, and nothing left at the old one.
        dao.insertTracks(listOf(track("/new/Song.flac", id = id, title = "Retagged")))
        assertEquals("/new/Song.flac", dao.pathOfTrack(id))
        assertEquals(setOf("/new/Song.flac", "/old/Other.flac"), dao.getAllTrackPaths().toSet())
    }

    @Test
    fun `a removed track is remembered, and its corrections follow it`() = runBlocking {
        val dao = fresh.localMediaDao()
        dao.insertDeparted(listOf(LocalDepartedTrackEntity(41, "/old/Song.flac", 1000, 5_000, 9.5f, departedAt = 100)))
        dao.insertDeparted(listOf(LocalDepartedTrackEntity(42, "/old/Stale.flac", 1000, 5_000, null, departedAt = 10)))
        dao.dropDepartedBefore(50)
        assertEquals(listOf(41L), dao.getDeparted().map { it.id })
        assertEquals(9.5f, dao.getDeparted().single().dynamicRange)
        dao.deleteDeparted(listOf(41))
        assertEquals(emptyList<Long>(), dao.getDeparted().map { it.id })

        dao.upsertTrackEdit(tf.monochrome.android.data.local.db.LocalTrackEditEntity(filePath = "/old/Song.flac", title = "Mine"))
        dao.moveTrackEdit("/old/Song.flac", "/new/Song.flac")
        assertNull(dao.getTrackEdit("/old/Song.flac"))
        assertEquals("Mine", dao.getTrackEdit("/new/Song.flac")?.title)
    }
}
