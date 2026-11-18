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
import tf.monochrome.android.data.db.entity.EqPresetEntity

/**
 * v24 → v25 gives every EQ preset a headphone column. A preset saved before
 * keeps everything it had and has no headphone; the migrated column is the one
 * a fresh install creates, or Room refuses to open the upgraded database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EqPresetHeadphoneMigrationTest {

    private val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = fresh.close()

    /** name → (type, notnull, default) of eq_presets' columns. */
    private fun columns(db: SupportSQLiteDatabase): Map<String, Triple<String, Int, String?>> =
        db.query("PRAGMA table_info(`eq_presets`)").use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(1), Triple(c.getString(2), c.getInt(3), c.getString(4)))
                }
            }
        }

    /** An eq_presets table as v24 had it: today's columns without the headphone. */
    private fun v24(): SupportSQLiteDatabase {
        val cols = columns(fresh.openHelper.writableDatabase).filterKeys { it != "headphoneJson" }
        val create = cols.entries.joinToString(", ", "CREATE TABLE `eq_presets` (", ", PRIMARY KEY(`id`))") { (name, c) ->
            "`$name` ${c.first}" + (if (c.second == 1) " NOT NULL" else "") + (c.third?.let { " DEFAULT $it" } ?: "")
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(create)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        return helper.writableDatabase
    }

    @Test
    fun `an old preset keeps its bands and has no headphone`() {
        val db = v24()
        db.execSQL(
            "INSERT INTO eq_presets (id, name, description, bandsJson, preamp, targetId, targetName, " +
                "isCustom, createdAt, updatedAt, eqType) VALUES ('p', 'Old', '', '[1]', -2.0, 't', 'T', 1, 1, 2, 0)",
        )
        MusicDatabase.MIGRATION_24_25.migrate(db)
        db.query("SELECT name, bandsJson, headphoneJson FROM eq_presets").use { c ->
            c.moveToFirst()
            assertEquals("Old", c.getString(0))
            assertEquals("[1]", c.getString(1))
            assertNull(c.getString(2))
        }
        db.close()
    }

    @Test
    fun `the migrated table matches a fresh install's`() {
        val db = v24()
        MusicDatabase.MIGRATION_24_25.migrate(db)
        assertEquals(columns(fresh.openHelper.writableDatabase), columns(db))
        db.close()
    }

    @Test
    fun `a preset's headphone is stored and read back`() = runBlocking {
        val dao = fresh.eqPresetDao()
        val headphone = """{"id":"a","name":"A","left":{"curve":"20.0,1.0"}}"""
        dao.insertPreset(EqPresetEntity(id = "p", name = "P", headphoneJson = headphone))
        assertEquals(headphone, dao.getPresetById("p")?.headphoneJson)
    }
}
