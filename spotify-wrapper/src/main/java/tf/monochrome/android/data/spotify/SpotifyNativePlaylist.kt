package tf.monochrome.android.data.spotify

/**
 * A playlist as librespot reads it from Spotify's own playlist service —
 * the path the Spotify app uses, which serves any public playlist. The Web
 * API's playlist-items endpoint only serves playlists the user owns or
 * collaborates on to a Development-mode app, so this is the only way to list
 * someone else's.
 */
data class SpotifyNativePlaylist(
    val name: String,
    val description: String?,
    val owner: String?,
    val tracks: List<SpotifyNativeTrack>,
)

/** One track of a [SpotifyNativePlaylist], from its librespot metadata. */
data class SpotifyNativeTrack(
    /** `spotify:track:<base62>`. */
    val uri: String,
    val name: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val explicit: Boolean,
    /** Largest cover on i.scdn.co, if the album has one. */
    val coverUrl: String?,
)
