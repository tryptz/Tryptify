package tf.monochrome.android.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.api.QobuzTrackMatch
import tf.monochrome.android.data.cache.QobuzStreamUri
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    private val localTrackLocator: LocalTrackLocator,
) {
    private fun normalizeArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.toUri()
        return if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(raw)) else parsed
    }

    // Legacy method for existing Track model. Returns (null, null) when the
    // stream couldn't be resolved — callers must skip instead of feeding an
    // empty MediaItem to ExoPlayer.
    suspend fun resolveMediaItem(track: Track): Pair<MediaItem?, TrackStream?> {
        // On-device copy wins over the stream, whichever screen queued this.
        localFor(
            title = track.title,
            artist = track.displayArtist,
            albumTitle = track.album?.title,
            durationSeconds = track.duration,
            // DownloadManager keys downloads by Track.id, not by appleId.
            catalogTrackId = track.id,
        )?.let { local ->
            return Pair(buildFileMediaItem(track, localUri(local.filePath)), null)
        }

        // Qobuz is its own catalogue, not a TIDAL fallback. Its track ids live
        // in a separate namespace, so handing one to TIDAL's /track/ endpoint
        // either 404s or — worse — streams a *different* recording under this
        // track's title and artwork, with nothing downstream able to tell.
        //
        // PlayerViewModel already re-routes these before calling in, but it is
        // not the only caller: PlaybackService reaches this method for every
        // natural track-end advance, notification/lock-screen skip, playback
        // resumption and next-track preload, and it has no such check. Guarding
        // here covers all of them at once — the same guard HiFiApiClient's
        // getLyrics already applies for the same reason.
        if (qobuzIdRegistry.isQobuzTrack(track.id)) {
            return Pair(qobuzCachedMediaItem(track), null)
        }

        val streamResult = repository.getTrackStream(track.id)
        val trackStream = streamResult.getOrNull()

        // For non-DASH streams the URL must be non-blank — DASH carries its
        // payload inline via base64-encoded MPD and the URL is therefore
        // intentionally empty at this stage; PlaybackService rebuilds the
        // DashMediaSource separately.
        if (trackStream != null && (trackStream.isDash || trackStream.streamUrl.isNotBlank())) {
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

    /**
     * Pre-warm what can *usefully* be pre-warmed for an upcoming queue entry.
     *
     * Only work whose result outlives the call is done here. Qobuz parks the
     * whole file on disk and the on-device lookup is memoised, so both make the
     * eventual play instant. A TIDAL or Apple stream URL is deliberately *not*
     * fetched: it's short-lived, so by the time the track comes round minutes
     * later it would have to be fetched again — the old preload did exactly
     * that and threw the answer away, spending a request (and connection
     * contention) at the precise moment the current track was trying to start.
     *
     * Returns whether this track can also be *pre-queued* — handed to
     * ExoPlayer's playlist now for gapless playback. That's true of exactly the
     * sources warmed here, because they're the ones that resolve to a URI which
     * is still valid minutes later; see [GaplessEligibility].
     */
    suspend fun warmUpcoming(track: Track): Boolean = runCatching {
        val local = localFor(
            title = track.title,
            artist = track.displayArtist,
            albumTitle = track.album?.title,
            durationSeconds = track.duration,
            catalogTrackId = track.id,
        )
        if (local != null) return@runCatching true
        if (qobuzIdRegistry.isQobuzTrack(track.id)) {
            // Kicks the download off and returns; it keeps filling the
            // cache on the manager's own scope, so by the time this track
            // is reached it is already there.
            qobuzCache.openPartial(track.id, AudioQuality.LOSSLESS)
            return@runCatching true
        }
        false
    }.getOrDefault(false)

    // New method for UnifiedTrack
    @OptIn(UnstableApi::class)
    suspend fun resolveUnifiedTrack(track: UnifiedTrack): ResolvedMedia {
        val source = track.source

        // Prefer the on-device copy over any remote source. This sits in the
        // resolver rather than in a screen so it holds for every entry point —
        // search results, album and artist pages, playlists, radio, the queue
        // sheet and notification skips all land here.
        if (source !is PlaybackSource.LocalFile) {
            localFor(
                title = track.title,
                artist = track.artistName,
                albumTitle = track.albumTitle,
                durationSeconds = track.durationSeconds,
                catalogTrackId = when (source) {
                    is PlaybackSource.HiFiApi -> source.tidalId
                    is PlaybackSource.QobuzCached -> source.qobuzId
                    is PlaybackSource.AppleCached -> source.appleId
                    else -> null
                },
                isrc = track.isrc,
                musicBrainzTrackId = track.musicBrainzTrackId,
            )?.let { local ->
                return resolveLocalFile(track, local)
            }
        }

        return when (source) {
            is PlaybackSource.LocalFile -> resolveLocalFile(track, source)
            is PlaybackSource.CollectionDirect -> resolveCollectionDirect(track, source)
            is PlaybackSource.HiFiApi -> resolveHiFiApi(track, source)
            is PlaybackSource.QobuzCached -> resolveQobuzCached(track, source)
            is PlaybackSource.AppleCached -> resolveAppleCached(track, source)
        }
    }

    // Apple resolution = "ask the instance's /api/apple/download-music for the
    // wrapper-resolved manifest, then stream the cloud-cached decrypted file it
    // points at (delivery.streamUrl, Range-capable)". Atmos-flagged tracks request
    // the atmos variant. Not playable when unconfigured / not yet cached upstream.
    private suspend fun resolveAppleCached(
        track: UnifiedTrack,
        source: PlaybackSource.AppleCached,
    ): ResolvedMedia {
        val streamUrl = repository.appleStreamUrl(
            appleId = source.appleId,
            quality = source.preferredQuality,
            atmos = track.isThxSpatialAudio,
        ).getOrNull()

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
            .apply { if (!streamUrl.isNullOrBlank()) setUri(streamUrl.toUri()) }
            .setMediaMetadata(metadata)
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            isPlayable = !streamUrl.isNullOrBlank(),
        )
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
        // Starts the download and returns once the headers are in; the player
        // reads the cache file as it fills. Null still means "can't play this"
        // (Qobuz unconfigured, or the request failed outright).
        val started = qobuzCache.openPartial(source.qobuzId, source.preferredQuality) != null

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
            .apply {
                if (started) {
                    setUri(QobuzStreamUri.build(source.qobuzId, source.preferredQuality))
                }
            }
            .setMediaMetadata(metadata)
            .build()

        return ResolvedMedia(
            mediaItem = mediaItem,
            isLocalFile = true,
            isPlayable = started,
        )
    }

    /**
     * On-device copy of a song that would otherwise stream, or null.
     *
     * Thin wrapper over [LocalTrackLocator] so both resolver entry points ask
     * the same question the same way.
     */
    private suspend fun localFor(
        title: String,
        artist: String,
        albumTitle: String?,
        durationSeconds: Int,
        catalogTrackId: Long?,
        isrc: String? = null,
        musicBrainzTrackId: String? = null,
    ): PlaybackSource.LocalFile? = localTrackLocator.findLocalSource(
        title = title,
        artist = artist,
        albumTitle = albumTitle,
        durationSeconds = durationSeconds,
        catalogTrackId = catalogTrackId,
        isrc = isrc,
        musicBrainzTrackId = musicBrainzTrackId,
    )

    /**
     * A legacy [Track] played from Qobuz — fetched into the cache directory on
     * first play, then played off disk, exactly like [resolveQobuzCached] does
     * for the [UnifiedTrack] path. LOSSLESS matches the default that
     * PlaybackSource.QobuzCached carries.
     *
     * Null when Qobuz isn't configured or the fetch fails; callers already
     * treat a null MediaItem as "skip this track", which is the right outcome —
     * far better than quietly serving someone else's recording from TIDAL.
     */
    private suspend fun qobuzCachedMediaItem(track: Track): MediaItem? {
        val started = runCatching { qobuzCache.openPartial(track.id, AudioQuality.LOSSLESS) }
            .getOrNull() ?: return null
        return buildFileMediaItem(
            track,
            QobuzStreamUri.build(track.id, AudioQuality.LOSSLESS).toUri(),
        ).takeIf { started.failure == null }
    }

    /**
     * A legacy [Track] pointed at a file on disk. The metadata stays the
     * catalogue's — same title, same artwork — so swapping the stream for the
     * file is invisible in the player; only the loading spinner disappears.
     */
    private fun buildFileMediaItem(track: Track, uri: Uri): MediaItem {
        val artworkUri = track.album?.cover?.let { cover -> buildCoverUrl(cover, 640).toUri() }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.displayArtist)
            .setAlbumTitle(track.album?.title)
            .setArtworkUri(artworkUri)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.volumeNumber)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    // DownloadWorker stores filePath either as an absolute filesystem path
    // (internal storage) or as a content:// URI string (when the user picked a
    // SAF folder). Wrapping a content:// path with File(...) + Uri.fromFile
    // produces a malformed file:// URI that ExoPlayer can't open and
    // DefaultMediaSourceFactory NPEs on. Detect the scheme and route
    // accordingly.
    private fun localUri(filePath: String): Uri =
        if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
            filePath.toUri()
        } else {
            Uri.fromFile(File(filePath))
        }

    private fun resolveLocalFile(
        track: UnifiedTrack,
        source: PlaybackSource.LocalFile
    ): ResolvedMedia {
        val uri = localUri(source.filePath)

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

        // DASH carries its MPD inline (PlaybackService rebuilds the source),
        // so an unset URI is fine in that case. Otherwise we need a real URL.
        val isDash = trackStream?.isDash == true
        val isPlayable = trackStream != null &&
            (isDash || trackStream.streamUrl.isNotBlank())

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
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .apply {
                if (trackStream != null && trackStream.streamUrl.isNotBlank() && !trackStream.isDash) {
                    setUri(trackStream.streamUrl.toUri())
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

        runCatching { qobuzCache.openPartial(match.trackId, AudioQuality.LOSSLESS) }
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
            .setUri(QobuzStreamUri.build(match.trackId, AudioQuality.LOSSLESS).toUri())
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

        // DASH has no progressive URL — PlaybackService synthesises a
        // data: URI at play time. For everything else, attach the URL.
        if (!isDash && streamUrl.isNotBlank()) {
            builder.setUri(streamUrl.toUri())
        }

        return builder.build()
    }
}
