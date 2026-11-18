package tf.monochrome.android.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** One entry in a SOFA directory listing — a folder or a .sofa file. */
data class HrtfNode(
    /** Human-readable name (URL-decoded; ".sofa"/trailing "/" stripped). */
    val display: String,
    /** Path segment relative to the current directory, kept URL-encoded. */
    val href: String,
    val isFolder: Boolean,
    /** Size as shown in the index (e.g. "2.3M"), or null (folders/unknown). */
    val size: String? = null,
)

/** Folders and .sofa files at one directory level. */
data class HrtfListing(val folders: List<HrtfNode>, val files: List<HrtfNode>)

/**
 * SofaHrtfApi — browses the public SOFA HRTF collection at sofacoustics.org,
 * the community mirror of dozens of HRTF databases (CIPIC, MIT KEMAR, HUTUBS,
 * LISTEN, ARI, …). Mirrors [HeadphoneAutoEqApi]'s role for headphones: it walks
 * a remote tree of measurement files the user can download and apply.
 *
 * The host serves plain Apache directory indexes (HTML), so this parses `href`
 * links rather than a JSON API — folders end in "/", HRTFs end in ".sofa".
 * Browsing is lazy (one level per request); the root database list is cached.
 */
class SofaHrtfApi {
    companion object {
        private const val TAG = "SofaHrtfApi"
        const val BASE = "https://sofacoustics.org/data/database/"
        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)
        private val HREF = Regex("""href="([^"?][^"]*)"""", RegexOption.IGNORE_CASE)
        // The index's size cell, after the date's: "1.1M", "861K", "12345".
        private val SIZE = Regex("""<td[^>]*>\s*(\d+(?:\.\d+)?[KMG]?)\s*</td>""")

        /**
         * The folders of the collection that hold rooms the headphone render
         * can play in, and which of their files are those rooms ([isRoom]).
         *
         * A room here is a real room heard by a head: a dummy head's or a
         * person's two ears (a BRIR), with the room's decay in it. Every
         * .sofa of the twelve folders sofaconventions.org lists as room
         * impulse responses was opened and measured on 2026-11-18: of 4,983
         * files, 47 are that. 4,911 are microphone arrays (Eigenmike and
         * Zylia spheres, ambisonics, linear and seven-microphone arrays),
         * whose two outermost microphones the reader would have taken for
         * ears, and 25 are free-field HRIR sets with no room in them. All
         * twelve used to be listed, file for file, and most taps ended in
         * "this file holds no room" or in a room that was not one.
         *
         * Two more folders hold head rooms and are left out because a phone
         * cannot take them: sbsbrir (15 files, about 950 MB each) and tuburo
         * (7 `BRIR_` files of 1.6 to 3.1 GB). The reader loads a whole file
         * to use its first measurement. They come back when it can read one
         * measurement of a file.
         */
        val ROOM_FOLDERS = setOf("oldenburg", "thk")

        /**
         * Whether the file [name] (without `.sofa`) of [folder] (lower case)
         * is a room, by the survey above: in thk the `BRIR_` files (a KU100
         * in four rooms; its `DRIR_` files are sphere arrays, the rest HRIR
         * sets), in oldenburg all but the anechoic set.
         */
        fun isRoom(folder: String, name: String): Boolean = when (folder) {
            "thk" -> name.startsWith("BRIR_")
            "oldenburg" -> !name.contains("anechoic", ignoreCase = true)
            else -> false
        }

        /** An index's size text ("9.6M", "861K", "1.2G", "12345") in bytes, or null when it is not one. */
        fun sizeBytes(text: String?): Long? {
            val m = Regex("""^(\d+(?:\.\d+)?)([KMG]?)$""").find(text?.trim().orEmpty()) ?: return null
            val unit = when (m.groupValues[2]) { "K" -> 1024.0; "M" -> 1024.0 * 1024; "G" -> 1024.0 * 1024 * 1024; else -> 1.0 }
            return (m.groupValues[1].toDouble() * unit).toLong()
        }

        /** The most a .sofa saved to a file may be: the finest sets in the collection are under 200 MB. */
        const val MAX_SOFA_FILE_BYTES = 512L * 1024 * 1024

        /** Storage left free beside a download. */
        private const val SPARE_BYTES = 64L * 1024 * 1024
    }

    private var rootCache: HrtfListing? = null
    private var rootCacheTime = 0L

    /**
     * Lists one directory. [path] is relative to [BASE] and already URL-encoded
     * (built from previous listings' [HrtfNode.href]); "" is the root database
     * list.
     */
    suspend fun list(path: String): Result<HrtfListing> = withContext(Dispatchers.IO) {
        if (path.isEmpty()) rootCache?.let {
            if (System.currentTimeMillis() - rootCacheTime < CACHE_TTL_MS) return@withContext Result.success(it)
        }
        try {
            val html = get(BASE + path)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext Result.failure(Exception("empty index"))
            val folders = ArrayList<HrtfNode>()
            val files = ArrayList<HrtfNode>()
            // Apache's index lists one entry per line; the size (e.g. "2.3M") is
            // the last token on a file's line, so parse line-by-line to keep it.
            for (line in html.lineSequence()) {
                val m = HREF.find(line) ?: continue
                val href = m.groupValues[1]
                // Skip absolute links, the parent link and Apache sort headers.
                if (href.startsWith("/") || href.contains("://") || href == "../") continue
                when {
                    href.endsWith("/") ->
                        folders += HrtfNode(decode(href.dropLast(1)), href, true)
                    href.endsWith(".sofa", ignoreCase = true) ->
                        files += HrtfNode(decode(href).removeSuffix(".sofa"), href, false,
                            size = SIZE.findAll(line.substring(m.range.last)).lastOrNull()?.groupValues?.get(1))
                }
            }
            folders.sortBy { it.display.lowercase() }
            files.sortBy { it.display.lowercase() }
            val listing = HrtfListing(folders, files)
            if (path.isEmpty()) { rootCache = listing; rootCacheTime = System.currentTimeMillis() }
            Result.success(listing)
        } catch (e: Exception) {
            Log.e(TAG, "list('$path') failed", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a .sofa straight into [dest], never through memory: an HRTF
     * measured every degree is 160 MB and more, and it is the kind worth
     * having, since each of the room's speakers then plays the file's own
     * measurement (`render/sofa_set.h`). [path] is [BASE]-relative (encoded).
     * [onProgress] is told the bytes so far and the file's size (0 when the
     * server does not say). It is written beside [dest] and moved into place
     * when whole, so a download that stops leaves nothing that looks like a
     * file. Refused over [MAX_SOFA_FILE_BYTES], or when the storage has no
     * room for it. The bytes written, on success.
     */
    suspend fun downloadTo(
        path: String,
        dest: java.io.File,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result<Long> = withContext(Dispatchers.IO) {
        val part = java.io.File(dest.parentFile, dest.name + ".part")
        try {
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Tryptify")
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("Server returned ${conn.responseCode}"))
            }
            val declared = conn.contentLengthLong
            val mb = 1024L * 1024L
            if (declared > MAX_SOFA_FILE_BYTES) {
                return@withContext Result.failure(
                    Exception("File is ${declared / mb} MB — too large (max ${MAX_SOFA_FILE_BYTES / mb} MB).")
                )
            }
            dest.parentFile?.mkdirs()
            val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (declared > 0 && free < declared + SPARE_BYTES) {
                return@withContext Result.failure(
                    Exception("Not enough storage: the file is ${declared / mb} MB and ${free / mb} MB is free.")
                )
            }
            var total = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var told = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SOFA_FILE_BYTES) {
                            return@withContext Result.failure(
                                Exception("File exceeds ${MAX_SOFA_FILE_BYTES / mb} MB — too large.")
                            )
                        }
                        out.write(buf, 0, n)
                        if (total - told >= mb) {
                            told = total
                            onProgress(total, declared.coerceAtLeast(0L))
                        }
                    }
                }
            }
            if (total == 0L || (declared > 0 && total != declared)) {
                return@withContext Result.failure(Exception("Download was cut short"))
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) return@withContext Result.failure(Exception("Couldn't save the file"))
            Result.success(total)
        } catch (e: Exception) {
            Log.e(TAG, "downloadTo('$path') failed", e)
            Result.failure(e)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun get(url: String): java.io.InputStream? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Tryptify")
        }
        return if (conn.responseCode == 200) conn.inputStream else {
            Log.e(TAG, "GET $url -> ${conn.responseCode}"); null
        }
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
