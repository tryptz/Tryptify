package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.PlayEventEntity

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEventEntity): Long

    @Query("DELETE FROM play_events")
    suspend fun clearAll()

    @Query("DELETE FROM play_events WHERE playedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    // --- Totals ---

    @Query("SELECT COUNT(*) FROM play_events WHERE playedAt >= :since")
    fun totalPlays(since: Long = 0L): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration), 0) FROM play_events WHERE playedAt >= :since")
    fun totalListeningSeconds(since: Long = 0L): Flow<Long>

    @Query("SELECT COUNT(DISTINCT trackId) FROM play_events WHERE playedAt >= :since")
    fun uniqueTracks(since: Long = 0L): Flow<Int>

    @Query("SELECT COUNT(DISTINCT artistId) FROM play_events WHERE artistId IS NOT NULL AND playedAt >= :since")
    fun uniqueArtists(since: Long = 0L): Flow<Int>

    @Query("SELECT COUNT(DISTINCT albumId) FROM play_events WHERE albumId IS NOT NULL AND playedAt >= :since")
    fun uniqueAlbums(since: Long = 0L): Flow<Int>

    // --- Top lists ---

    @Query("""
        SELECT trackId AS id, title, artistName, albumTitle, albumCover,
               COUNT(*) AS playCount, SUM(duration) AS totalSeconds, MAX(playedAt) AS lastPlayed
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY trackId
        ORDER BY playCount DESC, lastPlayed DESC
        LIMIT :limit
    """)
    fun topTracks(since: Long = 0L, limit: Int = 50): Flow<List<TopTrackAggregate>>

    @Query("""
        SELECT artistId AS id, artistName AS name, COUNT(*) AS playCount,
               SUM(duration) AS totalSeconds, COUNT(DISTINCT trackId) AS uniqueTracks,
               MAX(playedAt) AS lastPlayed
        FROM play_events
        WHERE artistName != '' AND playedAt >= :since
        GROUP BY artistName
        ORDER BY playCount DESC, lastPlayed DESC
        LIMIT :limit
    """)
    fun topArtists(since: Long = 0L, limit: Int = 50): Flow<List<TopArtistAggregate>>

    @Query("""
        SELECT albumId AS id, albumTitle AS title, artistName, albumCover,
               COUNT(*) AS playCount, SUM(duration) AS totalSeconds, MAX(playedAt) AS lastPlayed
        FROM play_events
        WHERE albumTitle IS NOT NULL AND albumTitle != '' AND playedAt >= :since
        GROUP BY albumTitle, artistName
        ORDER BY playCount DESC, lastPlayed DESC
        LIMIT :limit
    """)
    fun topAlbums(since: Long = 0L, limit: Int = 50): Flow<List<TopAlbumAggregate>>

    @Query("""
        SELECT COALESCE(audioQuality, 'Unknown') AS quality, COUNT(*) AS playCount
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY quality
        ORDER BY playCount DESC
    """)
    fun playsByQuality(since: Long = 0L): Flow<List<QualityAggregate>>

    @Query("""
        SELECT COALESCE(source, 'Unknown') AS source, COUNT(*) AS playCount
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY source
        ORDER BY playCount DESC
    """)
    fun playsBySource(since: Long = 0L): Flow<List<SourceAggregate>>

    // --- Time buckets ---

    @Query("""
        SELECT CAST(playedAt / 86400000 AS INTEGER) AS dayEpoch, COUNT(*) AS playCount
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    fun playsPerDay(since: Long = 0L): Flow<List<DayAggregate>>

    @Query("""
        SELECT CAST(strftime('%H', playedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS playCount
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY hour
        ORDER BY hour ASC
    """)
    fun playsByHour(since: Long = 0L): Flow<List<HourAggregate>>

    @Query("""
        SELECT CAST(strftime('%w', playedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS weekday,
               COUNT(*) AS playCount
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY weekday
        ORDER BY weekday ASC
    """)
    fun playsByWeekday(since: Long = 0L): Flow<List<WeekdayAggregate>>
}

data class TopTrackAggregate(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val albumCover: String?,
    val playCount: Int,
    val totalSeconds: Long,
    val lastPlayed: Long
)

data class TopArtistAggregate(
    val id: Long?,
    val name: String,
    val playCount: Int,
    val totalSeconds: Long,
    val uniqueTracks: Int,
    val lastPlayed: Long
)

data class TopAlbumAggregate(
    val id: Long?,
    val title: String,
    val artistName: String,
    val albumCover: String?,
    val playCount: Int,
    val totalSeconds: Long,
    val lastPlayed: Long
)

data class QualityAggregate(val quality: String, val playCount: Int)
data class SourceAggregate(val source: String, val playCount: Int)
data class DayAggregate(val dayEpoch: Long, val playCount: Int)
data class HourAggregate(val hour: Int, val playCount: Int)
data class WeekdayAggregate(val weekday: Int, val playCount: Int)
