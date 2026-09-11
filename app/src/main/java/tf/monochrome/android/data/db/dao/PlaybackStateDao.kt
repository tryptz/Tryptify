package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import tf.monochrome.android.data.db.entity.PlaybackQueueEntity
import tf.monochrome.android.data.db.entity.PlaybackStateEntity

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getState(): PlaybackStateEntity?

    @Query("SELECT * FROM playback_queue WHERE id = 1")
    suspend fun getQueue(): PlaybackQueueEntity?

    @Upsert
    suspend fun upsertState(state: PlaybackStateEntity)

    @Upsert
    suspend fun upsertQueue(queue: PlaybackQueueEntity)

    /**
     * The hot path, written every few seconds while playing.
     *
     * A column-level UPDATE rather than an upsert of the whole row: the queue
     * shape (index, shuffle, repeat) is owned by the queue collector, and a
     * position write that carried a stale copy of those would undo an edit made
     * between the two.
     */
    @Query(
        "UPDATE playback_state SET positionMs = :positionMs, durationMs = :durationMs, " +
            "currentTrackId = :trackId, updatedAt = :updatedAt WHERE id = 1"
    )
    suspend fun updatePosition(
        positionMs: Long,
        durationMs: Long,
        trackId: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM playback_state")
    suspend fun clearState()

    @Query("DELETE FROM playback_queue")
    suspend fun clearQueue()
}
