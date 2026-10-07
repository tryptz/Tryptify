package tf.monochrome.android.ui.library

import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.downloads.SafPaths
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.scanner.MediaScanner
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack
import java.io.File
import javax.inject.Inject
import tf.monochrome.android.R

/**
 * Loose grouping of downloaded tracks for the Albums section. Album rows are
 * keyed by (albumTitle, artistName) so a tracks-only download (album = null)
 * collapses into a synthetic "Singles" bucket.
 */
data class DownloadedAlbumGroup(
    val title: String,
    val artistName: String,
    val cover: String?,
    val trackCount: Int,
    val tracks: List<DownloadedTrackEntity>,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val appCtx: android.app.Application,
    private val downloadDao: DownloadDao,
    private val preferences: PreferencesManager,
    private val localMediaDao: LocalMediaDao,
) : ViewModel() {

    /**
     * Combined view of downloads: every track tracked by Room (downloaded by
     * this app) plus every other audio file in the download folder
     * (sideloaded files, prior installs, manual copies), as synthetic
     * DownloadedTrackEntity rows so the rest of the screen — album grouping,
     * tap-to-play, delete — works uniformly.
     *
     * The other files come from the local library, which has already scanned
     * them with their tags, rather than from a walk of the SAF folder of its
     * own: that walk took seconds, and the list waited for it. Both halves
     * are Room queries, so the list arrives at once. Null until it does, so
     * the screen does not claim there are no downloads while still looking.
     */
    private val roomTracks = downloadDao.getDownloadedTracks()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val folderTracks: Flow<List<DownloadedTrackEntity>> =
        preferences.downloadFolderUri
            .map { folder -> folder?.let(SafPaths::absolutePath)?.trimEnd('/')?.plus('/') }
            .distinctUntilChanged()
            .flatMapLatest { prefix ->
                // No folder picked: downloads are in app storage, which the
                // local library does not scan, and nothing else is there.
                if (prefix == null) flowOf(emptyList())
                else localMediaDao.observeTracksUnder(prefix).map { rows -> rows.map { it.toDownloadedTrack() } }
            }

    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>?> =
        combine(roomTracks, folderTracks) { roomRows, folderRows ->
            // A file this app downloaded is Room's row, not a second one.
            val known = roomRows.mapTo(HashSet()) { SafPaths.absolutePath(it.filePath) ?: it.filePath }
            // Newest first overall; folder rows bias to file timestamp.
            (roomRows + folderRows.filter { it.filePath !in known }).sortedByDescending { it.downloadedAt }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val albumGroups: StateFlow<List<DownloadedAlbumGroup>> =
        downloadedTracks
            .map { entities ->
                entities.orEmpty()
                    .groupBy { (it.albumTitle ?: SINGLES_LABEL) to it.artistName }
                    .map { (key, list) ->
                        val cover = list.firstNotNullOfOrNull { it.albumCover }
                        DownloadedAlbumGroup(
                            title = key.first,
                            artistName = key.second,
                            cover = cover,
                            trackCount = list.size,
                            // Within the album sort by trackNumber-equivalent
                            // proxy (downloadedAt) so the play order is stable.
                            tracks = list.sortedBy { it.downloadedAt },
                        )
                    }
                    .sortedBy { it.title.lowercase() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A local-library track in the download folder, as a Downloads row. */
    private fun LocalTrackEntity.toDownloadedTrack(): DownloadedTrackEntity = DownloadedTrackEntity(
        // Stable id derived from the path so the list keeps its keys. Always
        // negative so it can't collide with a real catalog track id (those
        // are positive Longs from TIDAL/Qobuz).
        id = -((filePath.hashCode().toLong() and 0x7FFFFFFFL) or 1L),
        title = title ?: MediaScanner.titleFromPath(filePath),
        duration = durationSeconds,
        artistName = artist ?: albumArtist ?: "Unknown Artist",
        albumTitle = album,
        albumCover = artworkCacheKey,
        filePath = filePath,
        quality = when {
            !codec.equals("FLAC", ignoreCase = true) && !codec.equals("ALAC", ignoreCase = true) -> AudioQuality.HIGH
            (bitDepth ?: 16) > 16 -> AudioQuality.HI_RES
            else -> AudioQuality.LOSSLESS
        }.name,
        sizeBytes = fileSizeBytes,
        // MediaStore dates files in seconds; download rows in milliseconds.
        downloadedAt = if (lastModified in 1 until 100_000_000_000L) lastModified * 1000 else lastModified,
        isThxSpatialAudio = isThxSpatialAudio,
        isDolbyAtmos = isDolbyAtmos,
    )

    // One-shot user messages (e.g. a delete that couldn't remove the file).
    private val _messages = MutableSharedFlow<tf.monochrome.android.ui.components.UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<tf.monochrome.android.ui.components.UiText> = _messages.asSharedFlow()

    fun deleteDownloads(tracks: List<DownloadedTrackEntity>) {
        viewModelScope.launch {
            val failed = tracks.count { !deleteOne(it) }
            if (failed > 0) {
                _messages.tryEmit(tf.monochrome.android.ui.components.UiText.Plural(R.plurals.delete_failed_count, failed))
            }
        }
    }

    /**
     * Removes the file and its Room record. Returns whether the file itself was
     * actually removed — a sideloaded (SAF content://) file that the provider
     * refuses to delete would otherwise silently reappear on the next folder
     * scan, so the caller surfaces a message on false. Runs off the main thread.
     */
    private suspend fun deleteOne(track: DownloadedTrackEntity): Boolean =
        withContext(Dispatchers.IO) {
            val fileRemoved = if (track.filePath.startsWith("content://")) {
                try {
                    val uri = track.filePath.toUri()
                    DocumentFile.fromSingleUri(appCtx, uri)?.delete() ?: false
                } catch (e: Exception) {
                    false
                }
            } else {
                val file = File(track.filePath)
                !file.exists() || file.delete() || deleteThroughFolder(track.filePath)
            }
            // App-written downloads live in Room; sideloaded rows don't, so this
            // is a harmless no-op for them (they leave once the file is gone).
            downloadDao.deleteDownloadedTrack(track.id)
            fileRemoved
        }

    /**
     * Deletes file [path] through the download folder's SAF grant. A file the
     * app did not write is out of reach of File.delete on Android 11+, but the
     * folder picked for downloads was granted with write access.
     */
    private suspend fun deleteThroughFolder(path: String): Boolean = runCatching {
        val tree = preferences.downloadFolderUri.first()?.toUri() ?: return false
        val documentId = SafPaths.documentIdOfPath(path) ?: return false
        DocumentsContract.deleteDocument(
            appCtx.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
        )
    }.getOrDefault(false)

    companion object {
        const val SINGLES_LABEL = "Singles"
    }
}

// File-private extension: map a downloaded entity onto the unified track shape
// the player expects. Codec/sample-rate are inferred from the saved quality
// label; ExoPlayer sniffs the actual container so these are advisory.
fun DownloadedTrackEntity.toUnifiedTrack(): UnifiedTrack {
    val quality = runCatching { AudioQuality.valueOf(quality) }.getOrDefault(AudioQuality.LOSSLESS)
    val (codec, sampleRate, bitDepth) = when {
        // TIDAL's Atmos mix: E-AC-3 JOC at 48 kHz, whatever tier was asked for.
        isDolbyAtmos -> Triple(AudioCodec.EAC3, 48_000, null)
        quality == AudioQuality.HI_RES -> Triple(AudioCodec.FLAC, 96_000, 24)
        quality == AudioQuality.LOSSLESS -> Triple(AudioCodec.FLAC, 44_100, 16)
        else -> Triple(AudioCodec.MP3, 44_100, null)
    }
    return UnifiedTrack(
        id = "download_$id",
        title = title,
        durationSeconds = duration,
        artistName = artistName.ifBlank { "Unknown Artist" },
        artistNames = listOfNotNull(artistName.takeIf { it.isNotBlank() }),
        albumArtistName = artistName.takeIf { it.isNotBlank() },
        albumTitle = albumTitle,
        albumId = albumTitle?.let { "download_album_${it.hashCode()}" },
        // The record keeps TIDAL's cover id, not a URL: handed over as it was,
        // the notification and lock screen tried to open "/<id>" as a file.
        artworkUri = albumCover?.let { tf.monochrome.android.domain.model.buildCoverUrl(it, 640) },
        source = PlaybackSource.LocalFile(
            filePath = filePath,
            codec = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        ),
        sourceType = SourceType.LOCAL,
        isDolbyAtmos = isDolbyAtmos,
    )
}
