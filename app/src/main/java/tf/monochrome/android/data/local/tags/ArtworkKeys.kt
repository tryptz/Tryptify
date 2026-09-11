package tf.monochrome.android.data.local.tags

import java.security.MessageDigest

/**
 * How stored cover art is named, and how to tell the two naming schemes apart.
 *
 * Deliberately a plain object with no Android types in it: the unit test source
 * set carries JUnit and nothing else — no Robolectric, no mocking framework — so
 * anything that has to be tested has to be reachable without a `Context`. The
 * `Bitmap` work that surrounds this lives in [ArtworkStore].
 */
object ArtworkKeys {

    const val DIR_NAME = "artwork"

    /**
     * Where art carried over from the old cache-keyed store is parked. Same
     * durability as the store root; the separate directory exists only so the
     * two schemes stay tellable apart, since after the move both are otherwise
     * just `<hex>.jpg`.
     */
    const val LEGACY_DIR_NAME = "legacy"

    /**
     * The file name for a cover, derived from the cover's own bytes.
     *
     * This is the whole reason the store is small enough to keep in `filesDir`.
     * The old scheme hashed the *audio file's path*, so every track on an album
     * wrote its own copy of one identical cover — a 500-track album meant 500
     * files. Keyed by content, an album is one file however many tracks carry it.
     *
     * Truncated to 16 bytes: 128 bits of SHA-256 is far past the point where a
     * collision between two covers on one device is worth thinking about, and it
     * keeps the names short enough to read in a directory listing.
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
     * track file. Drives a re-read, so the store compacts itself over the manual
     * rescans the user already runs rather than needing one of its own.
     *
     * Matches the path segment rather than a prefix, so it does not depend on
     * where the store root sits and a sibling directory that merely starts the
     * same way (`.../legacy_backup/`) is not mistaken for it.
     */
    fun isLegacyKey(key: String?): Boolean =
        key != null && key.contains("/$DIR_NAME/$LEGACY_DIR_NAME/")

    /**
     * Repoint a key from [oldDir] to [newDir], leaving anything not under
     * [oldDir] alone — the column also holds sidecar covers on external storage
     * and raw audio paths as a fallback, and neither should move.
     *
     * Directory-boundary aware in both directions: a trailing slash on [oldDir]
     * must not change the result, and `/cache/artwork_backup/x.jpg` must not be
     * rewritten by an [oldDir] of `/cache/artwork`.
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
