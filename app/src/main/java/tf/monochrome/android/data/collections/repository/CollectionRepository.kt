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
import tf.monochrome.android.data.collections.crypto.AesGcmKeySealer
import tf.monochrome.android.data.collections.model.CollectionManifest
import tf.monochrome.android.data.collections.parser.ManifestParser
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.CollectionDirectLink
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.TrackLyrics
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val manifestParser: ManifestParser,
    private val json: Json,
    private val keySealer: AesGcmKeySealer,
) {

    // ── Import ──────────────────────────────────────────────────────

    suspend fun importManifest(manifestJson: String): Result<String> {
        return try {
            val manifest = manifestParser.parse(manifestJson).getOrThrow()
            val collectionId = UUID.randomUUID().toString()
            val manifestHash = sha256(manifestJson)
            val sealedCollectionKey = keySealer.seal(decodeCollectionKey(manifest.encryption.key))

            // Check if already imported
            val existing = collectionDao.getAllCollections()
            // We'll skip duplicate check for simplicity - use manifestHash

            // Insert collection
            collectionDao.insertCollection(
                CollectionEntity(
                    collectionId = collectionId,
                    authorId = manifest.authorId,
                    version = manifest.version,
                    collectionLink = manifest.collectionLink,
                    projectLink = manifest.projectLink,
                    encryptionType = manifest.encryption.type,
                    encryptionKey = sealedCollectionKey,
                    manifestHash = manifestHash
                )
            )

            // Insert artists
            collectionDao.insertArtists(manifest.artists.map { artist ->
                CollectionArtistEntity(
                    uuid = artist.uuid,
                    collectionId = collectionId,
                    tidalId = artist.tidalId,
                    isni = artist.isni,
                    name = artist.name,
                    bio = artist.bio,
                    genresJson = json.encodeToString(artist.genres),
                    imagesJson = json.encodeToString(artist.images),
                    socialsJson = json.encodeToString(artist.socials)
                )
            })

            // Insert albums + album-artist crossrefs
            collectionDao.insertAlbums(manifest.albums.map { album ->
                CollectionAlbumEntity(
                    uuid = album.uuid,
                    collectionId = collectionId,
                    tidalId = album.tidalId,
                    upc = album.upc,
                    title = album.title,
                    description = album.description,
                    releaseDate = album.releaseDate,
                    numberOfTracks = album.numberOfTracks,
                    isSingle = album.isSingle,
                    type = album.type,
                    explicit = album.explicit,
                    label = album.label,
                    copyright = album.copyright,
                    imagesJson = json.encodeToString(album.images),
                    genresJson = json.encodeToString(album.genres),
                    qualityTagsJson = json.encodeToString(album.qualityTags)
                )
            })
            collectionDao.insertAlbumArtistCrossRefs(
                manifest.albums.flatMap { album ->
                    album.artistUuids.map { artistUuid ->
                        CollectionAlbumArtistCrossRef(albumUuid = album.uuid, artistUuid = artistUuid)
                    }
                }
            )

            // Insert tracks + direct links + track-artist crossrefs
            collectionDao.insertTracks(manifest.tracks.map { track ->
                CollectionTrackEntity(
                    uuid = track.uuid,
                    collectionId = collectionId,
                    albumUuid = track.albumUuid,
                    tidalId = track.tidalId,
                    isrc = track.isrc,
                    title = track.title,
                    releaseDate = track.releaseDate,
                    durationSeconds = track.durationSeconds,
                    trackNumber = track.trackNumber,
                    volumeNumber = track.volumeNumber,
                    version = track.version,
                    explicit = track.explicit,
                    replayGain = track.replayGain,
                    qualityTagsJson = json.encodeToString(track.qualityTags),
                    cover = track.cover,
                    fileHash = track.fileHash,
                    basicLyrics = track.basicLyrics,
                    lrcLyrics = track.lrcLyrics,
                    ttmlLyrics = track.ttmlLyrics
                )
            })

            collectionDao.insertDirectLinks(
                manifest.tracks.flatMap { track ->
                    track.directLinks.map { link ->
                        CollectionDirectLinkEntity(
                            trackUuid = track.uuid,
                            url = link.url,
                            quality = link.quality
                        )
                    }
                }
            )

            collectionDao.insertTrackArtistCrossRefs(
                manifest.tracks.flatMap { track ->
                    track.artistUuids.map { artistUuid ->
                        CollectionTrackArtistCrossRef(trackUuid = track.uuid, artistUuid = artistUuid)
                    }
                }
            )

            Result.success(collectionId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Delete ───────────────────────────────────────────────────────

    suspend fun deleteCollection(collectionId: String) {
        collectionDao.deleteCollectionCascade(collectionId)
    }

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

    private fun decodeCollectionKey(encodedKey: String): ByteArray =
        Base64.getMimeDecoder().decode(encodedKey)

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
