package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.MixPresetEntity

@Dao
interface MixPresetDao {

    @Query("SELECT * FROM mix_presets ORDER BY updatedAt DESC")
    fun getAllPresets(): Flow<List<MixPresetEntity>>

    @Query("SELECT * FROM mix_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): MixPresetEntity?

    /** Every preset at one moment, for the cloud push. */
    @Query("SELECT * FROM mix_presets ORDER BY updatedAt DESC")
    suspend fun getAllPresetsSnapshot(): List<MixPresetEntity>

    /**
     * A preset by its creation time, which is the identity it syncs under.
     *
     * The row id is Room's autoGenerate Long and means nothing outside this
     * device, so it cannot be what the cloud matches on; createdAt is fixed at
     * the moment of saving and never rewritten.
     */
    @Query("SELECT * FROM mix_presets WHERE createdAt = :createdAt LIMIT 1")
    suspend fun getByCreatedAt(createdAt: Long): MixPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: MixPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(preset: MixPresetEntity): Long

    @Query("DELETE FROM mix_presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM mix_presets")
    fun getPresetCount(): Flow<Int>
}
