package tf.monochrome.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal Spotify Web API / Accounts service DTOs — only the fields the
 * playlist importer consumes. The shared Json is configured with
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
data class SpotifyArtist(val name: String = "")

@Serializable
data class SpotifyAlbum(val name: String = "")

@Serializable
data class SpotifyTrack(
    val name: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    @SerialName("is_local") val isLocal: Boolean = false,
    // "track" or "episode" — playlists can contain podcast episodes.
    val type: String = "track",
)

/** Item of /v1/playlists/{id}/tracks — track is null for removed/unavailable entries. */
@Serializable
data class SpotifyPlaylistTrackItem(
    val track: SpotifyTrack? = null,
)

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
