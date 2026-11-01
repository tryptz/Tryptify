package tf.monochrome.android.data.db

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.domain.model.scxytLegacyId

/**
 * MIGRATION_22_23 moves saved YouTube and SoundCloud rows from their plain
 * hash into SCXYT_ID_BASE's range, and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScxytIdMigrationTest {

    private val db = SQLiteDatabase.create(null)

    @After fun close() = db.close()

    private fun idsAfterMove(): Map<String, Long> {
        db.execSQL("CREATE TABLE favorite_tracks (id INTEGER PRIMARY KEY, title TEXT, source TEXT)")
        // A YouTube hash that is negative as an Int, one that is positive, a
        // TIDAL song, an older row with no source, and one already moved.
        val ytNegative = "yt:dQw4w9WgXcQ"
        val scPositive = (0..200).map { "sc:$it" }.first { it.hashCode() > 0 }
        listOf(
            Triple(ytNegative.hashCode().toLong(), "neg", "youtube"),
            Triple(scPositive.hashCode().toLong(), "pos", "soundcloud"),
            Triple(123_456_789L, "tidal", "tidal"),
            Triple(987_654L, "old", null),
            Triple(scxytLegacyId("yt:already"), "moved", "youtube"),
        ).forEach { (id, title, source) ->
            db.execSQL("INSERT INTO favorite_tracks VALUES (?, ?, ?)", arrayOf(id, title, source))
        }
        db.execSQL(MusicDatabase.scxytIdMove("favorite_tracks", "id"))
        return db.rawQuery("SELECT title, id FROM favorite_tracks", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }
    }

    @Test
    fun `saved YouTube and SoundCloud rows get the id the app now gives them`() {
        val ids = idsAfterMove()
        assertEquals(scxytLegacyId("yt:dQw4w9WgXcQ"), ids["neg"])
        assertEquals(scxytLegacyId((0..200).map { "sc:$it" }.first { it.hashCode() > 0 }), ids["pos"])
    }

    @Test
    fun `other rows and rows already moved are left alone`() {
        val ids = idsAfterMove()
        assertEquals(123_456_789L, ids["tidal"])
        assertEquals(987_654L, ids["old"])
        assertEquals(scxytLegacyId("yt:already"), ids["moved"])
    }
}
