package tf.monochrome.android.domain.usecase

import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * Shared catalog `Track` → `UnifiedTrack` mappers.
 *
 * These were originally private to SearchViewModel; they live here so the
 * discovery feed (and any future catalog surface) can reuse the exact same
 * source-tagging without duplicating the conversion.
 */

private const val DEFAULT_ARTIST_NAME = "Unknown Artist"

/**
 * Per-artist credits for a catalog [Track] as [UnifiedArtistRef]s. Uses the full
 * `artists` list when the source populated it (TIDAL tracks, and Qobuz tracks enriched
 * from album credits), else falls back to the single primary `artist`. A 0/blank id
 * maps to null so the UI treats it as a non-navigable name.
 *
 * Public so `Track`-based UI rows (e.g. `TrackItem`) can render the same per-artist
 * links as the `UnifiedTrack` surfaces.
 */
fun Track.uiArtistRefs(): List<UnifiedArtistRef> =
    artists.ifEmpty { listOfNotNull(artist) }
        .map { UnifiedArtistRef(id = it.id.takeIf { id -> id > 0L }, name = it.name) }
        .filter { it.name.isNotBlank() }

/** A TIDAL catalog track. Plays via the streaming (HiFiApi) path. */
fun Track.toUnifiedTrack(): UnifiedTrack = UnifiedTrack(
    id = "api_$id",
    title = title,
    durationSeconds = duration,
    trackNumber = trackNumber,
    discNumber = volumeNumber,
    explicit = explicit,
    artistName = displayArtist.ifBlank { DEFAULT_ARTIST_NAME },
    artistNames = artists.map { it.name }.ifEmpty { listOfNotNull(artist?.name) },
    albumArtistName = artist?.name,
    artistId = artist?.id,
    artists = uiArtistRefs(),
    albumTitle = album?.title,
    albumId = album?.id?.toString(),
    artworkUri = coverUrl,
    channelCount = channelCount,
    source = PlaybackSource.HiFiApi(tidalId = id),
    sourceType = SourceType.API,
)

/**
 * A Qobuz catalog track. Shares the Track shape with TIDAL but is tagged so the
 * UI can label it and so dedup (distinctBy id) doesn't collapse a Qobuz hit onto
 * the same numeric id from TIDAL. Playback fetches the file via /api/download-music
 * into the app cache and ExoPlayer plays from the local file.
 */
fun Track.toQobuzUnifiedTrack(): UnifiedTrack = UnifiedTrack(
    id = "qobuz_$id",
    title = title,
    durationSeconds = duration,
    trackNumber = trackNumber,
    discNumber = volumeNumber,
    explicit = explicit,
    artistName = displayArtist.ifBlank { DEFAULT_ARTIST_NAME },
    artistNames = artists.map { it.name }.ifEmpty { listOfNotNull(artist?.name) },
    albumArtistName = artist?.name,
    artistId = artist?.id,
    artists = uiArtistRefs(),
    albumTitle = album?.title,
    albumId = album?.id?.toString(),
    artworkUri = coverUrl,
    channelCount = channelCount,
    source = PlaybackSource.QobuzCached(qobuzId = id),
    sourceType = SourceType.QOBUZ,
)

/**
 * Pick QobuzCached vs HiFiApi by what the registry knows about this track id,
 * so hearted tracks of either origin play correctly through
 * PlayerViewModel.playUnifiedTrack.
 */
fun Track.toUnifiedTrackAuto(registry: QobuzIdRegistry): UnifiedTrack =
    if (registry.isQobuzTrack(id)) toQobuzUnifiedTrack() else toUnifiedTrack()
