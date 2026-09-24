package tf.monochrome.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal Spotify Web API / Accounts service DTOs — only the fields the
 * playlist importer and track search consume. The shared Json is configured with
 * ignoreUnknownKeys, so everything else in Spotify's responses is dropped.
 */

@Serializable
data class SpotifyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Int = 3600,
    // Absent on refresh responses when Spotify chooses not to rotate —
    // callers must keep the previous refresh token in that case.
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

@Serializable
data class SpotifyErrorBody(
    val error: SpotifyErrorDetail? = null,
)

@Serializable
data class SpotifyErrorDetail(
    val status: Int = 0,
    val message: String? = null,
)

@Serializable
data class SpotifyAuthError(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class SpotifyPagingObject<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class SpotifyUserProfile(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SpotifyArtist(
    val name: String = "",
    val id: String? = null,
)

@Serializable
data class SpotifyImage(
    val url: String = "",
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SpotifyAlbum(
    val name: String = "",
    val id: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    // "2021", "2021-06" or "2021-06-25" depending on release_date_precision.
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class SpotifyExternalIds(val isrc: String? = null)

@Serializable
data class SpotifyTrack(
    val name: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    @SerialName("is_local") val isLocal: Boolean = false,
    // "track" or "episode" — playlists can contain podcast episodes.
    val type: String = "track",
    // Search-only fields; the playlist importer's `fields=` filter omits them.
    val id: String? = null,
    val uri: String? = null,
    val explicit: Boolean = false,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    @SerialName("external_ids") val externalIds: SpotifyExternalIds? = null,
)

/** GET /v1/search?type=track — only the tracks section is requested. */
@Serializable
data class SpotifySearchResponse(
    val tracks: SpotifyPagingObject<SpotifyTrack>? = null,
)

/** GET /v1/search?type=album — the albums section of a multi-type search. */
@Serializable
data class SpotifyAlbumSearchResponse(
    val albums: SpotifyPagingObject<SpotifyAlbumFull>? = null,
)

/** GET /v1/search?type=artist — the artists section of a multi-type search. */
@Serializable
data class SpotifyArtistSearchResponse(
    val artists: SpotifyPagingObject<SpotifyArtistFull>? = null,
)

/** Album as returned by search and by /v1/albums/{id}. */
@Serializable
data class SpotifyAlbumFull(
    val name: String = "",
    val id: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    val artists: List<SpotifyArtist> = emptyList(),
    // Album-detail only; absent ("" on some shapes) in search results.
    @SerialName("total_tracks") val totalTracks: Int = 0,
    val tracks: SpotifyPagingObject<SpotifyTrack>? = null,
)

@Serializable
data class SpotifyArtistFull(
    val name: String = "",
    val id: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val genres: List<String> = emptyList(),
)

/**
 * Item of /v1/playlists/{id}/items. The Feb 2026 Web API migration renamed
 * the wrapper field `track` → `item`; both are kept so old-shaped responses
 * still parse. Null for removed/unavailable entries.
 */
@Serializable
data class SpotifyPlaylistTrackItem(
    val item: SpotifyTrack? = null,
    val track: SpotifyTrack? = null,
) {
    val trackOrItem: SpotifyTrack? get() = item ?: track
}

/** Item of /v1/me/tracks (Liked Songs). */
@Serializable
data class SpotifySavedTrackItem(
    val track: SpotifyTrack? = null,
)

/** GET /v1/playlists/{id}?fields=id,name,description */
@Serializable
data class SpotifyPlaylistMeta(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
)

@Serializable
data class SpotifyPlaylistOwner(
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SpotifyPlaylistTracksRef(
    val total: Int = 0,
)

/** Item of /v1/me/playlists. */
@Serializable
data class SpotifySimplePlaylist(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val owner: SpotifyPlaylistOwner? = null,
    val tracks: SpotifyPlaylistTracksRef? = null,
)
