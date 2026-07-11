package tf.monochrome.android.data.local.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tf.monochrome.android.data.local.db.LocalAlbumEntity
import tf.monochrome.android.data.local.db.LocalArtistEntity
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.db.LocalGenreEntity
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.db.ScanStateEntity
import tf.monochrome.android.data.local.scanner.MediaScanner
import tf.monochrome.android.data.local.scanner.ScanProgress
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.TrackLyrics
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaRepository @Inject constructor(
    private val localMediaDao: LocalMediaDao,
    private val mediaScanner: MediaScanner
) {

    // ── Scanning ────────────────────────────────────────────────────

    fun fullScan(
        minDurationMs: Long = 30_000,
        excludedPaths: Set<String> = emptySet()
    ): Flow<ScanProgress> = mediaScanner.fullScan(minDurationMs, excludedPaths)

    fun incrementalScan(
        minDurationMs: Long = 30_000
    ): Flow<ScanProgress> = mediaScanner.incrementalScan(minDurationMs)

    // ── Tracks ──────────────────────────────────────────────────────

    fun getAllTracks(): Flow<List<UnifiedTrack>> =
        localMediaDao.getAllTracks().map { tracks -> tracks.map { it.toUnifiedTrack() } }

    fun searchTracks(query: String): Flow<List<UnifiedTrack>> =
        localMediaDao.searchTracks("%$query%").map { tracks -> tracks.map { it.toUnifiedTrack() } }

    fun getTracksByAlbum(albumId: Long): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksByAlbum(albumId).map { tracks -> tracks.map { it.toUnifiedTrack() } }

    fun getTracksByArtist(artistId: Long): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksByArtist(artistId).map { tracks -> tracks.map { it.toUnifiedTrack() } }

    fun getTracksByGenre(genre: String): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksByGenre(genre).map { tracks -> tracks.map { it.toUnifiedTrack() } }

    fun getTracksInFolder(folderPath: String): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksInFolder(folderPath).map { tracks -> tracks.map { it.toUnifiedTrack() } }

    suspend fun findByIsrc(isrc: String): UnifiedTrack? =
        localMediaDao.findByIsrc(isrc)?.toUnifiedTrack()

    // ── Albums ──────────────────────────────────────────────────────

    fun getAllAlbums(): Flow<List<UnifiedAlbum>> =
        localMediaDao.getAllAlbums().map { albums -> albums.map { it.toUnifiedAlbum() } }

    fun getAlbumsByArtist(artistName: String): Flow<List<UnifiedAlbum>> =
        localMediaDao.getAlbumsByArtist(artistName).map { albums -> albums.map { it.toUnifiedAlbum() } }

    suspend fun getAlbumById(albumId: Long): UnifiedAlbum? =
        localMediaDao.getAlbumById(albumId)?.toUnifiedAlbum()

    // ── Artists ─────────────────────────────────────────────────────

    fun getAllArtists(): Flow<List<UnifiedArtist>> =
        localMediaDao.getAllArtists().map { artists -> artists.map { it.toUnifiedArtist() } }

    suspend fun getArtistById(artistId: Long): UnifiedArtist? =
        localMediaDao.getArtistById(artistId)?.toUnifiedArtist()

    // ── Genres ──────────────────────────────────────────────────────

    fun getAllGenres(): Flow<List<LocalGenreEntity>> = localMediaDao.getAllGenres()

    // ── Folders ─────────────────────────────────────────────────────

    fun getRootFolders(): Flow<List<LocalFolderEntity>> = localMediaDao.getRootFolders()

    fun getSubfolders(parentPath: String): Flow<List<LocalFolderEntity>> =
        localMediaDao.getSubfolders(parentPath)

    // ── Scan State ──────────────────────────────────────────────────

    suspend fun getScanState(): ScanStateEntity? = localMediaDao.getScanState()

    suspend fun getTrackCount(): Int = localMediaDao.getTrackCount()

    // ── Conversions ─────────────────────────────────────────────────

    companion object {
        fun LocalTrackEntity.toUnifiedTrack(): UnifiedTrack {
            val codec = try { AudioCodec.valueOf(codec) } catch (_: Exception) { AudioCodec.UNKNOWN }
            return UnifiedTrack(
                id = "local_$id",
                title = displayTitle,
                durationSeconds = durationSeconds,
                trackNumber = trackNumber,
                discNumber = discNumber,
                artistName = albumArtist ?: artist ?: "Unknown Artist",
                artistNames = listOfNotNull(artist, albumArtist).distinct(),
                albumArtistName = albumArtist,
                // Local artist id (local_artists table) so song rows can link to
                // LocalArtistDetail. Routing branches on sourceType == LOCAL.
                artistId = artistId,
                artists = artistId?.let { aid ->
                    (artist ?: albumArtist)?.takeIf { it.isNotBlank() }
                        ?.let { listOf(UnifiedArtistRef(id = aid, name = it)) }
                } ?: emptyList(),
                albumTitle = album,
                albumId = albumId?.let { "local_album_$it" },
                // Fall back to the audio file's own file:// URI when the scan
                // didn't cache a JPG (or it was evicted). The registered
                // AudioFileCoverFetcher extracts the embedded picture on demand,
                // so song rows show the cover even on cache misses — matching
                // what toLegacyTrack() already does for the player.
                artworkUri = artworkCacheKey ?: android.net.Uri.fromFile(File(filePath)).toString(),
                codec = codec,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                bitRate = bitRate,
                channelCount = channels,
                isThxSpatialAudio = isThxSpatialAudio,
                replayGainTrack = rgTrackGain,
                replayGainAlbum = rgAlbumGain,
                r128TrackGain = r128TrackGain,
                r128AlbumGain = r128AlbumGain,
                lyrics = lyrics?.let { TrackLyrics(basic = it) },
                isrc = isrc,
                musicBrainzTrackId = musicbrainzTrack,
                source = PlaybackSource.LocalFile(
                    filePath = filePath,
                    codec = codec,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth
                ),
                sourceType = SourceType.LOCAL,
                dateModified = lastModified
            )
        }

        fun LocalAlbumEntity.toUnifiedAlbum(): UnifiedAlbum = UnifiedAlbum(
            id = "local_album_$id",
            title = title,
            artistName = artist,
            year = year,
            trackCount = trackCount,
            totalDuration = totalDuration,
            artworkUri = artworkCacheKey,
            genres = listOfNotNull(genre),
            sourceType = SourceType.LOCAL,
            qualitySummary = bestQuality
        )

        fun LocalArtistEntity.toUnifiedArtist(): UnifiedArtist = UnifiedArtist(
            id = "local_artist_$id",
            name = name,
            artworkUri = artworkCacheKey,
            albumCount = albumCount,
            trackCount = trackCount,
            sourceType = SourceType.LOCAL
        )
    }
}
