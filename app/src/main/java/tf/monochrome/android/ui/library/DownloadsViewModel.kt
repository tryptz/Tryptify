package tf.monochrome.android.ui.library

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack
import java.io.File
import javax.inject.Inject

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
) : ViewModel() {

    /**
     * Combined view of downloads: every track tracked by Room
     * (downloaded by this app) plus every audio file present in the
     * user-selected SAF folder that the app didn't write itself
     * (sideloaded files, prior installs, manual copies). Sideloaded
     * files are surfaced as synthetic DownloadedTrackEntity rows so
     * the rest of the screen — album grouping, tap-to-play, delete —
     * works uniformly. The synthetic id is a stable hash of the URI
     * so re-scans don't shuffle the list.
     */
    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>> =
        combine(
            downloadDao.getDownloadedTracks(),
            preferences.downloadFolderUri,
        ) { roomRows, folderUri ->
            val sideloaded = scanFolderForSideloadedTracks(folderUri, roomRows)
            // Newest first overall; synthetic rows bias to file timestamp.
            (roomRows + sideloaded).sortedByDescending { it.downloadedAt }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumGroups: StateFlow<List<DownloadedAlbumGroup>> =
        downloadedTracks
            .map { entities ->
                entities
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

    /**
     * Walk the SAF tree the user picked as the download folder and surface
     * any audio file not already represented in Room. Slow on large folders
     * (DocumentFile.listFiles round-trips through ContentResolver), so we
     * only run it from the combine() flow which is dispatched on IO.
     */
    private fun scanFolderForSideloadedTracks(
        folderUriString: String?,
        knownRoomRows: List<DownloadedTrackEntity>,
    ): List<DownloadedTrackEntity> {
        if (folderUriString.isNullOrBlank()) return emptyList()
        val tree = runCatching {
            DocumentFile.fromTreeUri(appCtx, folderUriString.toUri())
        }.getOrNull() ?: return emptyList()
        if (!tree.canRead()) return emptyList()

        // First pass — collect everything once. listFiles() is the expensive
        // call; iterating the local list afterwards is free.
        val children = tree.listFiles().filter { it.isFile && it.canRead() }

        // Folder-level art (cover.jpg / folder.png / albumart.webp). The
        // DownloadWorker drops one of these alongside the audio so the system
        // MediaScanner picks it up. We match by stem so any of the four
        // common names work.
        val folderArtUri = children
            .firstOrNull { f ->
                val n = f.name?.lowercase() ?: return@firstOrNull false
                val stem = n.substringBeforeLast('.')
                val ext = n.substringAfterLast('.', "")
                stem in COVER_STEMS && ext in IMAGE_EXTENSIONS
            }
            ?.uri
            ?.toString()

        // Per-track sidecar art (e.g. "Artist - Title.jpg" next to
        // "Artist - Title.flac"). Index by stem so the audio loop is O(n).
        val sidecarArtByStem: Map<String, String> = children.asSequence()
            .mapNotNull { f ->
                val n = f.name ?: return@mapNotNull null
                val ext = n.substringAfterLast('.', "").lowercase()
                if (ext !in IMAGE_EXTENSIONS) return@mapNotNull null
                val stem = n.substringBeforeLast('.')
                if (stem.lowercase() in COVER_STEMS) return@mapNotNull null
                stem to f.uri.toString()
            }
            .toMap()

        val knownPaths = knownRoomRows.mapTo(HashSet()) { it.filePath }
        val out = mutableListOf<DownloadedTrackEntity>()
        for (file in children) {
            val name = file.name ?: continue
            if (!isAudioFile(name, file.type)) continue
            val pathString = file.uri.toString()
            if (pathString in knownPaths) continue
            val stem = name.substringBeforeLast('.')
            val cover = sidecarArtByStem[stem] ?: folderArtUri
            out += syntheticEntityFor(file, pathString, name, cover)
        }
        return out
    }

    private fun isAudioFile(name: String, mime: String?): Boolean {
        if (mime?.startsWith("audio/") == true) return true
        val lower = name.lowercase()
        return AUDIO_EXTENSIONS.any { lower.endsWith(".$it") }
    }

    private fun syntheticEntityFor(
        file: DocumentFile,
        path: String,
        name: String,
        coverUri: String?,
    ): DownloadedTrackEntity {
        // Filename convention written by DownloadWorker is
        // "<artist> - <title>.<ext>" — try to recover the split, fall
        // back to the bare name.
        val withoutExt = name.substringBeforeLast('.', name)
        val (artist, title) = withoutExt.split(" - ", limit = 2)
            .let { if (it.size == 2) it[0] to it[1] else "" to withoutExt }
        // Stable id derived from the URI so successive scans don't drift the
        // LazyColumn keying. Always negative so it can't collide with a real
        // catalog track id (those are positive Longs from TIDAL/Qobuz).
        val syntheticId = -((path.hashCode().toLong() and 0x7FFFFFFFL) or 1L)
        return DownloadedTrackEntity(
            id = syntheticId,
            title = title,
            duration = 0,
            artistName = artist,
            albumTitle = null,
            albumCover = coverUri,
            filePath = path,
            quality = AudioQuality.LOSSLESS.name,
            sizeBytes = file.length(),
            downloadedAt = file.lastModified().takeIf { it > 0 } ?: 0L,
        )
    }

    fun deleteDownload(track: DownloadedTrackEntity) {
        viewModelScope.launch { deleteOne(track) }
    }

    fun deleteDownloads(tracks: List<DownloadedTrackEntity>) {
        viewModelScope.launch { tracks.forEach { deleteOne(it) } }
    }

    private suspend fun deleteOne(track: DownloadedTrackEntity) {
        if (track.filePath.startsWith("content://")) {
            try {
                val uri = track.filePath.toUri()
                val docFile = DocumentFile.fromSingleUri(appCtx, uri)
                docFile?.delete()
            } catch (e: Exception) {
                // Ignore exceptions during content deletion
            }
        } else {
            val file = File(track.filePath)
            if (file.exists()) file.delete()
        }
        downloadDao.deleteDownloadedTrack(track.id)
    }

    companion object {
        const val SINGLES_LABEL = "Singles"
        // Lower-case extensions DownloadWorker may produce + the formats
        // users typically sideload. Mime-type sniff still wins; this list
        // catches files SAF reports without a type (common on some
        // providers).
        private val AUDIO_EXTENSIONS = setOf(
            "flac", "alac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "wma",
        )
        // Common folder-level cover filenames (see also Android's MediaScanner
        // and most desktop tag editors). The DownloadWorker writes "cover.jpg".
        private val COVER_STEMS = setOf("cover", "folder", "albumart", "album")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

// File-private extension: map a downloaded entity onto the unified track shape
// the player expects. Codec/sample-rate are inferred from the saved quality
// label; ExoPlayer sniffs the actual container so these are advisory.
fun DownloadedTrackEntity.toUnifiedTrack(): UnifiedTrack {
    val quality = runCatching { AudioQuality.valueOf(quality) }.getOrDefault(AudioQuality.LOSSLESS)
    val (codec, sampleRate, bitDepth) = when (quality) {
        AudioQuality.HI_RES -> Triple(AudioCodec.FLAC, 96_000, 24)
        AudioQuality.LOSSLESS -> Triple(AudioCodec.FLAC, 44_100, 16)
        AudioQuality.HIGH, AudioQuality.LOW -> Triple(AudioCodec.MP3, 44_100, null)
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
        artworkUri = albumCover,
        source = PlaybackSource.LocalFile(
            filePath = filePath,
            codec = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        ),
        sourceType = SourceType.LOCAL,
    )
}
