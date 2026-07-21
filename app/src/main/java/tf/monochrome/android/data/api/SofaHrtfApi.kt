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
        // A trailing Apache size token: digits with an optional K/M/G suffix.
        private val SIZE = Regex("""\s\d+(\.\d+)?[KMG]?\s*$""")
        // Useful HRTF sets for this renderer are a few MB; anything past this is a
        // dense-grid / BRIR set that would OOM the heap and downsample away anyway.
        private const val MAX_SOFA_BYTES = 48L * 1024 * 1024
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
                            size = SIZE.find(line.substring(m.range.last))?.value?.trim())
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
     * Downloads a .sofa file's bytes. [path] is [BASE]-relative (encoded).
     *
     * Hard-capped at [MAX_SOFA_BYTES]: some databases hold hundreds-of-MB files
     * (dense grids, BRIR sets) that OOM the heap and are useless here anyway —
     * the loader resamples every HRTF to a fixed 36x6 grid. The cap is checked
     * from Content-Length up front and enforced during the read (the header can
     * be absent or wrong).
     */
    suspend fun download(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
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
            if (declared > MAX_SOFA_BYTES) {
                return@withContext Result.failure(
                    Exception("File is ${declared / (1024 * 1024)} MB — too large (max ${MAX_SOFA_BYTES / (1024 * 1024)} MB). Pick a smaller HRTF set.")
                )
            }
            val out = java.io.ByteArrayOutputStream(
                if (declared in 1..MAX_SOFA_BYTES) declared.toInt() else 1 shl 20
            )
            conn.inputStream.use { input ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOFA_BYTES) {
                        return@withContext Result.failure(
                            Exception("File exceeds ${MAX_SOFA_BYTES / (1024 * 1024)} MB — too large. Pick a smaller HRTF set.")
                        )
                    }
                    out.write(buf, 0, n)
                }
            }
            Result.success(out.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "download('$path') failed", e)
            Result.failure(e)
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
