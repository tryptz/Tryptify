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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: MixPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(preset: MixPresetEntity): Long

    @Query("DELETE FROM mix_presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM mix_presets")
    fun getPresetCount(): Flow<Int>
}
