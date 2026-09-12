package tf.monochrome.android.data.local.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMediaDao {

    // ── Tracks ──────────────────────────────────────────────────────

    @Query("SELECT * FROM local_tracks ORDER BY albumArtist, album, discNumber, trackNumber")
    fun getAllTracks(): Flow<List<LocalTrackEntity>>

    /**
     * The songs list, one page at a time.
     *
     * Eight queries rather than one @RawQuery with the ORDER BY pasted in.
     * Room checks these at build time; a RawQuery is a string, so a typo in a
     * column name is a crash when the tab opens instead of a failed build. The
     * list is the most-used screen in the app, which is exactly where that
     * trade should fall on the safe side.
     *
     * Every clause ends in `albumArtist, album, discNumber, trackNumber` — the
     * table's default order, and the order the Kotlin sort used to see. A
     * stable sort leaves rows the key ties on in the order they arrived, so
     * repeating the base order as a tiebreak reproduces that; and DESC repeats
     * it reversed, because the descending case reversed the whole sorted list,
     * ties and all. Reversing only the leading key would quietly reshuffle
     * every same-titled track. See LibrarySortTest.
     *
     * LOWER(COALESCE(title, filePath)) matches `displayTitle.lowercase()` for
     * every tagged file. Two known differences, both confined to sorting: an
     * untitled file sorts by its full path rather than by the bare filename
     * the row displays, and SQLite's LOWER only folds ASCII where Kotlin's
     * lowercase() is Unicode-aware.
     */
    @Query("SELECT * FROM local_tracks ORDER BY LOWER(COALESCE(title, filePath)) ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    fun pagedByNameAsc(): PagingSource<Int, LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY LOWER(COALESCE(title, filePath)) DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    fun pagedByNameDesc(): PagingSource<Int, LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY COALESCE(lastModified, 0) ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    fun pagedByDateAsc(): PagingSource<Int, LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY COALESCE(lastModified, 0) DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    fun pagedByDateDesc(): PagingSource<Int, LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY durationSeconds ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    fun pagedByTimeAsc(): PagingSource<Int, LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY durationSeconds DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    fun pagedByTimeDesc(): PagingSource<Int, LocalTrackEntity>

    // codec is NOT NULL in the schema, so no COALESCE. The Kotlin sort's
    // "\uFFFF" fallback only ever applied to an unmapped codec name, which
    // sorts as itself here rather than as UNKNOWN.
    @Query("SELECT * FROM local_tracks ORDER BY codec ASC, LOWER(COALESCE(title, filePath)) ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    fun pagedByFileTypeAsc(): PagingSource<Int, LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY codec DESC, LOWER(COALESCE(title, filePath)) DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    fun pagedByFileTypeDesc(): PagingSource<Int, LocalTrackEntity>

    /** How many tracks there are, without loading any of them. */
    @Query("SELECT COUNT(*) FROM local_tracks")
    fun countTracks(): Flow<Int>

    /**
     * The whole library in one sort order, once, for building a play queue.
     *
     * Tapping a row queues everything after it, and the paged list above only
     * ever holds the rows near the screen — so the queue has to come from
     * somewhere. It comes from here, on tap, off the main thread, instead of
     * the list being held in memory permanently just in case somebody presses
     * play. Same rows and same order as the paged queries; the ORDER BY
     * clauses are deliberately identical.
     */
    @Query("SELECT * FROM local_tracks ORDER BY LOWER(COALESCE(title, filePath)) ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    suspend fun snapshotByNameAsc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY LOWER(COALESCE(title, filePath)) DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    suspend fun snapshotByNameDesc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY COALESCE(lastModified, 0) ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    suspend fun snapshotByDateAsc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY COALESCE(lastModified, 0) DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    suspend fun snapshotByDateDesc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY durationSeconds ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    suspend fun snapshotByTimeAsc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY durationSeconds DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    suspend fun snapshotByTimeDesc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY codec ASC, LOWER(COALESCE(title, filePath)) ASC, albumArtist ASC, album ASC, discNumber ASC, trackNumber ASC")
    suspend fun snapshotByFileTypeAsc(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks ORDER BY codec DESC, LOWER(COALESCE(title, filePath)) DESC, albumArtist DESC, album DESC, discNumber DESC, trackNumber DESC")
    suspend fun snapshotByFileTypeDesc(): List<LocalTrackEntity>

    // `genre` is in the LIKE and indexed (see LocalMediaEntities). Searching
    // "techno" used to match only tracks with it in the title; now it finds
    // everything tagged with it, which is what a genre word means.
    @Query("SELECT * FROM local_tracks WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query OR albumArtist LIKE :query OR genre LIKE :query ORDER BY title")
    fun searchTracks(query: String): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE albumId = :albumId ORDER BY discNumber, trackNumber")
    fun getTracksByAlbum(albumId: Long): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE artistId = :artistId ORDER BY album, discNumber, trackNumber")
    fun getTracksByArtist(artistId: Long): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE genre = :genre ORDER BY album, trackNumber")
    fun getTracksByGenre(genre: String): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE filePath LIKE :folderPath || '%' AND filePath NOT LIKE :folderPath || '%/%' ORDER BY trackNumber, title")
    fun getTracksInFolder(folderPath: String): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE isrc = :isrc LIMIT 1")
    suspend fun findByIsrc(isrc: String): LocalTrackEntity?

    @Query("SELECT * FROM local_tracks WHERE filePath = :path LIMIT 1")
    suspend fun findByPath(path: String): LocalTrackEntity?

    @Query("SELECT * FROM local_tracks WHERE musicbrainzTrack = :mbId LIMIT 1")
    suspend fun findByMusicBrainzId(mbId: String): LocalTrackEntity?

    // Title-prefix shortlist for "play the on-device copy instead of streaming".
    // The pattern is the version-stripped title plus '%', so both directions
    // match ("Song" finds "Song (Remastered)" and vice versa); the caller does
    // the real title/artist/duration comparison in Kotlin. Capped because a
    // one-word title can otherwise shortlist a big slice of the library.
    @Query("SELECT * FROM local_tracks WHERE title LIKE :titlePattern ESCAPE '\\' LIMIT 200")
    suspend fun findByTitlePattern(titlePattern: String): List<LocalTrackEntity>

    // Indexed form of the shortlist above: a half-open range over the folded
    // titleSearchKey column, which SQLite can satisfy from
    // index_local_tracks_titleSearchKey instead of scanning the table. LIKE
    // cannot be used for that here — see MusicDatabase.MIGRATION_12_13. Same
    // 200-row cap, for the same reason.
    @Query(
        "SELECT * FROM local_tracks " +
            "WHERE titleSearchKey >= :start AND titleSearchKey < :endExclusive LIMIT 200"
    )
    suspend fun findByTitlePrefix(start: String, endExclusive: String): List<LocalTrackEntity>

    @Query("SELECT COUNT(*) FROM local_tracks")
    suspend fun getTrackCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: LocalTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<LocalTrackEntity>)

    @Update
    suspend fun updateTrack(track: LocalTrackEntity)

    @Update
    suspend fun updateTracks(tracks: List<LocalTrackEntity>)

    @Query("SELECT * FROM local_tracks")
    suspend fun getAllTracksSnapshot(): List<LocalTrackEntity>

    @Query("DELETE FROM local_tracks WHERE filePath = :path")
    suspend fun deleteTrackByPath(path: String)

    @Query("DELETE FROM local_tracks WHERE filePath IN (:paths)")
    suspend fun deleteTracksByPaths(paths: List<String>)

    @Query("SELECT filePath FROM local_tracks")
    suspend fun getAllTrackPaths(): List<String>

    @Query("SELECT filePath, lastModified, artworkCacheKey, hasEmbeddedArt, artist, title FROM local_tracks")
    suspend fun getAllTrackScanInfo(): List<TrackScanInfo>

    // Distinct because album-cover propagation gives every track in an album
    // the same key — a 500-track album is one row here, so the startup
    // artwork eviction probe stays cheap.
    @Query("SELECT DISTINCT artworkCacheKey FROM local_tracks WHERE artworkCacheKey IS NOT NULL")
    suspend fun getDistinctArtworkCacheKeys(): List<String>

    // ── Albums ──────────────────────────────────────────────────────

    @Query("SELECT * FROM local_albums ORDER BY artist, title")
    fun getAllAlbums(): Flow<List<LocalAlbumEntity>>

    @Query("SELECT * FROM local_albums WHERE id = :albumId")
    suspend fun getAlbumById(albumId: Long): LocalAlbumEntity?

    @Query("SELECT * FROM local_albums WHERE artist = :artistName ORDER BY year DESC, title")
    fun getAlbumsByArtist(artistName: String): Flow<List<LocalAlbumEntity>>

    @Query("SELECT * FROM local_albums WHERE groupingKey = :key LIMIT 1")
    suspend fun findAlbumByGroupingKey(key: String): LocalAlbumEntity?

    @Upsert
    suspend fun upsertAlbum(album: LocalAlbumEntity): Long

    @Query("DELETE FROM local_albums WHERE id NOT IN (SELECT DISTINCT albumId FROM local_tracks WHERE albumId IS NOT NULL)")
    suspend fun pruneOrphanAlbums()

    // ── Artists ─────────────────────────────────────────────────────

    @Query("SELECT * FROM local_artists ORDER BY name")
    fun getAllArtists(): Flow<List<LocalArtistEntity>>

    @Query("SELECT * FROM local_artists WHERE id = :artistId")
    suspend fun getArtistById(artistId: Long): LocalArtistEntity?

    @Query("SELECT * FROM local_artists WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findArtistByNormalizedName(normalizedName: String): LocalArtistEntity?

    @Upsert
    suspend fun upsertArtist(artist: LocalArtistEntity): Long

    @Query("DELETE FROM local_artists WHERE id NOT IN (SELECT DISTINCT artistId FROM local_tracks WHERE artistId IS NOT NULL)")
    suspend fun pruneOrphanArtists()

    // ── Genres ──────────────────────────────────────────────────────

    @Query("SELECT * FROM local_genres ORDER BY name")
    fun getAllGenres(): Flow<List<LocalGenreEntity>>

    @Query("SELECT * FROM local_genres WHERE name = :name LIMIT 1")
    suspend fun findGenreByName(name: String): LocalGenreEntity?

    @Upsert
    suspend fun upsertGenre(genre: LocalGenreEntity): Long

    @Query("DELETE FROM local_genres WHERE id NOT IN (SELECT DISTINCT lg.id FROM local_genres lg INNER JOIN local_tracks lt ON lt.genre = lg.name)")
    suspend fun pruneOrphanGenres()

    // ── Folders ─────────────────────────────────────────────────────

    @Query("SELECT * FROM local_folders WHERE parentPath = :parentPath ORDER BY displayName")
    fun getSubfolders(parentPath: String): Flow<List<LocalFolderEntity>>

    @Query("SELECT * FROM local_folders WHERE parentPath IS NULL ORDER BY displayName")
    fun getRootFolders(): Flow<List<LocalFolderEntity>>

    @Upsert
    suspend fun upsertFolder(folder: LocalFolderEntity)

    @Upsert
    suspend fun upsertFolders(folders: List<LocalFolderEntity>)

    @Query("DELETE FROM local_folders")
    suspend fun clearFolders()

    // ── Scan State ──────────────────────────────────────────────────

    @Query("SELECT * FROM scan_state WHERE id = 1")
    suspend fun getScanState(): ScanStateEntity?

    @Upsert
    suspend fun updateScanState(state: ScanStateEntity)

    // ── Artwork store ───────────────────────────────────────────────

    // Repoint keys under [oldPrefix] at [newPrefix], for the one-time move out
    // of cacheDir. [oldPrefix] ends in a slash so "…/artwork_backup/" cannot
    // match, and the LIKE keeps REPLACE away from the sidecar covers and raw
    // file paths sharing the column.
    @Query(
        "UPDATE local_tracks SET artworkCacheKey = " +
            "REPLACE(artworkCacheKey, :oldPrefix, :newPrefix) " +
            "WHERE artworkCacheKey LIKE :oldPrefix || '%'"
    )
    suspend fun repointTrackArtwork(oldPrefix: String, newPrefix: String): Int

    @Query(
        "UPDATE local_albums SET artworkCacheKey = " +
            "REPLACE(artworkCacheKey, :oldPrefix, :newPrefix) " +
            "WHERE artworkCacheKey LIKE :oldPrefix || '%'"
    )
    suspend fun repointAlbumArtwork(oldPrefix: String, newPrefix: String): Int

    @Query(
        "UPDATE local_artists SET artworkCacheKey = " +
            "REPLACE(artworkCacheKey, :oldPrefix, :newPrefix) " +
            "WHERE artworkCacheKey LIKE :oldPrefix || '%'"
    )
    suspend fun repointArtistArtwork(oldPrefix: String, newPrefix: String): Int

    // Every key an orphan sweep must keep alive. Albums and artists carry
    // their own copies, so consulting local_tracks alone would delete a cover
    // an album row still points at.
    @Query(
        "SELECT artworkCacheKey FROM local_tracks WHERE artworkCacheKey IS NOT NULL " +
            "UNION SELECT artworkCacheKey FROM local_albums WHERE artworkCacheKey IS NOT NULL " +
            "UNION SELECT artworkCacheKey FROM local_artists WHERE artworkCacheKey IS NOT NULL"
    )
    suspend fun getAllReferencedArtworkKeys(): List<String>

    // ── Bulk operations ─────────────────────────────────────────────

    @Query("DELETE FROM local_tracks")
    suspend fun clearAllTracks()

    @Query("DELETE FROM local_albums")
    suspend fun clearAllAlbums()

    @Query("DELETE FROM local_artists")
    suspend fun clearAllArtists()

    @Query("DELETE FROM local_genres")
    suspend fun clearAllGenres()

    @Transaction
    suspend fun clearAll() {
        clearAllTracks()
        clearAllAlbums()
        clearAllArtists()
        clearAllGenres()
        clearFolders()
    }
}

/**
 * Lightweight projection of the columns the scanner's re-read heuristics
 * need, so a full scan can prefetch the whole table in one query instead
 * of issuing a per-file SELECT.
 */
data class TrackScanInfo(
    val filePath: String,
    val lastModified: Long,
    val artworkCacheKey: String?,
    val hasEmbeddedArt: Boolean,
    val artist: String?,
    val title: String?
)
