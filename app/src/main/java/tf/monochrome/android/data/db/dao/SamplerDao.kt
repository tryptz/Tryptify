// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.PatternChannelEntity
import tf.monochrome.android.data.db.entity.PatternEntity
import tf.monochrome.android.data.db.entity.PatternStepEntity
import tf.monochrome.android.data.db.entity.SampleEntity

@Dao
interface SampleDao {

    @Query("SELECT * FROM sampler_samples ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SampleEntity>>

    @Query("SELECT * FROM sampler_samples WHERE category = :category ORDER BY createdAt DESC")
    fun observeByCategory(category: String): Flow<List<SampleEntity>>

    /**
     * Browser search. Matches on the pre-folded [SampleEntity.nameKey] rather
     * than on `name`, so the index is usable — SQLite's LIKE only takes the
     * prefix optimisation on a NOCASE column, and folding at write time is the
     * cheaper of the two ways to get there.
     */
    @Query(
        "SELECT * FROM sampler_samples WHERE nameKey LIKE :prefix || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun search(prefix: String, limit: Int = 200): Flow<List<SampleEntity>>

    @Query("SELECT * FROM sampler_samples WHERE id = :id")
    suspend fun byId(id: Long): SampleEntity?

    @Query("SELECT * FROM sampler_samples WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<SampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sample: SampleEntity): Long

    @Update
    suspend fun update(sample: SampleEntity)

    @Query("DELETE FROM sampler_samples WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Detaches a deleted sample from every channel that referenced it.
     *
     * Deliberately not a foreign key cascade: a cascade here would delete the
     * user's channels — and with them their step data — because they cleared a
     * sample out of the library. Emptying the slot is the behaviour that loses
     * nothing.
     */
    @Query("UPDATE sampler_pattern_channels SET sampleId = NULL WHERE sampleId = :sampleId")
    suspend fun detachFromChannels(sampleId: Long)

    @Query("SELECT COUNT(*) FROM sampler_samples")
    fun count(): Flow<Int>

    /** Paths still referenced, for the orphan sweep. Two columns, one pass each. */
    @Query("SELECT filePath FROM sampler_samples")
    suspend fun allAudioPaths(): List<String>

    @Query("SELECT waveformPath FROM sampler_samples WHERE waveformPath IS NOT NULL")
    suspend fun allWaveformPaths(): List<String>
}

@Dao
interface PatternDao {

    @Query("SELECT * FROM sampler_patterns ORDER BY bankSlot ASC")
    fun observePatterns(): Flow<List<PatternEntity>>

    @Query("SELECT * FROM sampler_patterns ORDER BY bankSlot ASC")
    suspend fun allPatterns(): List<PatternEntity>

    @Query("SELECT * FROM sampler_patterns WHERE id = :id")
    suspend fun pattern(id: Long): PatternEntity?

    @Query("SELECT * FROM sampler_patterns WHERE bankSlot = :slot")
    suspend fun patternAtSlot(slot: Int): PatternEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPattern(pattern: PatternEntity): Long

    @Query("DELETE FROM sampler_patterns WHERE id = :id")
    suspend fun deletePattern(id: Long)

    @Query("SELECT * FROM sampler_pattern_channels WHERE patternId = :patternId ORDER BY position ASC")
    suspend fun channels(patternId: Long): List<PatternChannelEntity>

    @Query("SELECT * FROM sampler_pattern_channels ORDER BY patternId ASC, position ASC")
    fun observeAllChannels(): Flow<List<PatternChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: PatternChannelEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<PatternChannelEntity>): List<Long>

    @Delete
    suspend fun deleteChannel(channel: PatternChannelEntity)

    @Query("DELETE FROM sampler_pattern_channels WHERE patternId = :patternId")
    suspend fun deleteChannelsOf(patternId: Long)

    @Query(
        "SELECT s.* FROM sampler_pattern_steps s " +
            "INNER JOIN sampler_pattern_channels c ON c.id = s.channelId " +
            "WHERE c.patternId = :patternId"
    )
    suspend fun steps(patternId: Long): List<PatternStepEntity>

    @Query("SELECT * FROM sampler_pattern_steps WHERE channelId = :channelId")
    suspend fun stepsOfChannel(channelId: Long): List<PatternStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStep(step: PatternStepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<PatternStepEntity>)

    @Query("DELETE FROM sampler_pattern_steps WHERE channelId = :channelId AND stepIndex = :stepIndex")
    suspend fun deleteStep(channelId: Long, stepIndex: Int)

    @Query("DELETE FROM sampler_pattern_steps WHERE channelId = :channelId")
    suspend fun clearChannelSteps(channelId: Long)

    @Query(
        "DELETE FROM sampler_pattern_steps WHERE channelId IN " +
            "(SELECT id FROM sampler_pattern_channels WHERE patternId = :patternId)"
    )
    suspend fun clearPatternSteps(patternId: Long)

    /** Trims steps that fall outside a newly shortened pattern. */
    @Query(
        "DELETE FROM sampler_pattern_steps WHERE stepIndex >= :length AND channelId IN " +
            "(SELECT id FROM sampler_pattern_channels WHERE patternId = :patternId)"
    )
    suspend fun trimStepsBeyond(patternId: Long, length: Int)

    @Query("SELECT COALESCE(MAX(bankSlot), -1) FROM sampler_patterns")
    suspend fun highestSlot(): Int

}
