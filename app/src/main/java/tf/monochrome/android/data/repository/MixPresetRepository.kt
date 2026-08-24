package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tf.monochrome.android.audio.dsp.model.MixPreset
import tf.monochrome.android.audio.dsp.preset.BuiltInMixPresets
import tf.monochrome.android.data.db.dao.MixPresetDao
import tf.monochrome.android.data.db.entity.MixPresetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixPresetRepository @Inject constructor(
    private val dao: MixPresetDao,
    private val supabaseSync: SupabaseSyncRepository,
) {
    /**
     * Fire-and-forget cloud writes, so saving a preset never waits on the
     * network and never fails because of it. Same shape as
     * LibraryRepository's, and a no-op when signed out.
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getAllPresets(): Flow<List<MixPreset>> = dao.getAllPresets().map { entities ->
        BuiltInMixPresets.presets + entities.map { it.toDomain() }
    }

    suspend fun getPresetById(id: Long): MixPreset? =
        if (id < 0) BuiltInMixPresets.presets.find { it.id == id }
        else dao.getPresetById(id)?.toDomain()

    suspend fun savePreset(preset: MixPreset): Long {
        val entity = preset.toEntity()
        val rowId = dao.upsert(entity)
        // Pushed on save rather than only on a manual sync, so a preset made
        // on the phone is already in the account by the time the tablet next
        // opens. The entity is re-read because upsert is what assigns the row
        // id, and the copy above still carries 0.
        syncScope.launch {
            dao.getPresetById(rowId)?.takeIf { it.isCustom }?.let { supabaseSync.pushMixPreset(it) }
        }
        return rowId
    }

    suspend fun deletePreset(id: Long) {
        // Built-in presets (negative ids) are read-only.
        if (id < 0) return
        // Read before deleting: the cloud knows this preset by its creation
        // time, which is only available while the row still exists.
        val createdAt = dao.getPresetById(id)?.createdAt
        dao.delete(id)
        if (createdAt != null) syncScope.launch { supabaseSync.deleteMixPreset(createdAt) }
    }

    fun getPresetCount(): Flow<Int> = dao.getPresetCount()

    private fun MixPresetEntity.toDomain() = MixPreset(
        id = id,
        name = name,
        stateJson = stateJson,
        isCustom = isCustom,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun MixPreset.toEntity() = MixPresetEntity(
        id = id,
        name = name,
        stateJson = stateJson,
        isCustom = isCustom,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
