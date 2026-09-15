package tf.monochrome.android.data.local.tags

import java.security.MessageDigest

/**
 * How stored cover art is named, and how to tell the two naming schemes apart.
 *
 * A plain object with no Android types: the unit tests carry JUnit and nothing
 * else, so anything testable has to be reachable without a `Context`. The
 * `Bitmap` work around it lives in [ArtworkStore].
 */
object ArtworkKeys {

    const val DIR_NAME = "artwork"

    /**
     * Where art carried over from the old cache-keyed store is parked. Same
     * durability as the store root; the separate directory only keeps the two
     * schemes tellable apart, since both are `<hex>.jpg`.
     */
    const val LEGACY_DIR_NAME = "legacy"

    /**
     * The file name for a cover, derived from the cover's own bytes — the whole
     * reason the store is small enough for `filesDir`. The old scheme hashed the
     * *audio file's path*, so a 500-track album meant 500 copies of one cover.
     *
     * Truncated to 16 bytes: 128 bits of SHA-256 is well past a collision being
     * worth thinking about, and it keeps names readable in a listing.
     */
    fun nameFor(encodedBytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(encodedBytes)
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return "$hex.jpg"
    }

    /**
     * True for a key still under the pre-move scheme: one unscaled copy per
     * track. Drives a re-read, so the store compacts over the manual rescans
     * the user already runs rather than needing one of its own.
     *
     * Matches the path segment, not a prefix, so it doesn't depend on where the
     * root sits and `.../legacy_backup/` isn't mistaken for it.
     */
    fun isLegacyKey(key: String?): Boolean =
        key != null && key.contains("/$DIR_NAME/$LEGACY_DIR_NAME/")

    /**
     * Repoint a key from [oldDir] to [newDir], leaving anything else alone — the
     * column also holds sidecar covers and raw audio paths, and neither moves.
     *
     * Boundary-aware both ways: a trailing slash on [oldDir] must not change the
     * result, and `/cache/artwork_backup/x.jpg` must not match `/cache/artwork`.
     */
    fun rewriteKey(key: String, oldDir: String, newDir: String): String {
        val from = oldDir.trimEnd('/') + "/"
        val to = newDir.trimEnd('/') + "/"
        return if (key.startsWith(from)) to + key.removePrefix(from) else key
    }

    /**
     * Largest power-of-two downscale that still leaves the image at or above
     * [maxEdgePx] on its longest edge, for `BitmapFactory.Options.inSampleSize`.
     */
    fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
        if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdgePx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}
