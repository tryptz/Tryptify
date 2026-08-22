package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.EqPresetEntity

@Dao
interface EqPresetDao {
    // ==================== AutoEQ presets (eqType = 0) ====================

    /**
     * Get all AutoEQ presets (custom + built-in)
     */
    @Query("SELECT * FROM eq_presets WHERE eqType = 0 ORDER BY isCustom DESC, updatedAt DESC")
    fun getAllPresets(): Flow<List<EqPresetEntity>>

    /**
     * Get all custom user-created AutoEQ presets
     */
    @Query("SELECT * FROM eq_presets WHERE isCustom = 1 AND eqType = 0 ORDER BY updatedAt DESC")
    fun getCustomPresets(): Flow<List<EqPresetEntity>>

    /**
     * Get all built-in AutoEQ presets
     */
    @Query("SELECT * FROM eq_presets WHERE isCustom = 0 AND eqType = 0 ORDER BY name ASC")
    fun getBuiltInPresets(): Flow<List<EqPresetEntity>>

    /**
     * Get a specific preset by ID
     */
    @Query("SELECT * FROM eq_presets WHERE id = :presetId")
    suspend fun getPresetById(presetId: String): EqPresetEntity?

    /**
     * Get a preset as a Flow for reactive updates
     */
    @Query("SELECT * FROM eq_presets WHERE id = :presetId")
    fun getPresetByIdFlow(presetId: String): Flow<EqPresetEntity?>

    /**
     * Insert a new preset or update if exists
     */
    /** Every preset of both kinds at one moment, for the cloud push. */
    @Query("SELECT * FROM eq_presets ORDER BY updatedAt DESC")
    suspend fun getAllPresetsSnapshot(): List<EqPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: EqPresetEntity)

    /**
     * Insert multiple presets
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPresets(presets: List<EqPresetEntity>)

    /**
     * Update an existing preset
     */
    @Update
    suspend fun updatePreset(preset: EqPresetEntity)

    /**
     * Delete a preset by ID
     */
    @Query("DELETE FROM eq_presets WHERE id = :presetId")
    suspend fun deletePreset(presetId: String)

    /**
     * Delete all custom AutoEQ presets
     */
    @Query("DELETE FROM eq_presets WHERE isCustom = 1 AND eqType = 0")
    suspend fun deleteAllCustomPresets()

    /**
     * Check if a preset exists
     */
    @Query("SELECT EXISTS(SELECT 1 FROM eq_presets WHERE id = :presetId)")
    suspend fun presetExists(presetId: String): Boolean

    /**
     * Get count of all AutoEQ presets
     */
    @Query("SELECT COUNT(*) FROM eq_presets WHERE eqType = 0")
    fun getPresetCount(): Flow<Int>

    /**
     * Get count of custom AutoEQ presets
     */
    @Query("SELECT COUNT(*) FROM eq_presets WHERE isCustom = 1 AND eqType = 0")
    fun getCustomPresetCount(): Flow<Int>

    /**
     * Search AutoEQ presets by name
     */
    @Query("SELECT * FROM eq_presets WHERE eqType = 0 AND name LIKE '%' || :searchQuery || '%' ORDER BY isCustom DESC, updatedAt DESC")
    fun searchPresets(searchQuery: String): Flow<List<EqPresetEntity>>

    /**
     * Get AutoEQ presets for a specific target curve
     */
    @Query("SELECT * FROM eq_presets WHERE eqType = 0 AND targetId = :targetId ORDER BY isCustom DESC, updatedAt DESC")
    fun getPresetsByTarget(targetId: String): Flow<List<EqPresetEntity>>

    // ==================== Parametric EQ presets (eqType = 1) ====================

    /**
     * Get all Parametric EQ presets (custom + built-in)
     */
    @Query("SELECT * FROM eq_presets WHERE eqType = 1 ORDER BY isCustom DESC, updatedAt DESC")
    fun getAllParametricPresets(): Flow<List<EqPresetEntity>>

    /**
     * Get custom Parametric EQ presets
     */
    @Query("SELECT * FROM eq_presets WHERE isCustom = 1 AND eqType = 1 ORDER BY updatedAt DESC")
    fun getCustomParametricPresets(): Flow<List<EqPresetEntity>>

    /**
     * Count of custom Parametric EQ presets
     */
    @Query("SELECT COUNT(*) FROM eq_presets WHERE isCustom = 1 AND eqType = 1")
    fun getCustomParametricPresetCount(): Flow<Int>

    /**
     * Search Parametric EQ presets by name
     */
    @Query("SELECT * FROM eq_presets WHERE eqType = 1 AND name LIKE '%' || :searchQuery || '%' ORDER BY isCustom DESC, updatedAt DESC")
    fun searchParametricPresets(searchQuery: String): Flow<List<EqPresetEntity>>
}
