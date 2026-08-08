package tf.monochrome.android.data.cache

import tf.monochrome.android.domain.model.AudioQuality

/**
 * The `qobuz://` URI contract between [StreamResolver][tf.monochrome.android.player.StreamResolver],
 * which mints these, and [QobuzPartialDataSource], which serves them.
 *
 * A Qobuz track is handed to ExoPlayer as an identifier rather than as a
 * signed URL or a file path: the signed URL is short-lived, and the file may
 * not exist yet because it is still downloading. Resolving the identifier is
 * deferred to the moment the player actually opens it.
 *
 * Kept free of Android types so the round trip can be unit tested.
 */
object QobuzStreamUri {

    const val SCHEME = "qobuz"

    data class Request(val trackId: Long, val quality: AudioQuality)

    fun build(trackId: Long, quality: AudioQuality): String =
        "$SCHEME://track/$trackId?quality=${quality.name}"

    fun matches(uri: String): Boolean = uri.startsWith("$SCHEME://")

    /**
     * Inverse of [build]. Null when [uri] isn't one of ours or carries no
     * usable track id; an unrecognised quality falls back to LOSSLESS, which
     * is the default every caller mints with anyway.
     */
    fun parse(uri: String): Request? {
        if (!matches(uri)) return null
        val body = uri.removePrefix("$SCHEME://")
        val trackId = body.substringBefore('?').substringAfterLast('/').toLongOrNull() ?: return null
        val quality = body
            .substringAfter("?quality=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { AudioQuality.valueOf(name) }.getOrNull() }
            ?: AudioQuality.LOSSLESS
        return Request(trackId, quality)
    }
}
