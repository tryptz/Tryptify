package tf.monochrome.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The play head: which track, how far in, and how the queue is being walked.
 *
 * Split from [PlaybackQueueEntity] on purpose. This row is tiny and is updated
 * every few seconds while something is playing; the queue blob is large and
 * changes only when the queue actually does. SQLite rewrites a whole record on
 * any UPDATE, so keeping them in one row would mean re-writing a
 * hundred-kilobyte blob every ten seconds for the sake of a six-byte number.
 *
 * [currentTrackId] is stored beside the position so a restore can tell whether
 * the position still belongs to the track it is about to seek — a queue edit
 * that lands between the two writes would otherwise drop the play head of one
 * song onto another.
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
