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

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun getPlaylistTrack(playlistId: String, trackId: Long): PlaylistTrackEntity?

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getPlaylistTrackCount(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackIfNotExists(track: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: Long)

    /**
     * Puts [replacement] where [oldTrackId] is: same position, same date added,
     * and the cover if the old row was it. When the replacement is already in
     * the playlist the old row is only removed — the song is there once already,
     * and the key is (playlist, track), so it could not be there twice.
     */
    @Transaction
    suspend fun replacePlaylistTrack(
        playlistId: String,
        oldTrackId: Long,
        replacement: PlaylistTrackEntity,
    ): PlaylistTrackReplacement {
        val old = getPlaylistTrack(playlistId, oldTrackId) ?: return PlaylistTrackReplacement.GONE
        if (replacement.trackId == oldTrackId) return PlaylistTrackReplacement.UNCHANGED
        val alreadyThere = getPlaylistTrack(playlistId, replacement.trackId) != null
        removeTrackFromPlaylist(playlistId, oldTrackId)
        if (!alreadyThere) {
            insertPlaylistTrack(
                replacement.copy(playlistId = playlistId, position = old.position, addedAt = old.addedAt)
            )
        }
        getPlaylist(playlistId)?.let { playlist ->
            updatePlaylist(
                playlist.copy(
                    coverTrackId = if (playlist.coverTrackId == oldTrackId && !alreadyThere) {
                        replacement.trackId
                    } else playlist.coverTrackId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        return if (alreadyThere) PlaylistTrackReplacement.MERGED else PlaylistTrackReplacement.REPLACED
    }

    @Transaction
    suspend fun addTrackToPlaylist(playlistId: String, track: PlaylistTrackEntity) {
        val count = getPlaylistTrackCount(playlistId)
        insertPlaylistTrack(track.copy(position = count))
        // Update playlist timestamp
        getPlaylist(playlistId)?.let { playlist ->
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}

/** What [PlaylistDao.replacePlaylistTrack] did. */
enum class PlaylistTrackReplacement {
    /** The old row now holds the replacement. */
    REPLACED,

    /** The replacement was already in the playlist, so the old row was removed. */
    MERGED,

    /** The replacement is the row itself; nothing was written. */
    UNCHANGED,

    /** The old row was no longer in the playlist; nothing was written. */
    GONE,
}
