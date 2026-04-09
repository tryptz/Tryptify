package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity

@Dao
interface PlaylistDao {
    // Playlists
    @Query("SELECT * FROM user_playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<UserPlaylistEntity>>

    @Query("SELECT * FROM user_playlists ORDER BY updatedAt DESC")
    suspend fun getAllPlaylistsSnapshot(): List<UserPlaylistEntity>

    @Query("SELECT * FROM user_playlists WHERE id = :playlistId")
    suspend fun getPlaylist(playlistId: String): UserPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: UserPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistIfNotExists(playlist: UserPlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: UserPlaylistEntity)

    @Query("DELETE FROM user_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    // Playlist tracks
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getPlaylistTracks(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistTracksSnapshot(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getPlaylistTrackCount(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackIfNotExists(track: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Transaction
    suspend fun addTrackToPlaylist(playlistId: String, track: PlaylistTrackEntity) {
        val count = getPlaylistTrackCount(playlistId)
        insertPlaylistTrack(track.copy(position = count))
        // Update playlist timestamp
        getPlaylist(playlistId)?.let { playlist ->
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    @Transaction
    suspend fun reorderPlaylistTracks(playlistId: String, tracks: List<PlaylistTrackEntity>) {
        clearPlaylistTracks(playlistId)
        tracks.forEachIndexed { index, track ->
            insertPlaylistTrack(track.copy(position = index))
        }
    }

    // Check if track exists in any playlist
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE trackId = :trackId)")
    suspend fun isTrackInAnyPlaylist(trackId: Long): Boolean
}
