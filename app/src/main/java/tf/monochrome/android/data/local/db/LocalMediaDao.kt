package tf.monochrome.android.data.local.db

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

    @Query("SELECT * FROM local_tracks WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query OR albumArtist LIKE :query ORDER BY title")
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

    @Query("DELETE FROM local_tracks WHERE filePath NOT IN (:existingPaths)")
    suspend fun deleteTracksNotIn(existingPaths: Set<String>)

    @Query("SELECT filePath FROM local_tracks")
    suspend fun getAllTrackPaths(): List<String>

    @Query("SELECT filePath, lastModified FROM local_tracks")
    suspend fun getAllTrackPathsWithModified(): List<TrackPathModified>

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

    @Query("DELETE FROM local_folders")
    suspend fun clearFolders()

    // ── Scan State ──────────────────────────────────────────────────

    @Query("SELECT * FROM scan_state WHERE id = 1")
    suspend fun getScanState(): ScanStateEntity?

    @Upsert
    suspend fun updateScanState(state: ScanStateEntity)

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

data class TrackPathModified(
    val filePath: String,
    val lastModified: Long
)
