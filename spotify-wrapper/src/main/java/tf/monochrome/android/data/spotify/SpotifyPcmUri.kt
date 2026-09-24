package tf.monochrome.android.data.spotify

/** URI contract for Spotify PCM decoded in-process by librespot. */
object SpotifyPcmUri {
    const val SCHEME = "spotify-pcm"

    data class Request(val spotifyUri: String, val durationMs: Long)

    fun build(spotifyUri: String, durationMs: Long): String =
        "$SCHEME://track/${trackIdOf(spotifyUri)}?durationMs=$durationMs"

    fun matches(uri: String): Boolean = uri.startsWith("$SCHEME://")

    fun parse(uri: String): Request? {
        if (!matches(uri)) return null
        val body = uri.removePrefix("$SCHEME://")
        val id = body.substringBefore('?').substringAfterLast('/')
        if (!isTrackId(id)) return null
        val durationMs = body.substringAfter("?durationMs=", "")
            .substringBefore('&')
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        return Request("spotify:track:$id", durationMs)
    }

    fun trackIdOf(spotifyUri: String): String = spotifyUri.substringAfterLast(':')

    fun isTrackId(id: String): Boolean =
        id.length == 22 && id.all { it.isLetterOrDigit() && it.code < 128 }
}
