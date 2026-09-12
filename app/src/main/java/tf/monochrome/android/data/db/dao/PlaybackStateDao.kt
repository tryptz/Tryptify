package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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
     * The queue and the play head into it, written together or not at all.
     *
     * They used to be two calls under a mutex, which orders writers but does
     * nothing about the process dying between them. That left a new queue
     * beside the previous queue's index and position, and restore trusted the
     * index — reopening whatever track happened to sit at that slot, at the
     * wrong second. A snapshot is one fact; this makes it one write.
     */
    @Transaction
    suspend fun upsertSnapshot(queue: PlaybackQueueEntity, state: PlaybackStateEntity) {
        upsertQueue(queue)
        upsertState(state)
    }

    /**
     * The hot path, written every few seconds while playing. A column-level
     * UPDATE rather than a whole-row upsert: the queue shape is owned by the
     * queue collector, and a position write carrying a stale copy of it would
     * undo an edit made between the two.
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
