package tf.monochrome.android.data.sync

import kotlinx.serialization.Serializable
import tf.monochrome.android.data.db.entity.PlayEventEntity

/**
 * The pure half of uploading plays the cloud never accepted.
 *
 * A play is recorded in Room first and pushed after; when the push fails the
 * row keeps a null cloudRowId and sits there. That used to be most plays: the
 * session was dropped whenever the app went to the background, so a play
 * recorded with the screen off went out under the anon key and was refused.
 * [SupabaseSyncRepository.uploadUnsyncedPlayEvents] sends those rows up, and
 * this file holds the rule that keeps that safe to repeat.
 */

/** How many plays one round trip carries, each way. */
internal const val PLAY_UPLOAD_BATCH = 200

/**
 * Plays newer than this are left to the push that follows the play itself.
 * A backfill that took one of those could insert it while that push is still
 * in flight, and the cloud would count the play twice.
 */
internal const val PLAY_UPLOAD_MIN_AGE_MS = 10 * 60 * 1000L

/** What identifies one play in both places: the same track at the same millisecond. */
internal data class PlayKey(val trackId: Long, val playedAtMs: Long)

internal val PlayEventEntity.playKey get() = PlayKey(trackId, playedAt)

/** The columns a backfill needs back from `play_events`. */
@Serializable
internal data class SbPlayEventKey(
    val id: Long,
    val track_id: Long,
    val played_at_ms: Long,
) {
    val key get() = PlayKey(track_id, played_at_ms)
}

/**
 * One row of a bulk insert into `play_events`.
 *
 * Every property is required and none has a default, so every row encodes
 * every NOT NULL column whatever the serializer's settings. That matters for a
 * bulk insert: the request names the union of all rows' keys, and a row that
 * leaves one out — as a default of 0 or "" is left out under
 * encodeDefaults = false — sends NULL for it, which `duration` and
 * `artist_name` refuse, and the whole batch fails with it.
 */
@Serializable
internal data class SbPlayEventUpload(
    val user_id: String,
    val track_id: Long,
    val title: String,
    val duration: Int,
    val artist_id: Long?,
    val artist_name: String,
    val album_id: Long?,
    val album_title: String?,
    val album_cover: String?,
    val audio_quality: String?,
    val source: String?,
    val played_at_ms: Long,
    val started_at: String,
)

internal fun PlayEventEntity.toUpload(userId: String) = SbPlayEventUpload(
    user_id = userId,
    track_id = trackId,
    title = title,
    duration = duration,
    artist_id = artistId,
    artist_name = artistName,
    album_id = albumId,
    album_title = albumTitle,
    album_cover = albumCover,
    audio_quality = audioQuality,
    source = source,
    played_at_ms = playedAt,
    started_at = java.time.Instant.ofEpochMilli(playedAt).toString(),
)

/** Local plays paired with the cloud rows that hold them, and the ones nothing holds. */
internal data class PlayMatch(
    val matched: List<Pair<PlayEventEntity, Long>>,
    val unmatched: List<PlayEventEntity>,
)

/**
 * Pairs local plays with cloud rows for the same track at the same
 * millisecond, one cloud row per local play.
 *
 * A local play the cloud already has is one whose upload landed and whose
 * reply never arrived. Inserting it again would count it twice, so it is
 * matched to the existing row instead. One-for-one, because two plays of a
 * track in the same millisecond are two rows on both sides: each local row
 * takes the next unclaimed cloud id, and any left over are inserted.
 */
internal fun matchPlays(local: List<PlayEventEntity>, cloud: List<SbPlayEventKey>): PlayMatch {
    val available = cloud.groupByTo(HashMap(), { it.key }, { it.id })
    val matched = ArrayList<Pair<PlayEventEntity, Long>>()
    val unmatched = ArrayList<PlayEventEntity>()
    for (play in local) {
        val ids = available[play.playKey]
        if (ids.isNullOrEmpty()) unmatched += play else matched += play to ids.removeAt(0)
    }
    return PlayMatch(matched, unmatched)
}
