package tf.monochrome.android.data.analysis

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Measured objective audio features for one song.
 *
 * Keyed by [normKey] = `normalize(artist)|normalize(title)` (the same
 * normaliser used for the Spotify dataset) so a song is analysed once and the
 * result is shared across every source that has it (local file, Qobuz, TIDAL)
 * and survives local-library rescans (whose row ids are not stable). All
 * values are *measured* on-device — no proprietary/ML estimates.
 */
@Entity(tableName = "audio_features")
data class AudioFeatureEntity(
    @PrimaryKey val normKey: String,
    val trackId: String,
    val title: String,
    val artist: String,
    /** Beats per minute (estimated from the onset envelope), 0 if undetected. */
    val tempoBpm: Float,
    /** Mean frame loudness in dBFS (negative; 0 = full scale). */
    val loudnessDb: Float,
    /** 0..1 loudness-derived energy. */
    val energy: Float,
    /** Pitch class 0=C … 11=B, or -1 if undetected. */
    val musicalKey: Int,
    /** 1 = major, 0 = minor, -1 if undetected. */
    val mode: Int,
    /** Mean spectral centroid in Hz ("brightness"). */
    val brightnessHz: Float,
    val durationMs: Long,
    val analyzedAt: Long,
    val schemaVersion: Int,
)

@Dao
interface AudioFeatureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feature: AudioFeatureEntity)

    @Query("SELECT * FROM audio_features WHERE normKey = :normKey LIMIT 1")
    suspend fun get(normKey: String): AudioFeatureEntity?

    @Query("SELECT normKey FROM audio_features")
    suspend fun allKeys(): List<String>

    @Query("SELECT COUNT(*) FROM audio_features")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM audio_features")
    suspend fun count(): Int

    @Query("DELETE FROM audio_features")
    suspend fun clear()
}

/**
 * Standalone database for measured audio features — deliberately separate from
 * MusicDatabase so adding/evolving this schema never bumps MusicDatabase's
 * version (which is built with fallbackToDestructiveMigration and would wipe
 * the user's library/favourites on a bump).
 */
@Database(entities = [AudioFeatureEntity::class], version = 1, exportSchema = false)
abstract class AudioFeatureDatabase : RoomDatabase() {
    abstract fun audioFeatureDao(): AudioFeatureDao
}
