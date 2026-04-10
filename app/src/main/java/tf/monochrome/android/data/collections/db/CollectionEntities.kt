package tf.monochrome.android.data.collections.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val collectionId: String,
    val authorId: String,
    val version: String,
    val collectionLink: String? = null,
    val projectLink: String? = null,
    val encryptionType: String,
    val encryptionKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val importedAt: Long = System.currentTimeMillis(),
    val manifestHash: String = ""
)

@Entity(
    tableName = "collection_artists",
    indices = [Index("collectionId")]
)
data class CollectionArtistEntity(
    @PrimaryKey val uuid: String,
    val collectionId: String,
    val tidalId: String? = null,
    val isni: String? = null,
    val name: String,
    val bio: String? = null,
    val genresJson: String = "[]",
    val imagesJson: String = "[]",
    val socialsJson: String = "{}"
)

@Entity(
    tableName = "collection_albums",
    indices = [Index("collectionId")]
)
data class CollectionAlbumEntity(
    @PrimaryKey val uuid: String,
    val collectionId: String,
    val tidalId: String? = null,
    val upc: String? = null,
    val title: String,
    val description: String? = null,
    val releaseDate: String? = null,
    val numberOfTracks: Int? = null,
    val isSingle: Boolean = false,
    val type: String? = null,
    val explicit: Boolean = false,
    val label: String? = null,
    val copyright: String? = null,
    val imagesJson: String = "[]",
    val genresJson: String = "[]",
    val qualityTagsJson: String = "[]"
)

@Entity(
    tableName = "collection_tracks",
    indices = [
        Index("collectionId"),
        Index("albumUuid"),
        Index("isrc"),
        Index("tidalId"),
        Index("title")
    ]
)
data class CollectionTrackEntity(
    @PrimaryKey val uuid: String,
    val collectionId: String,
    val albumUuid: String,
    val tidalId: String? = null,
    val isrc: String? = null,
    val title: String,
    val releaseDate: String? = null,
    val durationSeconds: Int,
    val trackNumber: Int = 1,
    val volumeNumber: Int = 1,
    val version: String? = null,
    val explicit: Boolean = false,
    val replayGain: Float? = null,
    val qualityTagsJson: String = "[]",
    val cover: String? = null,
    val fileHash: String? = null,
    val basicLyrics: String? = null,
    val lrcLyrics: String? = null,
    val ttmlLyrics: String? = null
)

@Entity(
    tableName = "collection_direct_links",
    indices = [Index("trackUuid")],
    foreignKeys = [
        ForeignKey(
            entity = CollectionTrackEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["trackUuid"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CollectionDirectLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackUuid: String,
    val url: String,
    val quality: String
)

@Entity(
    tableName = "collection_track_artists",
    primaryKeys = ["trackUuid", "artistUuid"]
)
data class CollectionTrackArtistCrossRef(
    val trackUuid: String,
    val artistUuid: String
)

@Entity(
    tableName = "collection_album_artists",
    primaryKeys = ["albumUuid", "artistUuid"]
)
data class CollectionAlbumArtistCrossRef(
    val albumUuid: String,
    val artistUuid: String
)
