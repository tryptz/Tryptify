package tf.monochrome.android.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_tracks",
    indices = [
        Index("filePath", unique = true),
        Index("album"),
        Index("artist"),
        Index("albumArtist"),
        Index("genre"),
        Index("codec"),
        Index("isrc"),
        Index("musicbrainzTrack"),
        Index("musicbrainzAlbum"),
        Index("albumId"),
        Index("artistId")
    ]
)
data class LocalTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileSizeBytes: Long,
    val lastModified: Long,

    // Tag data (nullable - files may have partial/no tags)
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = 1,
    val discTotal: Int? = null,
    val composer: String? = null,
    val comment: String? = null,
    val lyrics: String? = null,
    val isrc: String? = null,
    val musicbrainzTrack: String? = null,
    val musicbrainzAlbum: String? = null,

    // Audio properties
    val codec: String,                // "FLAC", "MP3", "AAC", etc.
    val sampleRate: Int,              // Hz
    val bitDepth: Int? = null,        // null for lossy
    val bitRate: Int,                 // kbps
    val channels: Int,
    val durationSeconds: Int,

    // Replay gain
    val rgTrackGain: Float? = null,
    val rgAlbumGain: Float? = null,
    val r128TrackGain: Int? = null,
    val r128AlbumGain: Int? = null,

    // Artwork
    val hasEmbeddedArt: Boolean = false,
    val artworkCacheKey: String? = null,

    // THX Spatial Audio release — detected from embedded tags at scan time so
    // sideloaded/re-scanned THX files light up the badge without a DB history.
    val isThxSpatialAudio: Boolean = false,

    // Grouping references (populated during scan)
    val albumId: Long? = null,
    val artistId: Long? = null,

    // Scan metadata
    val firstScannedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = System.currentTimeMillis()
) {
    /** Display title - falls back to filename without extension */
    val displayTitle: String
        get() = title ?: filePath.substringAfterLast('/').substringBeforeLast('.')
}

@Entity(
    tableName = "local_albums",
    indices = [
        Index("groupingKey", unique = true),
        Index("artist")
    ]
)
data class LocalAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val totalDuration: Int = 0,       // seconds
    val artworkCacheKey: String? = null,
    val bestQuality: String? = null,  // highest codec/sample rate
    val groupingKey: String           // normalize(title)+normalize(artist)+year
)

@Entity(
    tableName = "local_artists",
    indices = [
        Index("normalizedName", unique = true)
    ]
)
data class LocalArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
    val artworkCacheKey: String? = null
)

@Entity(tableName = "local_genres")
data class LocalGenreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trackCount: Int = 0
)

@Entity(
    tableName = "local_folders",
    indices = [Index("parentPath")]
)
data class LocalFolderEntity(
    @PrimaryKey val path: String,
    val parentPath: String? = null,
    val displayName: String,
    val trackCount: Int = 0,
    val totalDuration: Int = 0
)

@Entity(tableName = "scan_state")
data class ScanStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastFullScan: Long? = null,
    val lastIncremental: Long? = null,
    val totalTracks: Int = 0,
    val totalDuration: Int = 0,
    val totalSizeBytes: Long = 0
)
