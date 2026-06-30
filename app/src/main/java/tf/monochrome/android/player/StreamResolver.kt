package tf.monochrome.android.player

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.api.QobuzTrackMatch
import tf.monochrome.android.data.cache.QobuzStreamCacheManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.CollectionDirectLink
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.TrackStream
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.model.buildCoverUrl
import tf.monochrome.android.domain.usecase.CrossSourceMatcher
import tf.monochrome.android.radio.RADIO_QUALITY_TAG
import tf.monochrome.android.radio.RADIO_SOURCE_EXTRA_KEY
import tf.monochrome.android.radio.RADIO_SOURCE_SPOTIFY
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

const val MEDIA_METADATA_IS_HI_RES = "tf.monochrome.android.media.IS_HI_RES"

data class ResolvedMedia(
    val mediaItem: MediaItem,
    val trackStream: TrackStream? = null,
    val isLocalFile: Boolean = false,
    val isEncrypted: Boolean = false,
    val encryptionKey: String? = null,
    val isDash: Boolean = false,
    // False when stream resolution failed and the item has no playable URI.
    // Callers must skip rather than feed it to ExoPlayer; otherwise
    // FileDataSource opens an empty path → ENOENT, or
    // DefaultMediaSourceFactory NPEs on a null localConfiguration.
    val isPlayable: Boolean = true,
)

@Singleton
class StreamResolver @Inject constructor(
    private val repository: MusicRepository,
    private val qobuzCache: QobuzStreamCacheManager,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {
    private fun MediaMetadata.Builder.applyRadioExtras(track: UnifiedTrack): MediaMetadata.Builder {
        return setExtras(
            Bundle().apply {
                putBoolean(MEDIA_METADATA_IS_HI_RES, track.isHiResTrack())
                if (track.qualityTags.orEmpty().contains(RADIO_QUALITY_TAG)) {
                    putString(RADIO_SOURCE_EXTRA_KEY, RADIO_SOURCE_SPOTIFY)
                }
            }
        )
    }

    private fun UnifiedTrack.isHiResTrack(): Boolean {
        val tags = qualityTags.orEmpty()
        return bitDepth?.let { it >= 24 } == true ||
            sampleRate?.let { it >= 88_200 } == true ||
            tags.any { it.equals("HI_RES", ignoreCase = true) || it.equals("HI_RES_LOSSLESS", ignoreCase = true) }
    }

    private fun normalizeArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.toUri()
        return if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(raw)) else parsed
    }

    private fun dashManifestUri(manifestOrUrl: String): Uri {
        if (!manifestOrUrl.contains("<MPD")) return manifestOrUrl.toUri()
        val encoded = Base64.encodeToString(
            manifestOrUrl.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return "data:application/dash+xml;base64,$encoded".toUri()
    }

    // Legacy method for existing Track model. Returns (null, null) when the
    // stream couldn't be resolved — callers must skip instead of feeding an
    // empty MediaItem to ExoPlayer.
    suspend fun resolveMediaItem(track: Track): Pair<MediaItem?, TrackStream?> {
        val streamResult = repository.getTrackStream(track.id)
        val trackStream = streamResult.getOrNull()

        // DASH carries its payload inline as MPD XML, so convert it to a
        // playable data: URI here instead of handing the session a URI-less
        // MediaItem. Blank manifests are treated as unresolved.
        if (trackStream != null && trackStream.streamUrl.isNotBlank()) {
            return Pair(buildMediaItem(track, trackStream.streamUrl, trackStream.isDash), trackStream)
        }

        // TIDAL is unavailable for this track (instance down, track pulled, no
        // manifest) — fall back to the same song on Qobuz so a TIDAL-built
        // playlist keeps playing.
        val fallback = qobuzFallbackMediaItem(
            tidalId = track.id,
            knownIsrc = null,
            tidalAlbumId = track.album?.id,
            tidalArtistId = track.artist?.id,
            mediaId = track.id.toString(),
            title = track.title,
            artist = track.displayArtist,
            durationSeconds = track.duration,
            albumTitle = track.album?.title,
            artworkUri = track.album?.cover?.let { buildCoverUrl(it, 640).toUri() },
            trackNumber = track.trackNumber,
            discNumber = track.volumeNumber,
        )
        return Pair(fallback, trackStream)
    }

    // New method for UnifiedTrack
    @OptIn(UnstableApi::class)
    suspend fun resolveUnifiedTrack(track: UnifiedTrack): ResolvedMedia {
        return when (val source = track.source) {
            is PlaybackSource.LocalFile -> resolveLocalFile(track, source)
            is PlaybackSource.CollectionDirect -> resolveCollectionDirect(track, source)
            is PlaybackSource.HiFiApi -> resolveHiFiApi(track, source)
            is PlaybackSource.QobuzCached -> resolveQobuzCached(track, source)
        }
    }

    // Qobuz resolution = "fetch via /api/download-music, park in app cache,
    // play from local file". The cache manager dedupes concurrent plays of
    // the same track and evicts oldest entries when over the size cap. If
    // Qobuz isn't configured or the fetch fails, mark the result not playable
    // so PlaybackService can skip it instead of handing ExoPlayer a
    // FileDataSource with an empty path (ENOENT spam).
    private suspend fun resolveQobuzCached(
        track: UnifiedTrack,
        source: PlaybackSource.QobuzCached,
    ): ResolvedMedia {
        val cachedFile = qobuzCache.getOrFetch(source.qobuzId, source.preferredQuality)

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(normalizeArtworkUri(track.artworkUri))
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .applyRadioExtras(track)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .apply { cachedFile?.let { setUri(Uri.fromFile(it)) } }
            .setMediaMetadata(metadata)
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            isLocalFile = cachedFile != null,
            isPlayable = cachedFile != null,
        )
    }

    private fun resolveLocalFile(
        track: UnifiedTrack,
        source: PlaybackSource.LocalFile
    ): ResolvedMedia {
        // DownloadWorker stores filePath either as an absolute filesystem path
        // (internal storage) or as a content:// URI string (when the user
        // picked a SAF folder). Wrapping a content:// path with File(...) +
        // Uri.fromFile produces a malformed file:// URI that ExoPlayer can't
        // open and DefaultMediaSourceFactory NPEs on. Detect the scheme and
        // route accordingly.
        val uri = if (source.filePath.startsWith("content://") ||
            source.filePath.startsWith("file://")
        ) {
            source.filePath.toUri()
        } else {
            Uri.fromFile(File(source.filePath))
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(normalizeArtworkUri(track.artworkUri))
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .applyRadioExtras(track)
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
            .applyRadioExtras(track)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .apply { bestLink?.url?.takeIf { it.isNotBlank() }?.let { setUri(it.toUri()) } }
            .setMediaMetadata(metadata)
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            isEncrypted = true,
            encryptionKey = source.encryptionKey,
            isPlayable = bestLink?.url?.isNotBlank() == true,
        )
    }

    private suspend fun resolveHiFiApi(
        track: UnifiedTrack,
        source: PlaybackSource.HiFiApi
    ): ResolvedMedia {
        val streamResult = repository.getTrackStream(source.tidalId)
        val trackStream = streamResult.getOrNull()

        // DASH carries its MPD inline, but callers still need a playable URI.
        val isDash = trackStream?.isDash == true
        val isPlayable = trackStream != null && trackStream.streamUrl.isNotBlank()

        // TIDAL unavailable — try the same song on Qobuz before giving up.
        if (!isPlayable) {
            val fallback = qobuzFallbackMediaItem(
                tidalId = source.tidalId,
                knownIsrc = track.isrc,
                // UnifiedTrack carries no numeric TIDAL album/artist ids, and
                // main-player navigation keys off the legacy Track anyway, so
                // there's nothing to bridge from this path.
                tidalAlbumId = null,
                tidalArtistId = null,
                mediaId = track.id,
                title = track.title,
                artist = track.artistName,
                durationSeconds = track.durationSeconds,
                albumTitle = track.albumTitle,
                artworkUri = normalizeArtworkUri(track.artworkUri),
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
            )
            if (fallback != null) {
                return ResolvedMedia(mediaItem = fallback, isLocalFile = true, isPlayable = true)
            }
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(normalizeArtworkUri(track.artworkUri))
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .applyRadioExtras(track)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .apply {
                if (trackStream != null && trackStream.streamUrl.isNotBlank()) {
                    if (trackStream.isDash) {
                        setUri(dashManifestUri(trackStream.streamUrl))
                        setMimeType(MimeTypes.APPLICATION_MPD)
                    } else {
                        setUri(trackStream.streamUrl.toUri())
                    }
                }
            }
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            trackStream = trackStream,
            isDash = isDash,
            isPlayable = isPlayable,
        )
    }

    /**
     * Last-resort fallback for TIDAL (HiFiApi) tracks: when the TIDAL stream
     * can't be resolved (instance down, track pulled, no manifest), find the
     * same recording on Qobuz and play it from the Qobuz cache. This is what
     * lets a playlist built from TIDAL keep playing when TIDAL is down.
     *
     * Matching is ISRC-first — the ISRC uniquely identifies the recording
     * across catalogues, so it can't grab the wrong song. The ISRC comes from
     * the track itself when known, otherwise from TIDAL's metadata pool (which
     * usually answers even when streaming doesn't). Only when no ISRC is
     * available do we fall back to a strict title+artist metadata match.
     *
     * Returns null when Qobuz isn't configured, no confident match is found, or
     * the fetch fails — callers then skip the track exactly as before.
     */
    private suspend fun qobuzFallbackMediaItem(
        tidalId: Long,
        knownIsrc: String?,
        tidalAlbumId: Long?,
        tidalArtistId: Long?,
        mediaId: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        albumTitle: String?,
        artworkUri: Uri?,
        trackNumber: Int?,
        discNumber: Int?,
    ): MediaItem? {
        val isrc = knownIsrc?.takeIf { it.isNotBlank() } ?: repository.getTidalIsrc(tidalId)
        val match = isrc?.let { repository.findQobuzByIsrc(it) }
            ?: metadataMatchQobuz(title, artist, durationSeconds)
            ?: return null

        // Bridge navigation: make the playing TIDAL album/artist ids resolve to
        // the matched Qobuz release/artist so "Go to album/artist" on the main
        // player works for a fallback-played track.
        val albumSlug = match.albumSlug
        if (tidalAlbumId != null && !albumSlug.isNullOrBlank()) {
            qobuzIdRegistry.registerAlbum(tidalAlbumId, albumSlug)
        }
        val qobuzArtistId = match.artistId
        if (tidalArtistId != null && qobuzArtistId != null) {
            qobuzIdRegistry.registerArtistAlias(tidalArtistId, qobuzArtistId)
        }

        val file = runCatching { qobuzCache.getOrFetch(match.trackId, AudioQuality.LOSSLESS) }
            .getOrNull() ?: return null

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(albumTitle)
            .setArtworkUri(artworkUri)
            .setTrackNumber(trackNumber)
            .setDiscNumber(discNumber)
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Title + artist fallback for when no ISRC is available. Strict on purpose:
     * exact normalised title + artist (with a ~3s duration tolerance, then
     * duration-relaxed) so it never plays the wrong recording.
     */
    private suspend fun metadataMatchQobuz(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): QobuzTrackMatch? {
        if (title.isBlank() || artist.isBlank()) return null
        // Qobuz joins the version onto its titles with an em dash; strip it so
        // the search and comparison line up with TIDAL's plain title.
        val cleanTitle = title.substringBefore(" — ").trim().ifBlank { title }
        val candidates = repository.searchQobuz("$cleanTitle $artist").getOrNull()?.tracks
            ?: return null
        val match = candidates.firstOrNull { c ->
            CrossSourceMatcher.fuzzyMatch(
                cleanTitle, artist, durationSeconds,
                c.title.substringBefore(" — ").trim(), c.displayArtist, c.duration,
            )
        } ?: candidates.firstOrNull { c ->
            CrossSourceMatcher.normalizeForMatching(c.title.substringBefore(" — ").trim()) ==
                CrossSourceMatcher.normalizeForMatching(cleanTitle) &&
                CrossSourceMatcher.normalizeForMatching(c.displayArtist) ==
                CrossSourceMatcher.normalizeForMatching(artist)
        } ?: return null
        // searchQobuz already registered the album slug under the Qobuz album
        // id, so we can recover the slug for the navigation bridge.
        return QobuzTrackMatch(
            trackId = match.id,
            albumSlug = match.album?.id?.let { qobuzIdRegistry.albumSlugFor(it) },
            artistId = match.artist?.id,
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
                builder.setUri(dashManifestUri(streamUrl))
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
            } else {
                builder.setUri(streamUrl.toUri())
            }
        }

        return builder.build()
    }
}
