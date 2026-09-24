package tf.monochrome.android.data.cache

/**
 * The `spotify-shadow://` URI contract between
 * [StreamResolver][tf.monochrome.android.player.StreamResolver], which mints
 * these, and [SilentWavDataSource], which serves them.
 *
 * A Spotify track's audio never passes through this app: the Spotify app plays
 * it, driven over App Remote by [SpotifyPlaybackBridge][tf.monochrome.android.player.SpotifyPlaybackBridge].
 * ExoPlayer still needs *something* to play so it stays the one owner of queue
 * position, play state, seeking and end-of-track — the notification, lock
 * screen, scrubber and auto-advance all read from it. What it plays is this: a
 * synthetic, silent WAV exactly as long as the Spotify track, carrying the
 * Spotify URI so the bridge knows what to mirror.
 *
 * Kept free of Android types so the round trip can be unit tested.
 */
object SpotifyShadowUri {

    const val SCHEME = "spotify-shadow"

    data class Request(val spotifyUri: String, val durationMs: Long)

    /**
     * [spotifyUri] is a `spotify:track:<base62>` URI. Only the id travels in
     * the path — base62 is URI-safe, so no encoding is needed either way.
     */
    fun build(spotifyUri: String, durationMs: Long): String =
        "$SCHEME://track/${trackIdOf(spotifyUri)}?durationMs=$durationMs"

    fun matches(uri: String): Boolean = uri.startsWith("$SCHEME://")

    /** Inverse of [build]. Null when [uri] isn't one of ours or is missing a usable id or duration. */
    fun parse(uri: String): Request? {
        if (!matches(uri)) return null
        val body = uri.removePrefix("$SCHEME://")
        val id = body.substringBefore('?').substringAfterLast('/')
        if (!isTrackId(id)) return null
        val durationMs = body
            .substringAfter("?durationMs=", missingDelimiterValue = "")
            .substringBefore('&')
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        return Request("spotify:track:$id", durationMs)
    }

    /** The base62 id of a `spotify:track:` URI, or the input itself when it is already a bare id. */
    fun trackIdOf(spotifyUri: String): String = spotifyUri.substringAfterLast(':')

    /** Spotify track ids are 22 base62 characters. */
    fun isTrackId(id: String): Boolean =
        id.length == 22 && id.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
}
