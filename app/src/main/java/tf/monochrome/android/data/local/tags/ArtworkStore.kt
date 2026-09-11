package tf.monochrome.android.data.local.tags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where extracted cover art lives, and what it is named. Two things about the
 * old store made the library rescan itself on almost every launch.
 *
 * It lived in `cacheDir`, which Android is entitled to empty whenever it wants
 * — and did. Every eviction left Room rows pointing at vanished JPEGs, which
 * the startup check answered with a *full library scan*. It lives in [filesDir]
 * now: app data, which the OS does not reclaim and "Clear cache" does not touch.
 *
 * And it was keyed by `MD5(filePath)`, so a 500-track album wrote the same
 * cover 500 times at whatever size it was embedded at — often 3000x3000. That
 * is what made it worth reclaiming. Art is keyed by the hash of its own encoded
 * bytes now, one file per cover, downscaled to [MAX_EDGE_PX] on the way in.
 *
 * Keys stay absolute paths: the column already holds paths to sidecar covers
 * and raw audio files, and every reader treats it as "a path to an image".
 */
@Singleton
class ArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The durable store. Everything written from now on lands here. */
    val root: File by lazy { File(context.filesDir, ArtworkKeys.DIR_NAME).also { it.mkdirs() } }

    /**
     * Where [ArtworkStoreMigration] parks art carried over from the old
     * cache-keyed store. Same durability as [root]; the separate directory only
     * keeps the two naming schemes tellable apart, since both are `<hex>.jpg`.
     * `needsReRead()` re-reads a row still pointing here, so the next manual
     * rescan compacts it a row at a time.
     */
    val legacyRoot: File by lazy { File(root, ArtworkKeys.LEGACY_DIR_NAME) }

    /**
     * Store [artworkBytes] and return the absolute path for `artworkCacheKey`,
     * or [fallbackPath] if it could not be written. Identical covers collapse
     * onto one file: the name is the hash of the *encoded* bytes, so it
     * describes what is on disk rather than which track was read first.
     */
    fun put(artworkBytes: ByteArray, fallbackPath: String): String {
        return try {
            val encoded = encode(artworkBytes) ?: return fallbackPath
            val name = ArtworkKeys.nameFor(encoded)
            val file = File(root, name)
            if (!file.exists()) {
                // The lazy mkdirs runs once per process and the directory can
                // be gone by now ("Clear storage" mid-process); without this
                // the write fails silently and every track falls back to its
                // raw file path.
                root.mkdirs()
                // Write beside the target and rename in. A half-written JPEG
                // under a content hash is permanent: the name claims those
                // exact bytes, so nothing ever rewrites it and every track
                // sharing that cover renders torn for good.
                val tmp = File(root, "$name.tmp")
                FileOutputStream(tmp).use { it.write(encoded) }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return fallbackPath
                }
            }
            file.absolutePath
        } catch (_: Exception) {
            fallbackPath
        }
    }

    /**
     * Decode, downscale to [MAX_EDGE_PX] on the longest edge, re-encode as JPEG.
     * Null when the bytes are not a decodable image — the one case the caller
     * must read as "no art" rather than a write failure.
     */
    private fun encode(artworkBytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ArtworkKeys.sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        }
        val decoded = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, opts)
            ?: return null

        // inSampleSize only halves, so the result can still be up to twice the
        // target on its longest edge. Land it exactly.
        val scaled = scaleDown(decoded)
        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE_PX) return bitmap
        val ratio = MAX_EDGE_PX.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Delete stored art no row points at any more. Used to be Android's job —
     * the store was a cache, so eviction bounded it. It is app data now, so a
     * deleted album would otherwise leave its cover behind forever.
     *
     * [referencedKeys] is every `artworkCacheKey` in the database, sidecar and
     * raw-file paths included; those simply don't match a file in the store.
     * Returns the number of files deleted.
     */
    fun sweepOrphans(referencedKeys: Collection<String>): Int {
        val keep = referencedKeys.toHashSet()
        var deleted = 0
        for (dir in listOf(root, legacyRoot)) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory) continue
                if (file.absolutePath in keep) continue
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    companion object {
        /**
         * Longest edge of a stored cover. The player hero is the largest
         * surface art is drawn on and remote art is fetched at 640 px for the
         * same surfaces, so this is headroom rather than a target.
         */
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 85
    }
}
