package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.FavoriteAlbumEntity
import tf.monochrome.android.data.db.entity.FavoriteArtistEntity
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity

@Dao
interface FavoriteDao {
    // Tracks
    @Query("SELECT * FROM favorite_tracks ORDER BY addedAt DESC")
    fun getFavoriteTracks(): Flow<List<FavoriteTrackEntity>>

    @Query("SELECT * FROM favorite_tracks ORDER BY addedAt DESC")
    suspend fun getFavoriteTracksSnapshot(): List<FavoriteTrackEntity>

    @Query("SELECT COUNT(*) FROM favorite_tracks")
    fun getFavoriteTrackCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteTrack(track: FavoriteTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackIfNotExists(track: FavoriteTrackEntity)

    @Query("DELETE FROM favorite_tracks WHERE id = :trackId")
    suspend fun deleteFavoriteTrack(trackId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE id = :trackId)")
    suspend fun isFavoriteTrack(trackId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE id = :trackId)")
    fun isFavoriteTrackFlow(trackId: Long): Flow<Boolean>

    // Albums
    @Query("SELECT * FROM favorite_albums ORDER BY addedAt DESC")
    fun getFavoriteAlbums(): Flow<List<FavoriteAlbumEntity>>

    @Query("SELECT * FROM favorite_albums ORDER BY addedAt DESC")
    suspend fun getFavoriteAlbumsSnapshot(): List<FavoriteAlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteAlbum(album: FavoriteAlbumEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumIfNotExists(album: FavoriteAlbumEntity)

    @Query("DELETE FROM favorite_albums WHERE id = :albumId")
    suspend fun deleteFavoriteAlbum(albumId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_albums WHERE id = :albumId)")
    suspend fun isFavoriteAlbum(albumId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_albums WHERE id = :albumId)")
    fun isFavoriteAlbumFlow(albumId: Long): Flow<Boolean>

    // Artists
    @Query("SELECT * FROM favorite_artists ORDER BY addedAt DESC")
    fun getFavoriteArtists(): Flow<List<FavoriteArtistEntity>>

    @Query("SELECT * FROM favorite_artists ORDER BY addedAt DESC")
    suspend fun getFavoriteArtistsSnapshot(): List<FavoriteArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteArtist(artist: FavoriteArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistIfNotExists(artist: FavoriteArtistEntity)

    @Query("DELETE FROM favorite_artists WHERE id = :artistId")
    suspend fun deleteFavoriteArtist(artistId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_artists WHERE id = :artistId)")
    suspend fun isFavoriteArtist(artistId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_artists WHERE id = :artistId)")
    fun isFavoriteArtistFlow(artistId: Long): Flow<Boolean>
}
