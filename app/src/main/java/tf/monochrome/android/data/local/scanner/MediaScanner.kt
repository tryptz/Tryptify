package tf.monochrome.android.data.local.scanner

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import tf.monochrome.android.data.local.db.LocalAlbumEntity
import tf.monochrome.android.data.local.db.LocalArtistEntity
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.db.LocalGenreEntity
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.db.ScanStateEntity
import tf.monochrome.android.data.local.tags.TagReader
import java.text.Normalizer
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
    private val musicDatabase: tf.monochrome.android.data.db.MusicDatabase
) {

    fun fullScan(
        minDurationMs: Long = 30_000,
        excludedPaths: Set<String> = emptySet()
    ): Flow<ScanProgress> = flow {
        try {
            val mediaStoreFiles = mediaStoreSource.queryAllAudio(minDurationMs, excludedPaths)
            emit(ScanProgress.Started(totalFiles = mediaStoreFiles.size))

            var addedCount = 0
            // Memoize sidecar cover art lookups by parent directory so we don't
            // listFiles() once per track. A 500-track album → 1 directory scan.
            val folderArtCache = HashMap<String, String?>()

            mediaStoreFiles.forEachIndexed { index, audioFile ->
                try {
                    val existing = localMediaDao.findByPath(audioFile.absolutePath)
                    if (existing == null || existing.lastModified < audioFile.dateModified) {
                        val tags = tagReader.readTags(audioFile.absolutePath, folderArtCache)
                        val trackEntity = buildTrackEntity(audioFile, tags)
                        localMediaDao.insertTrack(trackEntity)
                        addedCount++
                    }
                } catch (_: Exception) {
                    // Skip individual file errors
                }

                if (index % 50 == 0 || index == mediaStoreFiles.size - 1) {
                    emit(ScanProgress.Processing(
                        current = index + 1,
                        total = mediaStoreFiles.size,
                        currentFile = audioFile.displayName
                    ))
                }
            }

            // Prune deleted files
            emit(ScanProgress.Grouping("Removing deleted tracks..."))
            val existingPaths = mediaStoreFiles.map { it.absolutePath }.toSet()
            localMediaDao.deleteTracksNotIn(existingPaths)
            val removedCount = localMediaDao.getAllTrackPaths().size.let { mediaStoreFiles.size - it }

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
            val scanState = localMediaDao.getScanState()
            val lastScan = scanState?.lastIncremental ?: scanState?.lastFullScan ?: 0

            val modifiedFiles = mediaStoreSource.queryModifiedSince(lastScan, minDurationMs)
            if (modifiedFiles.isEmpty()) {
                emit(ScanProgress.Complete(scanned = 0, added = 0, removed = 0))
                return@flow
            }

            emit(ScanProgress.Started(totalFiles = modifiedFiles.size))
            var addedCount = 0
            val folderArtCache = HashMap<String, String?>()

            modifiedFiles.forEachIndexed { index, audioFile ->
                try {
                    val tags = tagReader.readTags(audioFile.absolutePath, folderArtCache)
                    val trackEntity = buildTrackEntity(audioFile, tags)
                    localMediaDao.insertTrack(trackEntity)
                    addedCount++
                } catch (_: Exception) {}

                if (index % 20 == 0 || index == modifiedFiles.size - 1) {
                    emit(ScanProgress.Processing(index + 1, modifiedFiles.size, audioFile.displayName))
                }
            }

            // Check for deleted files
            val allMediaStorePaths = mediaStoreSource.queryAllAudio(minDurationMs)
                .map { it.absolutePath }.toSet()
            localMediaDao.deleteTracksNotIn(allMediaStorePaths)

            rebuildGroupings()
            rebuildFolders()
            updateScanState()

            emit(ScanProgress.Complete(scanned = modifiedFiles.size, added = addedCount, removed = 0))
        } catch (e: Exception) {
            emit(ScanProgress.Error(e.message ?: "Incremental scan error"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun buildTrackEntity(audioFile: AudioFileInfo, tags: tf.monochrome.android.data.local.tags.AudioTags): LocalTrackEntity {
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
            for ((key, albumTracks) in tracksByAlbumKey) {
                val representative = albumTracks.first()
                val albumEntity = LocalAlbumEntity(
                    title = representative.album ?: "Unknown Album",
                    artist = representative.albumArtist ?: representative.artist ?: "Unknown Artist",
                    year = representative.year,
                    genre = representative.genre,
                    trackCount = albumTracks.size,
                    totalDuration = albumTracks.sumOf { it.durationSeconds },
                    artworkCacheKey = albumTracks.firstOrNull { it.hasEmbeddedArt }?.artworkCacheKey,
                    bestQuality = albumTracks.maxByOrNull { qualityScore(it) }?.let {
                        "${it.codec} ${it.bitDepth ?: ""}/${(it.sampleRate / 1000)}"
                    },
                    groupingKey = key
                )
                val albumId = localMediaDao.upsertAlbum(albumEntity)
                for (track in albumTracks) albumIdByTrackPath[track.filePath] = albumId
            }

            val artistIdByTrackPath = HashMap<String, Long>(tracks.size)
            for ((normalizedName, artistTracks) in artistSet) {
                val displayName = artistTracks.first().let { it.albumArtist ?: it.artist ?: "Unknown Artist" }
                val uniqueAlbums = artistTracks.mapNotNull { it.album }.toSet().size
                val artistEntity = LocalArtistEntity(
                    name = displayName,
                    normalizedName = normalizedName,
                    albumCount = uniqueAlbums,
                    trackCount = artistTracks.size,
                    artworkCacheKey = artistTracks.firstOrNull { it.hasEmbeddedArt }?.artworkCacheKey
                )
                val artistId = localMediaDao.upsertArtist(artistEntity)
                for (track in artistTracks) artistIdByTrackPath[track.filePath] = artistId
            }

            // Bulk-update every track in one statement (was: N updateTrack calls).
            val updatedTracks = tracks.map { t ->
                t.copy(
                    albumId = albumIdByTrackPath[t.filePath],
                    artistId = artistIdByTrackPath[t.filePath]
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
        localMediaDao.clearFolders()

        val allPaths = localMediaDao.getAllTrackPaths()
        val folderMap = mutableMapOf<String, MutableList<String>>()

        for (path in allPaths) {
            val folder = path.substringBeforeLast('/')
            folderMap.getOrPut(folder) { mutableListOf() }.add(path)
        }

        for ((folderPath, filePaths) in folderMap) {
            val parentPath = folderPath.substringBeforeLast('/').takeIf { it != folderPath }
            localMediaDao.upsertFolder(
                LocalFolderEntity(
                    path = folderPath,
                    parentPath = parentPath,
                    displayName = folderPath.substringAfterLast('/'),
                    trackCount = filePaths.size,
                    totalDuration = 0 // Could compute from track durations
                )
            )
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
