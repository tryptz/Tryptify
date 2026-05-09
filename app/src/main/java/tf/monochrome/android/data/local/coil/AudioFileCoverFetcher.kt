package tf.monochrome.android.data.local.coil

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import java.io.File

/**
 * Coil 3 fetcher that pulls embedded album art directly from an audio file
 * via MediaMetadataRetriever. Activates when the model is a file:// URI
 * pointing at one of the [AUDIO_EXTENSIONS] formats — anything else (cached
 * JPG/PNG/WebP, network URLs, content:// images) falls through to Coil's
 * built-in fetchers.
 *
 * Why this exists: the local-media scanner caches embedded art at scan time,
 * but cache files get evicted, scans miss freshly-downloaded tracks until
 * MediaScanner runs, and some files keep their art only in container atoms
 * that the cache layer hasn't been taught to read. Pointing the player's
 * artwork URI at the audio file itself (and letting this fetcher pull the
 * picture on demand) means the cover is shown whenever the file has one,
 * regardless of cache state.
 */
class AudioFileCoverFetcher(
    private val filePath: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (!File(filePath).exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val bytes = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // Downsample to the size Coil asked for so we don't keep a 4K cover
            // in memory for a 200dp player thumbnail.
            val sized = options.size.let { sz ->
                val w = sz.width.pxOrNull() ?: bitmap.width
                val h = sz.height.pxOrNull() ?: bitmap.height
                if (w >= bitmap.width && h >= bitmap.height) bitmap
                else bitmap.scale(w.coerceAtMost(bitmap.width), h.coerceAtMost(bitmap.height))
            }
            ImageFetchResult(
                image = sized.asImage(),
                isSampled = sized !== bitmap,
                dataSource = DataSource.DISK,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory(@Suppress("unused") private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "file") return null
            val path = data.path ?: return null
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXTENSIONS) return null
            return AudioFileCoverFetcher(path, options)
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus",
            "wav", "wma", "aif", "aiff", "ape", "dsf", "dff",
        )

        fun coil3.size.Dimension.pxOrNull(): Int? = when (this) {
            is coil3.size.Dimension.Pixels -> px
            else -> null
        }
    }
}
