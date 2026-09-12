package tf.monochrome.android.data.local.repository

import kotlinx.coroutines.Dispatchers
import tf.monochrome.android.ui.library.LibrarySortKey
import tf.monochrome.android.ui.library.LibrarySort
import kotlinx.coroutines.withContext
import androidx.paging.map
import androidx.paging.PagingData
import androidx.paging.PagingConfig
import androidx.paging.Pager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
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
            .flowOn(Dispatchers.Default)

    /** How many local tracks there are, without building a single one of them. */
    fun countTracks(): Flow<Int> = localMediaDao.countTracks()

    /**
     * The songs list as pages, in [sort] order.
     *
     * [getAllTracks] above materialises the whole library on every emission —
     * measured at ~118 ms and 20,000 objects for a 20,000-track library, all
     * of it to draw the dozen rows that fit on a screen. This loads a window
     * instead. The ordering moved into SQL with it, because there is no longer
     * a full list in memory to sort.
     */
    fun pagedTracks(sort: LibrarySort): Flow<PagingData<UnifiedTrack>> =
        Pager(
            // A page is comfortably more than a screenful, so scrolling at a
            // normal speed never waits on a query; the placeholder-free config
            // means the list length grows as pages land rather than starting
            // at the full count with blank rows.
            // Placeholders ON so the list reports its real length from the
            // first frame: the fast scroller's thumb is sized and positioned
            // from totalItemsCount, and without them that is only the rows
            // loaded so far, so the thumb would shrink and the drag crawl.
            // Unloaded rows come back null and the list draws an empty row of
            // the right height until the page lands.
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 30,
                enablePlaceholders = true,
                // Without a jump threshold, dragging the scrollbar to row
                // 15,000 makes Paging walk there — loading, and keeping, every
                // page in between. Past this many rows skipped it throws the
                // loaded pages away and reloads around where the finger landed,
                // which is one query instead of two hundred. Room's
                // LimitOffsetPagingSource supports it; a source that does not
                // simply ignores the value.
                jumpThreshold = 180,
            ),
            pagingSourceFactory = { pagingSourceFor(sort) },
        ).flow.map { page -> page.map { it.toUnifiedTrack() } }

    /**
     * The whole library in [sort] order, for the moment a play queue is built.
     *
     * Tapping a row queues everything after it, and a paged list cannot answer
     * that — it only holds what is near the screen. So the cost is paid on tap,
     * off the main thread, rather than by holding the library in memory for the
     * whole session in case somebody presses play.
     */
    suspend fun tracksForQueue(sort: LibrarySort): List<UnifiedTrack> =
        withContext(Dispatchers.Default) {
            snapshotFor(sort).map { it.toUnifiedTrack() }
        }

    // The two mappings from a sort selection to a query. Kept side by side so
    // a new sort key cannot be added to one and forgotten in the other, which
    // would show the list in one order and play it in another.
    private fun pagingSourceFor(sort: LibrarySort) = when (sort.key) {
        LibrarySortKey.DATE ->
            if (sort.ascending) localMediaDao.pagedByDateAsc() else localMediaDao.pagedByDateDesc()
        LibrarySortKey.FILE_TYPE ->
            if (sort.ascending) localMediaDao.pagedByFileTypeAsc() else localMediaDao.pagedByFileTypeDesc()
        LibrarySortKey.TIME ->
            if (sort.ascending) localMediaDao.pagedByTimeAsc() else localMediaDao.pagedByTimeDesc()
        else ->
            if (sort.ascending) localMediaDao.pagedByNameAsc() else localMediaDao.pagedByNameDesc()
    }

    private suspend fun snapshotFor(sort: LibrarySort) = when (sort.key) {
        LibrarySortKey.DATE ->
            if (sort.ascending) localMediaDao.snapshotByDateAsc() else localMediaDao.snapshotByDateDesc()
        LibrarySortKey.FILE_TYPE ->
            if (sort.ascending) localMediaDao.snapshotByFileTypeAsc() else localMediaDao.snapshotByFileTypeDesc()
        LibrarySortKey.TIME ->
            if (sort.ascending) localMediaDao.snapshotByTimeAsc() else localMediaDao.snapshotByTimeDesc()
        else ->
            if (sort.ascending) localMediaDao.snapshotByNameAsc() else localMediaDao.snapshotByNameDesc()
    }

    fun searchTracks(query: String): Flow<List<UnifiedTrack>> =
        localMediaDao.searchTracks("%$query%").map { tracks -> tracks.map { it.toUnifiedTrack() } }
            .flowOn(Dispatchers.Default)

    fun getTracksByAlbum(albumId: Long): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksByAlbum(albumId).map { tracks -> tracks.map { it.toUnifiedTrack() } }
            .flowOn(Dispatchers.Default)

    fun getTracksByArtist(artistId: Long): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksByArtist(artistId).map { tracks -> tracks.map { it.toUnifiedTrack() } }
            .flowOn(Dispatchers.Default)

    fun getTracksByGenre(genre: String): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksByGenre(genre).map { tracks -> tracks.map { it.toUnifiedTrack() } }
            .flowOn(Dispatchers.Default)

    fun getTracksInFolder(folderPath: String): Flow<List<UnifiedTrack>> =
        localMediaDao.getTracksInFolder(folderPath).map { tracks -> tracks.map { it.toUnifiedTrack() } }
            .flowOn(Dispatchers.Default)

    suspend fun findByIsrc(isrc: String): UnifiedTrack? =
        localMediaDao.findByIsrc(isrc)?.toUnifiedTrack()

    suspend fun findByMusicBrainzId(mbId: String): UnifiedTrack? =
        localMediaDao.findByMusicBrainzId(mbId)?.toUnifiedTrack()

    /** Title-prefix shortlist for cross-source matching — see [LocalMediaDao.findByTitlePattern]. */
    suspend fun findByTitlePattern(titlePattern: String): List<UnifiedTrack> =
        localMediaDao.findByTitlePattern(titlePattern).map { it.toUnifiedTrack() }

    /** Indexed title-prefix shortlist — see [LocalMediaDao.findByTitlePrefix]. */
    suspend fun findByTitlePrefix(start: String, endExclusive: String): List<UnifiedTrack> =
        localMediaDao.findByTitlePrefix(start, endExclusive).map { it.toUnifiedTrack() }

    // ── Albums ──────────────────────────────────────────────────────

    fun getAllAlbums(): Flow<List<UnifiedAlbum>> =
        localMediaDao.getAllAlbums().map { albums -> albums.map { it.toUnifiedAlbum() } }
            .flowOn(Dispatchers.Default)

    fun getAlbumsByArtist(artistName: String): Flow<List<UnifiedAlbum>> =
        localMediaDao.getAlbumsByArtist(artistName).map { albums -> albums.map { it.toUnifiedAlbum() } }
            .flowOn(Dispatchers.Default)

    suspend fun getAlbumById(albumId: Long): UnifiedAlbum? =
        localMediaDao.getAlbumById(albumId)?.toUnifiedAlbum()

    // ── Artists ─────────────────────────────────────────────────────

    fun getAllArtists(): Flow<List<UnifiedArtist>> =
        localMediaDao.getAllArtists().map { artists -> artists.map { it.toUnifiedArtist() } }
            .flowOn(Dispatchers.Default)

    suspend fun getArtistById(artistId: Long): UnifiedArtist? =
        localMediaDao.getArtistById(artistId)?.toUnifiedArtist()

    // ── Genres ──────────────────────────────────────────────────────

    fun getAllGenres(): Flow<List<LocalGenreEntity>> = localMediaDao.getAllGenres()

    // ── Folders ─────────────────────────────────────────────────────

    fun getRootFolders(): Flow<List<LocalFolderEntity>> = localMediaDao.getRootFolders()

    /** Every folder row, for working out where the Folders tab should open. */
    fun getAllFolders(): Flow<List<LocalFolderEntity>> = localMediaDao.getAllFolders()

    fun getSubfolders(parentPath: String): Flow<List<LocalFolderEntity>> =
        localMediaDao.getSubfolders(parentPath)

    // ── Scan State ──────────────────────────────────────────────────

    suspend fun getScanState(): ScanStateEntity? = localMediaDao.getScanState()

    suspend fun getTrackCount(): Int = localMediaDao.getTrackCount()

    // ── Conversions ─────────────────────────────────────────────────

    companion object {
        /**
         * Codec name → enum, without the exception.
         *
         * This used to be `try { AudioCodec.valueOf(codec) } catch { UNKNOWN }`,
         * and `valueOf` signals a miss by *throwing* — so every row whose codec
         * string wasn't an exact enum name built a JVM exception and captured a
         * stack trace. Per row, on every emission of a whole-library flow. One
         * odd file was enough to make listing the library allocate thousands of
         * throwaway stack traces.
         */
        private val CODECS_BY_NAME: Map<String, AudioCodec> =
            AudioCodec.entries.associateBy { it.name }

        fun LocalTrackEntity.toUnifiedTrack(): UnifiedTrack {
            val codec = CODECS_BY_NAME[codec] ?: AudioCodec.UNKNOWN
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
