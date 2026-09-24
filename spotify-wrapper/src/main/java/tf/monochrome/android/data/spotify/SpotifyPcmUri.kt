package tf.monochrome.android.data.spotify

import tf.monochrome.android.data.cache.SpotifyShadowUri

/**
 * The `spotify-pcm://` URI contract between StreamResolver, which mints these
 * when the in-process librespot client is signed in, and [PcmSinkDataSource],
 * which serves them. Same shape as [SpotifyShadowUri] — the id and the
 * song's length, which the data source needs to write a WAV header — but a
 * different scheme, because what sits behind it is the real audio rather than
 * silence mirrored to the Spotify app. SpotifyPlaybackBridge only reacts to
 * the shadow scheme, so these never engage it.
 */
object SpotifyPcmUri {

    const val SCHEME = "spotify-pcm"

    fun build(spotifyUri: String, durationMs: Long): String =
        "$SCHEME://track/${SpotifyShadowUri.trackIdOf(spotifyUri)}?durationMs=$durationMs"

    fun matches(uri: String): Boolean = uri.startsWith("$SCHEME://")

    /** Inverse of [build]; null when [uri] isn't one of ours or lacks a usable id or duration. */
    fun parse(uri: String): SpotifyShadowUri.Request? {
        if (!matches(uri)) return null
        // Same body as a shadow URI, so its parser does the work.
        return SpotifyShadowUri.parse(SpotifyShadowUri.SCHEME + uri.removePrefix(SCHEME))
    }
}
