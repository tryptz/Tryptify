package tf.monochrome.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The play head: which track, how far in, and how the queue is being walked.
 *
 * Split from [PlaybackQueueEntity] on purpose: this row is tiny and updated
 * every few seconds, the blob is large and changes only with the queue. SQLite
 * rewrites a whole record on any UPDATE, so one row would mean re-writing a
 * hundred-kilobyte blob every ten seconds for a six-byte number.
 *
 * [currentTrackId] sits beside the position so a restore can tell the two still
 * belong together — a queue edit between the writes would otherwise drop one
 * song's play head onto another.
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 1,
    val currentIndex: Int = -1,
    val currentTrackId: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "OFF",
    val updatedAt: Long = 0,
)

/** The queue itself, serialized. See `PlaybackSnapshot.kt` for the shape. */
@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(
    @PrimaryKey val id: Int = 1,
    val queueJson: String = "",
    val updatedAt: Long = 0,
)
