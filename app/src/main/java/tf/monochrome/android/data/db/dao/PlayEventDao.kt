package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.PlayEventEntity

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEventEntity): Long

    /**
     * Insert a cloud-origin row; silently ignores duplicates detected via
     * the unique cloudRowId index, so repeated pulls are idempotent.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFromCloud(event: PlayEventEntity): Long

    /** [insertFromCloud] for many, in one transaction: the cloud pull's. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllFromCloud(events: List<PlayEventEntity>)

    @Query("UPDATE play_events SET cloudRowId = :cloudId WHERE rowId = :localId AND cloudRowId IS NULL")
    suspend fun setCloudId(localId: Long, cloudId: Long)

    @Query("DELETE FROM play_events")
    suspend fun clearAll()

    /**
     * Plays of each track on [albums], ever and since [since]: for the library
     * galaxy, which planets are played most and which glow. Matched on the
     * album and track titles as the tags spell them, because a local play
     * records those and not the file's row.
     */
    @Query(
        "SELECT title AS title, albumTitle AS albumTitle, COUNT(*) AS plays, " +
            "SUM(CASE WHEN playedAt >= :since THEN 1 ELSE 0 END) AS recent " +
            "FROM play_events WHERE albumTitle IN (:albums) GROUP BY title, albumTitle"
    )
    suspend fun playsOnAlbums(albums: List<String>, since: Long): List<AlbumTrackPlays>

    /**
     * Plays the cloud has never accepted, played before [before], in row order
     * after [afterRowId]. Walking by row id means a backfill always moves on,
     * even past a row it could not settle this time.
     */
    @Query(
        "SELECT * FROM play_events WHERE cloudRowId IS NULL AND playedAt < :before " +
            "AND rowId > :afterRowId ORDER BY rowId LIMIT :limit"
    )
    suspend fun getUnsyncedBatch(before: Long, afterRowId: Long, limit: Int): List<PlayEventEntity>

    @Query("SELECT COUNT(*) FROM play_events WHERE cloudRowId = :cloudId")
    suspend fun countWithCloudId(cloudId: Long): Int

    @Query("DELETE FROM play_events WHERE rowId = :rowId")
    suspend fun deleteRow(rowId: Long)

    /**
     * Ties a local play to the cloud row that already holds it.
     *
     * When another local row holds that cloud id already, this one is a
     * duplicate of it: the upload landed, its reply was lost, and a later pull
     * brought the cloud copy down as a second row. Dropping this one is what
     * makes the stats count the play once. In one transaction, so a pull
     * inserting the same id in between cannot trip the unique index.
     */
    @Transaction
    suspend fun adoptCloudId(localId: Long, cloudId: Long) {
        if (countWithCloudId(cloudId) > 0) deleteRow(localId) else setCloudId(localId, cloudId)
    }

    // --- Stats report ---
    //
    // The Stats screen reads the plays of a window as slim rows and does the
    // arithmetic in Kotlin (StatsReportBuilder), rather than one aggregate
    // query per chart: a 30-day window is a few hundred rows, and every chart
    // then agrees on what a day, a session or a new artist is.

    /** Every play in [from, until), oldest first, without the cover and ids. */
    @Query(
        "SELECT playedAt, trackId, title, artistName, albumTitle, duration, audioQuality, source " +
            "FROM play_events WHERE playedAt >= :from AND playedAt < :until ORDER BY playedAt"
    )
    suspend fun playsBetween(from: Long, until: Long): List<PlayRow>

    /** When each artist was first played, ever: what makes an artist "new". */
    @Query("SELECT artistName AS name, MIN(playedAt) AS firstPlayed FROM play_events WHERE artistName != '' GROUP BY artistName")
    suspend fun artistFirstPlays(): List<ArtistFirstPlay>

    /** Just when and how long, for the 12-month trend and the streak. */
    @Query("SELECT playedAt, duration FROM play_events WHERE playedAt >= :from ORDER BY playedAt")
    suspend fun momentsSince(from: Long): List<PlayMoment>

    @Query("SELECT MIN(playedAt) FROM play_events")
    suspend fun firstPlayedAt(): Long?

    /** Plays started between midnight and 5 am local time, ever: Night Owl. 5 is NIGHT_END_HOUR, night's end in every stat. */
    @Query("SELECT COUNT(*) FROM play_events WHERE CAST(strftime('%H', playedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) < 5")
    suspend fun playsAfterMidnight(): Int

    /**
     * Seconds of Hi-Res tracks played, ever: Golden Ears. The same test as
     * qualityTierOf, which the Quality card uses: "HI_RES" with a literal
     * underscore (in LIKE a bare `_` is any character, so "HI-RES" counted
     * here while the card called it lossy) or "HIRES".
     */
    @Query("SELECT COALESCE(SUM(duration), 0) FROM play_events WHERE UPPER(audioQuality) LIKE '%HI!_RES%' ESCAPE '!' OR UPPER(audioQuality) LIKE '%HIRES%'")
    suspend fun hiResSeconds(): Long

    /** Changes whenever a play is added or removed: the report's refresh signal. */
    @Query("SELECT COUNT(*) FROM play_events")
    fun playCount(): Flow<Int>

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

    /**
     * Number of listening sessions in range. A new session starts whenever
     * the gap between consecutive plays exceeds 30 min (1,800,000 ms). The
     * very first play in the window is also a session start (LAG defaults
     * to 0 so the gap is effectively infinite).
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT (playedAt - LAG(playedAt, 1, 0)
                    OVER (ORDER BY playedAt)) AS gap
            FROM play_events
            WHERE playedAt >= :since
        ) WHERE gap > 1800000
    """)
    fun sessionCount(since: Long = 0L): Flow<Int>

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

/** One play as the Stats report reads it. */
data class PlayRow(
    val playedAt: Long,
    val trackId: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val duration: Int,
    val audioQuality: String?,
    val source: String?,
)

data class ArtistFirstPlay(val name: String, val firstPlayed: Long)

data class PlayMoment(val playedAt: Long, val duration: Int)

data class QualityAggregate(val quality: String, val playCount: Int)
data class SourceAggregate(val source: String, val playCount: Int)
data class DayAggregate(val dayEpoch: Long, val playCount: Int)
data class HourAggregate(val hour: Int, val playCount: Int)
data class WeekdayAggregate(val weekday: Int, val playCount: Int)

/** A track's plays on an album, ever and lately. */
data class AlbumTrackPlays(
    val title: String,
    val albumTitle: String?,
    val plays: Int,
    val recent: Int,
)
