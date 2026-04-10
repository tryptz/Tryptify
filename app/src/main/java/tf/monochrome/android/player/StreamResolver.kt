package tf.monochrome.android.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.CollectionDirectLink
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.TrackStream
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.model.buildCoverUrl
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedMedia(
    val mediaItem: MediaItem,
    val trackStream: TrackStream? = null,
    val isLocalFile: Boolean = false,
    val isEncrypted: Boolean = false,
    val encryptionKey: String? = null,
    val isDash: Boolean = false
)

@Singleton
class StreamResolver @Inject constructor(
    private val repository: MusicRepository
) {
    private fun normalizeArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.toUri()
        return if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(raw)) else parsed
    }

    // Legacy method for existing Track model
    suspend fun resolveMediaItem(track: Track): Pair<MediaItem, TrackStream?> {
        val streamResult = repository.getTrackStream(track.id)
        val trackStream = streamResult.getOrNull()

        val mediaItem = if (trackStream != null) {
            buildMediaItem(track, trackStream.streamUrl, trackStream.isDash)
        } else {
            buildMediaItem(track, "", false)
        }

        return Pair(mediaItem, trackStream)
    }

    // New method for UnifiedTrack
    @OptIn(UnstableApi::class)
    suspend fun resolveUnifiedTrack(track: UnifiedTrack): ResolvedMedia {
        return when (val source = track.source) {
            is PlaybackSource.LocalFile -> resolveLocalFile(track, source)
            is PlaybackSource.CollectionDirect -> resolveCollectionDirect(track, source)
            is PlaybackSource.HiFiApi -> resolveHiFiApi(track, source)
        }
    }

    private fun resolveLocalFile(
        track: UnifiedTrack,
        source: PlaybackSource.LocalFile
    ): ResolvedMedia {
        val file = File(source.filePath)
        val uri = Uri.fromFile(file)

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(normalizeArtworkUri(track.artworkUri))
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            isLocalFile = true
        )
    }

    private fun resolveCollectionDirect(
        track: UnifiedTrack,
        source: PlaybackSource.CollectionDirect
    ): ResolvedMedia {
        val bestLink = selectBestLink(source.directLinks, source.preferredQuality.apiValue)

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(normalizeArtworkUri(track.artworkUri))
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(bestLink?.url?.toUri() ?: Uri.EMPTY)
            .setMediaMetadata(metadata)
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            isEncrypted = true,
            encryptionKey = source.encryptionKey
        )
    }

    private suspend fun resolveHiFiApi(
        track: UnifiedTrack,
        source: PlaybackSource.HiFiApi
    ): ResolvedMedia {
        val streamResult = repository.getTrackStream(source.tidalId)
        val trackStream = streamResult.getOrNull()

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(normalizeArtworkUri(track.artworkUri))
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .apply {
                if (trackStream != null && trackStream.streamUrl.isNotBlank()) {
                    if (trackStream.isDash) {
                        setUri(Uri.EMPTY)
                        setMimeType("application/dash+xml")
                    } else {
                        setUri(trackStream.streamUrl.toUri())
                    }
                }
            }
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            trackStream = trackStream,
            isDash = trackStream?.isDash ?: false
        )
    }

    private fun selectBestLink(
        links: List<CollectionDirectLink>,
        preferredQuality: String
    ): CollectionDirectLink? {
        // Try preferred quality first
        links.firstOrNull { it.quality == preferredQuality }?.let { return it }

        // Quality priority order
        val qualityOrder = listOf("HI_RES_LOSSLESS", "HI_RES", "LOSSLESS", "HIGH", "LOW")
        for (quality in qualityOrder) {
            links.firstOrNull { it.quality == quality }?.let { return it }
        }

        return links.firstOrNull()
    }

    private fun buildMediaItem(track: Track, streamUrl: String, isDash: Boolean): MediaItem {
        val artworkUri = track.album?.cover?.let { cover ->
            buildCoverUrl(cover, 640).toUri()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.displayArtist)
            .setAlbumTitle(track.album?.title)
            .setArtworkUri(artworkUri)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.volumeNumber)
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadata)

        if (streamUrl.isNotBlank()) {
            if (isDash) {
                builder.setUri(Uri.EMPTY)
                    .setMimeType("application/dash+xml")
            } else {
                builder.setUri(streamUrl.toUri())
            }
        }

        return builder.build()
    }
}
