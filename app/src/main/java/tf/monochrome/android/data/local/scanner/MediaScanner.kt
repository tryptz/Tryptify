package tf.monochrome.android.data.local.scanner

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import tf.monochrome.android.data.local.db.LocalAlbumEntity
import tf.monochrome.android.data.local.db.LocalArtistEntity
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.db.LocalGenreEntity
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.db.ScanStateEntity
import tf.monochrome.android.data.local.db.TrackScanInfo
import tf.monochrome.android.data.local.tags.TagReader
import tf.monochrome.android.data.preferences.PreferencesManager
import java.io.File
import java.text.Normalizer
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

sealed class ScanProgress {
    data class Started(val totalFiles: Int) : ScanProgress()
    data class Processing(val current: Int, val total: Int, val currentFile: String = "") : ScanProgress()
    data class Grouping(val message: String = "Building library...") : ScanProgress()
    data class Complete(val scanned: Int, val added: Int, val removed: Int) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}

@Singleton
class MediaScanner @Inject constructor(
    private val mediaStoreSource: MediaStoreSource,
    private val tagReader: TagReader,
    private val localMediaDao: LocalMediaDao,
    private val musicDatabase: tf.monochrome.android.data.db.MusicDatabase,
    private val preferences: PreferencesManager
) {

    fun fullScan(
        minDurationMs: Long = 30_000,
        excludedPaths: Set<String> = emptySet()
    ): Flow<ScanProgress> = flow {
        try {
            // User-chosen library roots restrict every MediaStore query in
            // this scan (discovery AND pruning, so tracks outside the roots
            // are removed consistently). Empty set = whole-device scan —
            // the behavior for users who never picked folders in onboarding.
            val folderRoots = preferences.userFolderRoots.first()
            val mediaStoreFiles =
                mediaStoreSource.queryAllAudio(minDurationMs, excludedPaths, folderRoots)
            emit(ScanProgress.Started(totalFiles = mediaStoreFiles.size))

            // One projection query up front instead of a findByPath() SELECT
            // per file — the re-read decision then runs entirely in memory.
            val scanInfoByPath = localMediaDao.getAllTrackScanInfo().associateBy { it.filePath }

            val addedCount = processFiles(
                files = mediaStoreFiles,
                shouldRead = { needsReRead(scanInfoByPath[it.absolutePath], it.dateModified) },
                progressChunkSize = 50
            )

            // Prune deleted files
            emit(ScanProgress.Grouping("Removing deleted tracks..."))
            val removedCount = pruneDeleted(mediaStoreFiles.mapTo(HashSet()) { it.absolutePath })

            // Rebuild groupings
            emit(ScanProgress.Grouping("Building album & artist library..."))
            rebuildGroupings()

            // Build folder structure
            emit(ScanProgress.Grouping("Building folder structure..."))
            rebuildFolders()

            // Update scan state
            updateScanState()

            emit(ScanProgress.Complete(
                scanned = mediaStoreFiles.size,
                added = addedCount,
                removed = maxOf(0, removedCount)
            ))
        } catch (e: Exception) {
            emit(ScanProgress.Error(e.message ?: "Unknown scan error"))
        }
    }.flowOn(Dispatchers.IO)

    fun incrementalScan(
        minDurationMs: Long = 30_000
    ): Flow<ScanProgress> = flow {
        try {
            val folderRoots = preferences.userFolderRoots.first()
            val scanState = localMediaDao.getScanState()
            val lastScan = scanState?.lastIncremental ?: scanState?.lastFullScan ?: 0

            val modifiedFiles =
                mediaStoreSource.queryModifiedSince(lastScan, minDurationMs, folderRoots)
            if (modifiedFiles.isEmpty()) {
                // No new tag content to read, but still rebuild groupings so
                // album-cover-into-track propagation runs and the UI picks up
                // any album-level artwork changes since the last scan.
                emit(ScanProgress.Grouping("Refreshing library..."))
                rebuildGroupings()
                rebuildFolders()
                updateScanState()
                emit(ScanProgress.Complete(scanned = 0, added = 0, removed = 0))
                return@flow
            }

            emit(ScanProgress.Started(totalFiles = modifiedFiles.size))

            // MediaStore already filtered to files modified since the last
            // scan, so every one of them needs a fresh tag read.
            val addedCount = processFiles(
                files = modifiedFiles,
                shouldRead = { true },
                progressChunkSize = 20
            )

            // Check for deleted files. Same roots filter as fullScan so the
            // prune diff never mass-deletes tracks a full scan would keep.
            val allMediaStorePaths = mediaStoreSource
                .queryAllAudio(minDurationMs, folderRoots = folderRoots)
                .mapTo(HashSet()) { it.absolutePath }
            pruneDeleted(allMediaStorePaths)

            rebuildGroupings()
            rebuildFolders()
            updateScanState()

            emit(ScanProgress.Complete(scanned = modifiedFiles.size, added = addedCount, removed = 0))
        } catch (e: Exception) {
            emit(ScanProgress.Error(e.message ?: "Incremental scan error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Read tags for [files] with bounded parallelism and batch the resulting
     * rows into the database, emitting [ScanProgress.Processing] once per
     * [progressChunkSize] files (matching the old per-file loop's cadence).
     *
     * Tag extraction dominates scan time — each file costs a native
     * MediaMetadataRetriever.setDataSource + metadata + artwork read — and
     * the files are independent, so fan the reads out across cores. DB
     * writes stay on the collector coroutine: one insertTracks() per
     * [INSERT_BATCH_SIZE] rows instead of one transaction per file.
     *
     * Written as a FlowCollector extension so it can emit() from inside the
     * callers' flow {} builders; emissions happen only between chunks, on
     * the collector's own coroutine, because flow {} emit is not
     * thread-safe from concurrent children.
     *
     * @return the number of tracks actually (re-)read and written.
     */
    private suspend fun FlowCollector<ScanProgress>.processFiles(
        files: List<AudioFileInfo>,
        shouldRead: (AudioFileInfo) -> Boolean,
        progressChunkSize: Int
    ): Int {
        val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val tagDispatcher = Dispatchers.IO.limitedParallelism(parallelism)
        // Memoize sidecar cover art lookups by parent directory so we don't
        // listFiles() once per track. A 500-track album → 1 directory scan.
        // synchronizedMap (not ConcurrentHashMap) because "no art in this
        // folder" is cached as a null value; a lost race just re-lists the
        // same folder once with an identical result.
        val folderArtCache = Collections.synchronizedMap(HashMap<String, String?>())

        val pending = ArrayList<LocalTrackEntity>(INSERT_BATCH_SIZE)
        var added = 0
        var processed = 0

        for (chunk in files.chunked(progressChunkSize)) {
            val entities = coroutineScope {
                chunk.map { audioFile ->
                    async(tagDispatcher) {
                        try {
                            if (!shouldRead(audioFile)) return@async null
                            val tags = tagReader.readTags(audioFile.absolutePath, folderArtCache)
                            buildTrackEntity(audioFile, tags)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Skip individual file errors
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            pending += entities
            added += entities.size
            if (pending.size >= INSERT_BATCH_SIZE) {
                localMediaDao.insertTracks(pending.toList())
                pending.clear()
            }

            processed += chunk.size
            emit(ScanProgress.Processing(
                current = processed,
                total = files.size,
                currentFile = chunk.last().displayName
            ))
        }
        if (pending.isNotEmpty()) {
            localMediaDao.insertTracks(pending)
        }
        return added
    }

    /**
     * Delete DB rows whose files no longer exist in MediaStore. The diff is
     * computed in memory and deleted with positive IN-lists chunked under
     * SQLite's 999 bound-variable limit (the old NOT IN variant bound the
     * entire MediaStore path set into a single statement).
     *
     * @return the number of rows removed.
     */
    private suspend fun pruneDeleted(mediaStorePaths: Set<String>): Int {
        val toDelete = localMediaDao.getAllTrackPaths().filterNot { it in mediaStorePaths }
        if (toDelete.isNotEmpty()) {
            musicDatabase.withTransaction {
                toDelete.chunked(DELETE_CHUNK_SIZE).forEach {
                    localMediaDao.deleteTracksByPaths(it)
                }
            }
        }
        return toDelete.size
    }

    private fun buildTrackEntity(audioFile: AudioFileInfo, tags: tf.monochrome.android.data.local.tags.AudioTags): LocalTrackEntity {
        return LocalTrackEntity(
            filePath = audioFile.absolutePath,
            fileSizeBytes = audioFile.sizeBytes,
            lastModified = audioFile.dateModified,
            title = tags.title,
            artist = tags.artist,
            albumArtist = tags.albumArtist,
            album = tags.album,
            genre = tags.genre,
            year = tags.year,
            trackNumber = tags.trackNumber,
            trackTotal = tags.trackTotal,
            discNumber = tags.discNumber ?: 1,
            discTotal = tags.discTotal,
            composer = tags.composer,
            lyrics = tags.lyrics,
            codec = tags.codec.name,
            sampleRate = tags.sampleRate,
            bitDepth = tags.bitDepth,
            bitRate = tags.bitRate,
            channels = tags.channels,
            durationSeconds = tags.durationSeconds,
            rgTrackGain = tags.replayGainTrack,
            rgAlbumGain = tags.replayGainAlbum,
            hasEmbeddedArt = tags.hasEmbeddedArt,
            artworkCacheKey = tags.artworkCacheKey,
            isThxSpatialAudio = tags.isThxSpatialAudio,
            isDolbyAtmos = tags.isDolbyAtmos,
            lastScannedAt = System.currentTimeMillis()
        )
    }

    private suspend fun rebuildGroupings() {
        // Pull every track in one query (was: getAllTrackPaths() then per-path
        // findByPath, i.e. N+1 round-trips for an N-track library).
        val tracks = localMediaDao.getAllTracksSnapshot()

        // In-memory grouping — pure CPU work, no DB calls.
        val tracksByAlbumKey = HashMap<String, MutableList<LocalTrackEntity>>()
        val artistSet = HashMap<String, MutableList<LocalTrackEntity>>()
        val genreSet = HashMap<String, Int>()
        for (track in tracks) {
            val albumKey = buildAlbumGroupingKey(
                track.album,
                track.albumArtist ?: track.artist,
                track.year
            )
            tracksByAlbumKey.getOrPut(albumKey) { mutableListOf() }.add(track)

            val artistName = track.albumArtist ?: track.artist ?: "Unknown Artist"
            val normalizedArtist = normalizeText(artistName)
            artistSet.getOrPut(normalizedArtist) { mutableListOf() }.add(track)

            track.genre?.let { genre ->
                genreSet[genre] = (genreSet[genre] ?: 0) + 1
            }
        }

        // Wrap every DB write in a single transaction so Room flushes once and
        // observers (LocalLibraryViewModel's StateFlows) only get one
        // invalidation pass.
        musicDatabase.withTransaction {
            localMediaDao.clearAllAlbums()
            localMediaDao.clearAllArtists()
            localMediaDao.clearAllGenres()

            val albumIdByTrackPath = HashMap<String, Long>(tracks.size)
            // Album cover propagated down to each track in the album, so
            // tracks without their own embedded art still render the album
            // cover in the song list instead of a music-note placeholder.
            val albumArtByTrackPath = HashMap<String, String?>(tracks.size)
            for ((key, albumTracks) in tracksByAlbumKey) {
                val representative = albumTracks.first()
                // A "synthetic" album is the bucket every track with a null/
                // blank `album` tag falls into — those tracks aren't actually
                // an album together, just unidentified files that share the
                // grouping key "unknown|unknown|0". If one of them happens to
                // have embedded art, propagating that single cover onto all
                // 1000+ unrelated tracks looks completely wrong (every song
                // ends up branded with one stray file's cover). So: never
                // store album art for the synthetic bucket, and never
                // propagate it down to its tracks.
                val isSyntheticAlbum = representative.album.isNullOrBlank()
                val albumArt = if (isSyntheticAlbum) null
                    else albumTracks.firstOrNull { it.hasEmbeddedArt }?.artworkCacheKey
                val albumEntity = LocalAlbumEntity(
                    title = representative.album ?: "Unknown Album",
                    artist = representative.albumArtist ?: representative.artist ?: "Unknown Artist",
                    year = representative.year,
                    genre = representative.genre,
                    trackCount = albumTracks.size,
                    totalDuration = albumTracks.sumOf { it.durationSeconds },
                    artworkCacheKey = albumArt,
                    bestQuality = albumTracks.maxByOrNull { qualityScore(it) }?.let {
                        "${it.codec} ${it.bitDepth ?: ""}/${(it.sampleRate / 1000)}"
                    },
                    groupingKey = key
                )
                val albumId = localMediaDao.upsertAlbum(albumEntity)
                for (track in albumTracks) {
                    albumIdByTrackPath[track.filePath] = albumId
                    albumArtByTrackPath[track.filePath] = albumArt
                }
            }

            val artistIdByTrackPath = HashMap<String, Long>(tracks.size)
            for ((normalizedName, artistTracks) in artistSet) {
                val representative = artistTracks.first()
                val displayName = representative.albumArtist ?: representative.artist ?: "Unknown Artist"
                val uniqueAlbums = artistTracks.mapNotNull { it.album }.toSet().size
                // Same logic as albums: the "Unknown Artist" bucket is a
                // synthetic grouping of unrelated files with no artist tag.
                // One stray file's embedded cover shouldn't appear next to
                // every other anonymous track in the artist list.
                val isSyntheticArtist = representative.albumArtist.isNullOrBlank() &&
                    representative.artist.isNullOrBlank()
                val artistArt = if (isSyntheticArtist) null
                    else artistTracks.firstNotNullOfOrNull { it.artworkCacheKey }
                        ?: artistTracks.firstNotNullOfOrNull { albumArtByTrackPath[it.filePath] }
                val artistEntity = LocalArtistEntity(
                    name = displayName,
                    normalizedName = normalizedName,
                    albumCount = uniqueAlbums,
                    trackCount = artistTracks.size,
                    artworkCacheKey = artistArt
                )
                val artistId = localMediaDao.upsertArtist(artistEntity)
                for (track in artistTracks) artistIdByTrackPath[track.filePath] = artistId
            }

            // Bulk-update every track in one statement (was: N updateTrack calls).
            // Trust hasEmbeddedArt (set by TagReader; covers both an embedded
            // picture atom and a folder sidecar) to decide ownership. Tracks
            // with their own art keep its path. Tracks without get the
            // album-propagated path — or null if the album is synthetic
            // (Unknown Album bucket). Always-overwriting on rebuild ensures
            // stale propagation from a previous scan gets cleaned up.
            val updatedTracks = tracks.map { t ->
                t.copy(
                    albumId = albumIdByTrackPath[t.filePath],
                    artistId = artistIdByTrackPath[t.filePath],
                    artworkCacheKey = if (t.hasEmbeddedArt) t.artworkCacheKey
                        else albumArtByTrackPath[t.filePath],
                )
            }
            if (updatedTracks.isNotEmpty()) {
                localMediaDao.updateTracks(updatedTracks)
            }

            for ((genre, count) in genreSet) {
                localMediaDao.upsertGenre(LocalGenreEntity(name = genre, trackCount = count))
            }
        }
    }

    private suspend fun rebuildFolders() {
        val allPaths = localMediaDao.getAllTrackPaths()
        val folderMap = mutableMapOf<String, MutableList<String>>()

        for (path in allPaths) {
            val folder = path.substringBeforeLast('/')
            folderMap.getOrPut(folder) { mutableListOf() }.add(path)
        }

        val folders = folderMap.map { (folderPath, filePaths) ->
            val parentPath = folderPath.substringBeforeLast('/').takeIf { it != folderPath }
            LocalFolderEntity(
                path = folderPath,
                parentPath = parentPath,
                displayName = folderPath.substringAfterLast('/'),
                trackCount = filePaths.size,
                totalDuration = 0 // Could compute from track durations
            )
        }

        // Clear + repopulate atomically so folder-tab observers never see an
        // empty list mid-scan, and the whole rebuild is one Room flush.
        musicDatabase.withTransaction {
            localMediaDao.clearFolders()
            if (folders.isNotEmpty()) {
                localMediaDao.upsertFolders(folders)
            }
        }
    }

    private suspend fun updateScanState() {
        val trackCount = localMediaDao.getTrackCount()
        val existingState = localMediaDao.getScanState()
        localMediaDao.updateScanState(
            ScanStateEntity(
                id = 1,
                lastFullScan = existingState?.lastFullScan ?: System.currentTimeMillis(),
                lastIncremental = System.currentTimeMillis(),
                totalTracks = trackCount,
                totalDuration = 0,
                totalSizeBytes = 0
            )
        )
    }

    private fun qualityScore(track: LocalTrackEntity): Int {
        val codecScore = when (track.codec) {
            "FLAC" -> 100
            "ALAC" -> 95
            "WAV" -> 90
            "AIFF" -> 90
            "APE" -> 85
            "OGG_VORBIS" -> 60
            "OPUS" -> 55
            "AAC" -> 50
            "MP3" -> 40
            "WMA" -> 30
            else -> 0
        }
        val bitDepthScore = (track.bitDepth ?: 16) * 2
        val sampleRateScore = track.sampleRate / 1000
        return codecScore + bitDepthScore + sampleRateScore
    }

    companion object {
        /** Rows per insertTracks() call — one Room transaction per batch. */
        private const val INSERT_BATCH_SIZE = 500

        /** Paths per DELETE ... IN (...) — under SQLite's 999-variable limit. */
        private const val DELETE_CHUNK_SIZE = 500

        /**
         * Decide whether a MediaStore file needs its tags (re-)read or the
         * existing DB row can be kept as-is. Pure function so it's unit
         * testable; [artworkFileExists] is injectable for the same reason.
         */
        fun needsReRead(
            existing: TrackScanInfo?,
            mediaStoreDateModified: Long,
            artworkFileExists: (String) -> Boolean = { File(it).exists() }
        ): Boolean {
            if (existing == null) return true
            if (existing.lastModified < mediaStoreDateModified) return true
            // Android reaps the app cache/ directory under storage
            // pressure, leaving Room rows pointing at vanished
            // artwork files. mtime hasn't changed, so without this
            // extra check the refresh button would never repopulate
            // them and the user is stuck with empty cards.
            if (existing.artworkCacheKey != null &&
                !artworkFileExists(existing.artworkCacheKey)
            ) return true
            // Re-tag tracks whose previous scan came up artwork-less.
            // The TagReader sidecar matcher has been extended over
            // time (per-track stem match was added later), so a
            // null cache key on an existing row may just mean "the
            // older scan logic missed it" — re-read so freshly-
            // installed cover detection logic gets a chance.
            if (!existing.hasEmbeddedArt && existing.artworkCacheKey == null) return true
            // Re-read rows that were indexed before artist-from-title
            // recovery existed: no artist tag, but a "Artist - Title"
            // shaped title we can now split. Self-heals (artist gets
            // populated, so the next scan skips them) and stays cheap
            // by only targeting files that can actually benefit.
            if (existing.artist == null && existing.title?.contains(" - ") == true) return true
            return false
        }

        fun normalizeText(text: String): String {
            return Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFC)
        }

        fun buildAlbumGroupingKey(album: String?, artist: String?, year: Int?): String {
            val normalizedAlbum = normalizeText(album ?: "unknown")
            val normalizedArtist = normalizeText(artist ?: "unknown")
            return "$normalizedAlbum|$normalizedArtist|${year ?: 0}"
        }
    }
}
