package tf.monochrome.android.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "favorite_tracks")
data class FavoriteTrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val duration: Int = 0,
    val artistId: Long? = null,
    val artistName: String = "",
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val audioQuality: String? = null,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "favorite_albums")
data class FavoriteAlbumEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artistId: Long? = null,
    val artistName: String = "",
    val cover: String? = null,
    val numberOfTracks: Int? = null,
    val releaseDate: String? = null,
    val type: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "favorite_artists")
data class FavoriteArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val picture: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "history_tracks",
    indices = [Index("playedAt")]
)
data class HistoryTrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val duration: Int = 0,
    val artistId: Long? = null,
    val artistName: String = "",
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val audioQuality: String? = null,
    val playedAt: Long = System.currentTimeMillis()
)

/**
 * Per-play scrobble log. Unlike [HistoryTrackEntity] (which holds one row per track),
 * this table records one row per playback so we can compute listening statistics over time.
 */
@Entity(
    tableName = "play_events",
    indices = [Index("trackId"), Index("playedAt")]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val trackId: Long,
    val title: String,
    val duration: Int = 0,
    val artistId: Long? = null,
    val artistName: String = "",
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val audioQuality: String? = null,
    val source: String? = null,
    val playedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "user_playlists")
data class UserPlaylistEntity(
    @PrimaryKey val id: String, // UUID
    val name: String,
    val description: String? = null,
    val coverTrackId: Long? = null,
    val isPublic: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = UserPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("trackId")]
)
@Serializable
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: Long,
    val title: String,
    val duration: Int = 0,
    val artistName: String = "",
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloaded_tracks",
    indices = [Index("filePath")]
)
@Serializable
data class DownloadedTrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val duration: Int = 0,
    val artistName: String = "",
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val filePath: String,
    val quality: String,
    val sizeBytes: Long = 0,
    val downloadedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cached_lyrics",
    indices = [Index("cachedAt")]
)
@Serializable
data class CachedLyricsEntity(
    @PrimaryKey val trackId: Long,
    val lyricsJson: String,
    val isSynced: Boolean = false,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "eq_presets",
    indices = [Index("id"), Index("isCustom")]
)
@Serializable
data class EqPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val bandsJson: String = "[]",  // Serialized List<EqBand>
    val preamp: Float = 0f,
    val targetId: String = "",
    val targetName: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
