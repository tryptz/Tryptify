package tf.monochrome.android.data.collections.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.collections.db.CollectionAlbumArtistCrossRef
import tf.monochrome.android.data.collections.db.CollectionAlbumEntity
import tf.monochrome.android.data.collections.db.CollectionArtistEntity
import tf.monochrome.android.data.collections.db.CollectionDao
import tf.monochrome.android.data.collections.db.CollectionDirectLinkEntity
import tf.monochrome.android.data.collections.db.CollectionEntity
import tf.monochrome.android.data.collections.db.CollectionTrackArtistCrossRef
import tf.monochrome.android.data.collections.db.CollectionTrackEntity
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.CollectionDirectLink
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.TrackLyrics
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val json: Json
) {

    // ── Queries ──────────────────────────────────────────────────────

    fun getAllCollections(): Flow<List<CollectionEntity>> =
        collectionDao.getAllCollections()

    fun getTracksByCollection(collectionId: String): Flow<List<UnifiedTrack>> =
        collectionDao.getTracksByCollection(collectionId).map { tracks ->
            tracks.map { it.toUnifiedTrack(collectionId) }
        }

    fun getAlbumsByCollection(collectionId: String): Flow<List<UnifiedAlbum>> =
        collectionDao.getAlbumsByCollection(collectionId).map { albums ->
            albums.map { it.toUnifiedAlbum() }
        }

    fun getArtistsByCollection(collectionId: String): Flow<List<UnifiedArtist>> =
        collectionDao.getArtistsByCollection(collectionId).map { artists ->
            artists.map { it.toUnifiedArtist() }
        }

    fun searchTracks(query: String): Flow<List<UnifiedTrack>> =
        collectionDao.searchTracks("%$query%").map { tracks ->
            tracks.map { it.toUnifiedTrack(it.collectionId) }
        }

    suspend fun findTrackByIsrc(isrc: String): UnifiedTrack? =
        collectionDao.findTrackByIsrc(isrc)?.toUnifiedTrack(null)

    suspend fun getDirectLinksForTrack(trackUuid: String): List<CollectionDirectLinkEntity> =
        collectionDao.getDirectLinks(trackUuid)

    suspend fun getEncryptionKey(collectionId: String): String? =
        collectionDao.getCollection(collectionId)?.encryptionKey

    // ── Conversions ─────────────────────────────────────────────────

    private suspend fun CollectionTrackEntity.toUnifiedTrack(fallbackCollectionId: String?): UnifiedTrack {
        val cId = fallbackCollectionId ?: collectionId
        val artists = collectionDao.getArtistsForTrack(uuid)
        val album = collectionDao.getAlbum(albumUuid)
        val directLinks = collectionDao.getDirectLinks(uuid)
        val collection = collectionDao.getCollection(cId)
        val qualityTags: List<String> = try {
            json.decodeFromString(qualityTagsJson)
        } catch (_: Exception) { emptyList() }

        return UnifiedTrack(
            id = "col_$uuid",
            title = title,
            durationSeconds = durationSeconds,
            trackNumber = trackNumber,
            discNumber = volumeNumber,
            explicit = explicit,
            artistName = artists.firstOrNull()?.name ?: "Unknown Artist",
            artistNames = artists.map { it.name },
            albumTitle = album?.title,
            albumId = albumUuid.let { "col_album_$it" },
            artworkUri = cover ?: album?.let {
                try {
                    val images: List<tf.monochrome.android.data.collections.model.ManifestImage> =
                        json.decodeFromString(it.imagesJson)
                    images.firstOrNull()?.url
                } catch (_: Exception) { null }
            },
            qualityTags = qualityTags,
            replayGainTrack = replayGain,
            lyrics = if (basicLyrics != null || lrcLyrics != null || ttmlLyrics != null) {
                TrackLyrics(basic = basicLyrics, lrc = lrcLyrics, ttml = ttmlLyrics)
            } else null,
            isrc = isrc,
            source = PlaybackSource.CollectionDirect(
                collectionId = cId,
                directLinks = directLinks.map { CollectionDirectLink(url = it.url, quality = it.quality) },
                encryptionKey = collection?.encryptionKey ?: "",
                fileHash = fileHash ?: ""
            ),
            sourceType = SourceType.COLLECTION
        )
    }

    private fun CollectionAlbumEntity.toUnifiedAlbum(): UnifiedAlbum {
        val genres: List<String> = try {
            json.decodeFromString(genresJson)
        } catch (_: Exception) { emptyList() }
        val qualityTags: List<String> = try {
            json.decodeFromString(qualityTagsJson)
        } catch (_: Exception) { emptyList() }
        val images: List<tf.monochrome.android.data.collections.model.ManifestImage> = try {
            json.decodeFromString(imagesJson)
        } catch (_: Exception) { emptyList() }

        return UnifiedAlbum(
            id = "col_album_$uuid",
            title = title,
            artistName = label ?: "",
            year = releaseDate?.take(4)?.toIntOrNull(),
            trackCount = numberOfTracks ?: 0,
            artworkUri = images.firstOrNull()?.url,
            genres = genres,
            sourceType = SourceType.COLLECTION,
            qualitySummary = qualityTags.firstOrNull()
        )
    }

    private fun CollectionArtistEntity.toUnifiedArtist(): UnifiedArtist {
        val genres: List<String> = try {
            json.decodeFromString(genresJson)
        } catch (_: Exception) { emptyList() }
        val images: List<tf.monochrome.android.data.collections.model.ManifestImage> = try {
            json.decodeFromString(imagesJson)
        } catch (_: Exception) { emptyList() }

        return UnifiedArtist(
            id = "col_artist_$uuid",
            name = name,
            artworkUri = images.firstOrNull()?.url,
            bio = bio,
            genres = genres,
            sourceType = SourceType.COLLECTION
        )
    }
}
