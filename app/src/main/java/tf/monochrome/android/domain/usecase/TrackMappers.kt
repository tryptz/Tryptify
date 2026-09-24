package tf.monochrome.android.domain.usecase

import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.api.SpotifyAlbumFull
import tf.monochrome.android.data.api.SpotifyAlbum
import tf.monochrome.android.data.api.SpotifyArtist
import tf.monochrome.android.data.api.SpotifyArtistFull
import tf.monochrome.android.data.api.SpotifyImage
import tf.monochrome.android.data.api.SpotifySimplePlaylist
import tf.monochrome.android.data.api.SpotifyIdRegistry
import tf.monochrome.android.data.api.SpotifyTrack
import tf.monochrome.android.data.api.spotifyNumericIdFor
import tf.monochrome.android.data.cache.SpotifyShadowUri
import tf.monochrome.android.data.spotify.SpotifyNativeTrack
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.GenreConfidence
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.PlaylistCreator
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedAlbum
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
    releaseYear = album?.releaseDate?.take(4)?.toIntOrNull(),
    artworkUri = coverUrl,
    channelCount = channelCount,
    version = version,
    isThxSpatialAudio = isThxSpatialAudio,
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
    releaseYear = album?.releaseDate?.take(4)?.toIntOrNull(),
    artworkUri = coverUrl,
    channelCount = channelCount,
    version = version,
    isThxSpatialAudio = isThxSpatialAudio,
    // DERIVED, not TAGGED: Qobuz tags the *release*, not the track, so this is
    // the album's genre inherited downward. True often enough to rank on, not
    // reliably enough to state as fact about the track itself.
    genre = album?.genre,
    genreConfidence = album?.genre?.let { GenreConfidence.DERIVED },
    source = PlaybackSource.QobuzCached(qobuzId = id),
    sourceType = SourceType.QOBUZ,
)

/**
 * Pick QobuzCached vs HiFiApi by what the registry knows about this track id,
 * so hearted tracks of either origin play correctly through
 * PlayerViewModel.playUnifiedTrack.
 */
/**
 * An Apple Music catalog track. Same Track shape, tagged APPLE for labelling and
 * dedup. Uses PlaybackSource.AppleCached, which StreamResolver resolves via the
 * instance's /api/apple/download-music (wrapper-resolved manifest + cloud-cached
 * decrypted file). The id prefix keeps Apple hits distinct from Qobuz/TIDAL.
 */
fun Track.toAppleUnifiedTrack(): UnifiedTrack = UnifiedTrack(
    id = "apple_${appleId ?: id}",
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
    releaseYear = album?.releaseDate?.take(4)?.toIntOrNull(),
    artworkUri = coverUrl,
    channelCount = channelCount,
    version = version,
    isThxSpatialAudio = isThxSpatialAudio,
    source = PlaybackSource.AppleCached(appleId = appleId ?: id),
    sourceType = SourceType.APPLE,
)

/**
 * Pick the playback source by what the track itself carries first (appleId is
 * authoritative — Apple and Qobuz share no id namespace), then by what the
 * registry knows about this track id, so hearted tracks of any origin play
 * correctly through PlayerViewModel.playUnifiedTrack.
 */
fun Track.toUnifiedTrackAuto(registry: QobuzIdRegistry): UnifiedTrack = when {
    appleId != null || registry.isAppleTrack(id) -> toAppleUnifiedTrack()
    registry.isQobuzTrack(id) -> toQobuzUnifiedTrack()
    else -> toUnifiedTrack()
}

/**
 * A Spotify catalog track, played by the Spotify app via App Remote
 * ([PlaybackSource.SpotifyRemote]). Null for anything that can't be played
 * that way: local files, episodes, or a missing/malformed track URI.
 *
 * Artist and album ids stay null on purpose — they are base62 Spotify ids,
 * and every artist/album screen here takes a numeric TIDAL/Qobuz id, so a
 * hashed stand-in would open some unrelated catalogue page.
 */
fun SpotifyTrack.toSpotifyUnifiedTrack(): UnifiedTrack? {
    val trackUri = uri ?: return null
    if (isLocal || type != "track" || name.isBlank() || durationMs <= 0) return null
    if (!trackUri.startsWith("spotify:track:") ||
        !SpotifyShadowUri.isTrackId(SpotifyShadowUri.trackIdOf(trackUri))
    ) return null

    val names = artists.map { it.name }.filter { it.isNotBlank() }
    return UnifiedTrack(
        id = "spotify_${SpotifyShadowUri.trackIdOf(trackUri)}",
        title = name,
        durationSeconds = (durationMs / 1000).toInt(),
        trackNumber = trackNumber,
        discNumber = discNumber,
        explicit = explicit,
        artistName = names.joinToString(", ").ifBlank { DEFAULT_ARTIST_NAME },
        artistNames = names,
        albumArtistName = names.firstOrNull(),
        artists = names.map { UnifiedArtistRef(id = null, name = it) },
        albumTitle = album?.name?.takeIf { it.isNotBlank() },
        releaseYear = album?.releaseDate?.take(4)?.toIntOrNull(),
        // Largest image first is Spotify's documented order.
        artworkUri = album?.images?.firstOrNull()?.url?.takeIf { it.isNotBlank() },
        isrc = externalIds?.isrc,
        source = PlaybackSource.SpotifyRemote(spotifyUri = trackUri, durationMs = durationMs),
        sourceType = SourceType.SPOTIFY,
    )
}

/**
 * A Spotify album from search or the album-detail endpoint, mapped the way
 * Qobuz albums are: a [UnifiedAlbum] whose id carries the `spotify_` prefix,
 * so AlbumDetailViewModel can route the click to SpotifyAlbumViewModel's
 * branch. Null for anything without a usable album id.
 */
fun SpotifyAlbumFull.toSpotifyUnifiedAlbum(): UnifiedAlbum? {
    val albumId = id ?: return null
    if (name.isBlank()) return null
    return UnifiedAlbum(
        id = "spotify_$albumId",
        title = name,
        artistName = artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_ARTIST_NAME,
        year = releaseDate?.take(4)?.toIntOrNull(),
        trackCount = totalTracks,
        artworkUri = images.firstOrNull()?.url?.takeIf { it.isNotBlank() },
        sourceType = SourceType.SPOTIFY,
    )
}

/**
 * A track inside a Spotify album (or any Spotify catalog context that already
 * carries the album). Same contract as [toSpotifyUnifiedTrack] but with the
 * album fields filled from the parent album rather than a search fragment.
 */
fun SpotifyTrack.toSpotifyAlbumTrack(album: SpotifyAlbumFull): UnifiedTrack? {
    val trackUri = uri ?: return null
    if (isLocal || type != "track" || name.isBlank() || durationMs <= 0) return null
    if (!trackUri.startsWith("spotify:track:") ||
        !SpotifyShadowUri.isTrackId(SpotifyShadowUri.trackIdOf(trackUri))
    ) return null

    val names = artists.map { it.name }.filter { it.isNotBlank() }
    return UnifiedTrack(
        id = "spotify_${SpotifyShadowUri.trackIdOf(trackUri)}",
        title = name,
        durationSeconds = (durationMs / 1000).toInt(),
        trackNumber = trackNumber,
        discNumber = discNumber,
        explicit = explicit,
        artistName = names.joinToString(", ").ifBlank { DEFAULT_ARTIST_NAME },
        artistNames = names,
        albumArtistName = names.firstOrNull(),
        artists = names.map { UnifiedArtistRef(id = null, name = it) },
        albumTitle = album.name.takeIf { it.isNotBlank() },
        albumId = album.id?.let { "spotify_$it" },
        releaseYear = album.releaseDate?.take(4)?.toIntOrNull(),
        artworkUri = album.images.firstOrNull()?.url?.takeIf { it.isNotBlank() },
        isrc = externalIds?.isrc,
        source = PlaybackSource.SpotifyRemote(spotifyUri = trackUri, durationMs = durationMs),
        sourceType = SourceType.SPOTIFY,
    )
}

// ========== Spotify catalog (domain-model) mappers ==========
//
// The Qobuz path renders search results as domain [Album]/[Artist] rows whose
// ids are numeric route arguments; Spotify's base62 ids can't fit a Long, so
// each Spotify catalog row gets a stable hashed numeric id (see
// [spotifyNumericIdFor]) and the real base62 id is recorded in
// [SpotifyIdRegistry]. AlbumDetailViewModel / StreamResolver consult the
// registry to route these rows back to the Spotify Web API — the same trick
// the Qobuz slug registry and the Apple id sets use.

/** A Spotify album as a catalog [Album], registered under its hashed id. */
/**
 * A playlist track read through librespot, as the Web API track it stands in
 * for — so it goes through [toSpotifyUnifiedTrack]'s checks and comes out
 * playable exactly like a search result.
 */
fun SpotifyNativeTrack.toSpotifyTrack(): SpotifyTrack = SpotifyTrack(
    name = name,
    durationMs = durationMs,
    artists = artists.map { SpotifyArtist(name = it) },
    album = album?.let { title ->
        SpotifyAlbum(name = title, images = listOfNotNull(coverUrl?.let { SpotifyImage(url = it) }))
    },
    type = "track",
    id = SpotifyShadowUri.trackIdOf(uri),
    uri = uri,
    explicit = explicit,
)

/**
 * A Spotify playlist search result as a search-screen [Playlist]. The uuid
 * carries a `spotify_` prefix — as Spotify album ids do — so a tap opens the
 * Spotify playlist page instead of TIDAL's.
 */
fun SpotifySimplePlaylist.toSpotifySearchPlaylist(): Playlist? {
    if (id.isBlank() || name.isBlank()) return null
    return Playlist(
        uuid = "$SPOTIFY_PLAYLIST_PREFIX$id",
        title = name,
        description = description?.takeIf { it.isNotBlank() },
        numberOfTracks = trackCount,
        cover = images?.firstOrNull()?.url?.takeIf { it.isNotBlank() },
        creator = owner?.displayName?.let { PlaylistCreator(name = it) },
    )
}

const val SPOTIFY_PLAYLIST_PREFIX = "spotify_"

fun SpotifyAlbumFull.toSpotifyCatalogAlbum(registry: SpotifyIdRegistry): Album? {
    val base62 = id ?: return null
    if (name.isBlank()) return null
    val numericId = spotifyNumericIdFor(base62)
    registry.registerAlbum(numericId, base62)
    return Album(
        id = numericId,
        title = name,
        artist = artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?.let { Artist(id = 0L, name = it) },
        artists = artists.map { Artist(id = 0L, name = it.name) },
        numberOfTracks = totalTracks.takeIf { it > 0 },
        releaseDate = releaseDate,
        cover = images.firstOrNull()?.url?.takeIf { it.isNotBlank() },
    )
}

/** A track inside a Spotify album, as a catalog [Track] with a hashed id. */
fun SpotifyTrack.toSpotifyCatalogTrack(
    album: SpotifyAlbumFull,
    registry: SpotifyIdRegistry,
): Track? {
    val trackUri = uri ?: return null
    if (isLocal || type != "track" || name.isBlank() || durationMs <= 0) return null
    val base62 = SpotifyShadowUri.trackIdOf(trackUri)
    if (!SpotifyShadowUri.isTrackId(base62)) return null

    val numericId = spotifyNumericIdFor(base62)
    registry.registerTrack(numericId, base62)
    album.id?.let { albumBase62 ->
        registry.registerAlbum(spotifyNumericIdFor(albumBase62), albumBase62)
    }

    val names = artists.map { it.name }.filter { it.isNotBlank() }
    return Track(
        id = numericId,
        title = name,
        duration = (durationMs / 1000).toInt(),
        artist = names.firstOrNull()?.let { Artist(id = 0L, name = it) },
        artists = names.map { Artist(id = 0L, name = it) },
        album = Album(
            id = album.id?.let { spotifyNumericIdFor(it) } ?: 0L,
            title = album.name,
            cover = album.images.firstOrNull()?.url?.takeIf { it.isNotBlank() },
        ),
        explicit = explicit,
        trackNumber = trackNumber,
        volumeNumber = discNumber,
    )
}

/** GET /v1/albums/{id} → a browsable [AlbumDetail] (album + its tracks). */
fun SpotifyAlbumFull.toSpotifyCatalogDetail(
    registry: SpotifyIdRegistry,
): AlbumDetail? {
    val album = toSpotifyCatalogAlbum(registry) ?: return null
    val tracks = tracks?.items.orEmpty().mapNotNull { it.toSpotifyCatalogTrack(this, registry) }
    return AlbumDetail(album = album, tracks = tracks)
}

/** A Spotify artist as a catalog [Artist], registered under its hashed id. */
fun SpotifyArtistFull.toSpotifyCatalogArtist(registry: SpotifyIdRegistry): Artist? {
    val base62 = id ?: return null
    if (name.isBlank()) return null
    val numericId = spotifyNumericIdFor(base62)
    registry.registerArtist(numericId, base62)
    return Artist(
        id = numericId,
        name = name,
        picture = images.firstOrNull()?.url?.takeIf { it.isNotBlank() },
    )
}
