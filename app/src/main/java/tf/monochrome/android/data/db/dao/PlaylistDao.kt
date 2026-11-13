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

    /** [insertPlaylistIfNotExists] for many, in one transaction: the cloud pull's. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistsIfNotExist(playlists: List<UserPlaylistEntity>)

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

    /** [insertTrackIfNotExists] for many, in one transaction: the cloud pull's. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIfNotExist(tracks: List<PlaylistTrackEntity>)

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

    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun lastTrackPosition(playlistId: String): Int?

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun setTrackPosition(playlistId: String, trackId: Long, position: Int)

    /**
     * Puts the playlist in the order of [orderedTrackIds] and numbers its rows
     * 0, 1, 2… ([PlaylistOrder.renumbered] has the rules for ids that are
     * missing or unknown). Returns the ids of the rows whose position changed,
     * which are the ones the cloud has to hear about; empty when the playlist
     * was already in that order, and then nothing is written.
     */
    @Transaction
    suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<Long>): List<Long> {
        val rows = getPlaylistTracksSnapshot(playlistId)
        val moved = PlaylistOrder.renumbered(rows.map { it.trackId to it.position }, orderedTrackIds)
        if (moved.isEmpty()) return emptyList()
        moved.forEach { (trackId, position) -> setTrackPosition(playlistId, trackId, position) }
        getPlaylist(playlistId)?.let { playlist ->
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
        return moved.map { it.first }
    }

    @Transaction
    suspend fun addTrackToPlaylist(playlistId: String, track: PlaylistTrackEntity) {
        // After the last row, not at the row count: the count is one a row
        // already has once a song has been removed from the middle, and two
        // rows on one position have no order between them.
        val position = (lastTrackPosition(playlistId) ?: -1) + 1
        insertPlaylistTrack(track.copy(position = position))
        // Update playlist timestamp
        getPlaylist(playlistId)?.let { playlist ->
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * [addTrackToPlaylist] for a whole list (an imported playlist), in one
     * transaction: [tracks] in their order, after whatever the playlist
     * holds. A song the playlist already has, or that [tracks] names twice,
     * is not added again (the key is playlist and track); it stays where it
     * is. Returns the ids added, in order.
     */
    @Transaction
    suspend fun addTracksToPlaylist(playlistId: String, tracks: List<PlaylistTrackEntity>): List<Long> {
        var position = (lastTrackPosition(playlistId) ?: -1) + 1
        val added = ArrayList<Long>(tracks.size)
        for (track in tracks) {
            if (getPlaylistTrack(playlistId, track.trackId) != null) continue
            insertPlaylistTrack(track.copy(playlistId = playlistId, position = position++))
            added += track.trackId
        }
        if (added.isNotEmpty()) {
            getPlaylist(playlistId)?.let { playlist ->
                updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        return added
    }
}

/**
 * The arithmetic of [PlaylistDao.reorderTracks], kept free of the database so
 * it has a plain unit test.
 */
object PlaylistOrder {
    /**
     * [rows] is the playlist as stored, in the order it is shown: each row's
     * track id and position. [wanted] is the order asked for. The result is
     * every row whose position has to change, with its new one.
     *
     * The new order is [wanted], then every row it does not name, in the
     * order they were in: a song added while the list was being arranged
     * (from the player, by a sync) stays at the end instead of being lost. An
     * id in [wanted] that is no longer in the playlist is skipped, and one
     * named twice counts where it is first.
     *
     * Positions come out as 0, 1, 2…, whatever they were: rows that shared a
     * position, or left gaps after a removal, are put right by the first
     * reorder.
     */
    fun renumbered(rows: List<Pair<Long, Int>>, wanted: List<Long>): List<Pair<Long, Int>> {
        val present = rows.mapTo(LinkedHashSet()) { it.first }
        val order = LinkedHashSet<Long>()
        wanted.filterTo(order) { it in present }
        order.addAll(present)
        val positionOf = rows.toMap()
        return order.mapIndexedNotNull { index, trackId ->
            if (positionOf[trackId] == index) null else trackId to index
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
