package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.FavoriteDao
import tf.monochrome.android.data.db.dao.HistoryDao
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.db.entity.FavoriteAlbumEntity
import tf.monochrome.android.data.db.entity.FavoriteArtistEntity
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity
import tf.monochrome.android.data.db.entity.HistoryTrackEntity
import tf.monochrome.android.data.db.entity.PlayEventEntity
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val playEventDao: PlayEventDao,
    private val playlistDao: PlaylistDao,
    private val downloadDao: DownloadDao
) {
    // --- Favorites ---

    fun getFavoriteTracks(): Flow<List<Track>> = favoriteDao.getFavoriteTracks().map { entities ->
        entities.map { it.toDomain() }
    }

    fun getFavoriteAlbums(): Flow<List<Album>> = favoriteDao.getFavoriteAlbums().map { entities ->
        entities.map { it.toDomain() }
    }

    fun getFavoriteArtists(): Flow<List<Artist>> = favoriteDao.getFavoriteArtists().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun toggleFavoriteTrack(track: Track) {
        if (favoriteDao.isFavoriteTrack(track.id)) {
            favoriteDao.deleteFavoriteTrack(track.id)
        } else {
            favoriteDao.insertFavoriteTrack(track.toFavoriteEntity())
        }
    }

    suspend fun toggleFavoriteAlbum(album: Album) {
        if (favoriteDao.isFavoriteAlbum(album.id)) {
            favoriteDao.deleteFavoriteAlbum(album.id)
        } else {
            favoriteDao.insertFavoriteAlbum(album.toFavoriteEntity())
        }
    }

    suspend fun toggleFavoriteArtist(artist: Artist) {
        if (favoriteDao.isFavoriteArtist(artist.id)) {
            favoriteDao.deleteFavoriteArtist(artist.id)
        } else {
            favoriteDao.insertFavoriteArtist(artist.toFavoriteEntity())
        }
    }

    fun isFavoriteTrack(trackId: Long): Flow<Boolean> = favoriteDao.isFavoriteTrackFlow(trackId)
    fun isFavoriteAlbum(albumId: Long): Flow<Boolean> = favoriteDao.isFavoriteAlbumFlow(albumId)
    fun isFavoriteArtist(artistId: Long): Flow<Boolean> = favoriteDao.isFavoriteArtistFlow(artistId)

    // --- History ---

    fun getHistory(): Flow<List<Track>> = historyDao.getHistory().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun addToHistory(track: Track) {
        historyDao.addToHistory(track.toHistoryEntity())
        playEventDao.insert(track.toPlayEventEntity())
    }

    suspend fun clearHistory() {
        historyDao.clearHistory()
        playEventDao.clearAll()
    }

    // --- Play events / stats ---

    val playEventDaoRef: PlayEventDao get() = playEventDao

    suspend fun getMostPlayed(limit: Int = 50): List<Track> {
        return historyDao.getMostPlayed(limit).map { it.toDomain() }
    }

    // --- Playlists ---

    fun getAllPlaylists(): Flow<List<UserPlaylistEntity>> = playlistDao.getAllPlaylists()

    suspend fun createPlaylist(name: String, description: String? = null): String {
        val id = UUID.randomUUID().toString()
        playlistDao.insertPlaylist(
            UserPlaylistEntity(
                id = id,
                name = name,
                description = description
            )
        )
        return id
    }

    suspend fun updatePlaylist(playlistId: String, name: String, description: String?, isPublic: Boolean = false) {
        playlistDao.getPlaylist(playlistId)?.let { playlist ->
            playlistDao.updatePlaylist(
                playlist.copy(
                    name = name,
                    description = description,
                    isPublic = isPublic,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    fun getPlaylistTracks(playlistId: String): Flow<List<Track>> {
        return playlistDao.getPlaylistTracks(playlistId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: Track) {
        playlistDao.addTrackToPlaylist(playlistId, track.toPlaylistTrackEntity(playlistId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    // --- Downloads ---

    fun getDownloadedTracks(): Flow<List<DownloadedTrackEntity>> = downloadDao.getDownloadedTracks()

    suspend fun isDownloaded(trackId: Long): Boolean = downloadDao.isDownloaded(trackId)

    fun isDownloadedFlow(trackId: Long): Flow<Boolean> = downloadDao.isDownloadedFlow(trackId)

    suspend fun getDownloadedTrack(trackId: Long): DownloadedTrackEntity? =
        downloadDao.getDownloadedTrack(trackId)

    fun getTotalDownloadSize(): Flow<Long?> = downloadDao.getTotalDownloadSize()
}

// --- Entity <-> Domain conversion ---

private fun FavoriteTrackEntity.toDomain() = Track(
    id = id,
    title = title,
    duration = duration,
    artist = Artist(id = artistId ?: 0, name = artistName),
    album = albumId?.let { Album(id = it, title = albumTitle ?: "", cover = albumCover) },
    audioQuality = audioQuality,
    explicit = explicit,
    trackNumber = trackNumber
)

private fun FavoriteAlbumEntity.toDomain() = Album(
    id = id,
    title = title,
    artist = Artist(id = artistId ?: 0, name = artistName),
    cover = cover,
    numberOfTracks = numberOfTracks,
    releaseDate = releaseDate,
    type = type
)

private fun FavoriteArtistEntity.toDomain() = Artist(
    id = id,
    name = name,
    picture = picture
)

private fun HistoryTrackEntity.toDomain() = Track(
    id = id,
    title = title,
    duration = duration,
    artist = Artist(id = artistId ?: 0, name = artistName),
    album = albumId?.let { Album(id = it, title = albumTitle ?: "", cover = albumCover) },
    audioQuality = audioQuality
)

private fun PlaylistTrackEntity.toDomain() = Track(
    id = trackId,
    title = title,
    duration = duration,
    artist = Artist(id = 0, name = artistName),
    album = albumId?.let { Album(id = it, title = albumTitle ?: "", cover = albumCover) }
)

private fun Track.toFavoriteEntity() = FavoriteTrackEntity(
    id = id,
    title = title,
    duration = duration,
    artistId = artist?.id,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover,
    audioQuality = audioQuality,
    explicit = explicit,
    trackNumber = trackNumber
)

private fun Album.toFavoriteEntity() = FavoriteAlbumEntity(
    id = id,
    title = title,
    artistId = artist?.id,
    artistName = displayArtist,
    cover = cover,
    numberOfTracks = numberOfTracks,
    releaseDate = releaseDate,
    type = type
)

private fun Artist.toFavoriteEntity() = FavoriteArtistEntity(
    id = id,
    name = name,
    picture = picture
)

private fun Track.toHistoryEntity() = HistoryTrackEntity(
    id = id,
    title = title,
    duration = duration,
    artistId = artist?.id,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover,
    audioQuality = audioQuality
)

private fun Track.toPlayEventEntity() = PlayEventEntity(
    trackId = id,
    title = title,
    duration = duration,
    artistId = artist?.id,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover,
    audioQuality = audioQuality,
    source = null,
    playedAt = System.currentTimeMillis()
)

private fun Track.toPlaylistTrackEntity(playlistId: String) = PlaylistTrackEntity(
    playlistId = playlistId,
    trackId = id,
    title = title,
    duration = duration,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover
)
