package tf.monochrome.android.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.domain.model.AudioQuality
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qobuz playback cache.
 *
 * Pre-fetches the full audio file via HiFiApiClient.getQobuzDownloadUrl on the
 * first request for a (trackId, quality) pair, writes it atomically into the
 * app cache directory, and serves the same File on subsequent requests so
 * ExoPlayer can play from local disk. Cache is bounded by total size with
 * mtime-LRU eviction.
 *
 * Why pre-fetch instead of progressive streaming: the URL the backend returns
 * is HMAC-signed with a time-bounded `etsp` parameter, so a long-running
 * progressive read can race the signature window. Downloading the whole file
 * up front is more reliable, and the second play is instant since it serves
 * from disk.
 */
@Singleton
class QobuzStreamCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val apiClient: HiFiApiClient,
) {
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { if (!it.exists()) it.mkdirs() }
    }

    // Per-key mutex map so concurrent plays of the same track don't double-fetch.
    // Guarded by [locksMutex] so the map itself isn't a race.
    private val locks = HashMap<String, Mutex>()
    private val locksMutex = Mutex()

    /**
     * Returns the local cache file for [qobuzId] at [quality], fetching it
     * over the network on first request. Returns null when the Qobuz instance
     * isn't configured or the network fetch fails — callers should treat null
     * as "playback unavailable" and surface an error.
     *
     * Suspends until the file is fully written; expect this to take seconds
     * for typical FLAC payloads. Caller should drive this from the IO
     * dispatcher (we already withContext(IO) internally for the file work).
     */
    suspend fun getOrFetch(qobuzId: Long, quality: AudioQuality): File? = withContext(Dispatchers.IO) {
        val key = cacheKey(qobuzId, quality)
        val target = File(cacheDir, "$key.bin")
        if (isComplete(target)) {
            // Touch for LRU; ignore failures, mtime is just a hint.
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }

        val lock = locksMutex.withLock { locks.getOrPut(key) { Mutex() } }
        try {
            lock.withLock {
                // Re-check inside the lock — another caller may have completed
                // the fetch while we were waiting.
                if (isComplete(target)) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withLock target
                }
                fetchInto(qobuzId, quality, target)
            }
        } finally {
            locksMutex.withLock { locks.remove(key) }
        }
    }

    private suspend fun fetchInto(qobuzId: Long, quality: AudioQuality, target: File): File? {
        val url = apiClient.getQobuzDownloadUrl(qobuzId, quality) ?: return null
        evictIfNeeded()

        val temp = File(cacheDir, "${target.name}.tmp").also { if (it.exists()) it.delete() }
        return try {
            // prepareGet + execute is the STREAMING request shape. A plain
            // httpClient.get() in Ktor 3 saves the entire response body into
            // a byte array before handing it over (SavedCall) — for a full
            // FLAC that's a 30-60 MB heap allocation, which OOM-crashed the
            // app on devices already near the 256 MB art heap limit.
            val fetched = httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) return@execute false
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_BYTES)
                temp.outputStream().use { out ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
                true
            }
            if (!fetched) {
                temp.delete()
                return null
            }
            // Atomic install. If rename fails (e.g. cross-mount), fall back to
            // a streamed copy — never a whole-file byte array.
            if (!temp.renameTo(target)) {
                temp.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            target
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    /**
     * Synchronous lookup that returns the cached file if it exists for
     * (qobuzId, quality), without triggering a network fetch. Used by the
     * share helper so it can hand the FLAC out to other apps when the user
     * has already played the track once.
     */
    fun peekCached(qobuzId: Long, quality: AudioQuality): File? {
        val target = File(cacheDir, "${cacheKey(qobuzId, quality)}.bin")
        return if (isComplete(target)) target else null
    }

    /** Trim the cache to [MAX_BYTES] by deleting the oldest .bin entries. */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total < MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total < MAX_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun isComplete(file: File): Boolean = file.exists() && file.length() > 0

    private fun cacheKey(qobuzId: Long, quality: AudioQuality): String = "${qobuzId}_${quality.name}"

    companion object {
        private const val CACHE_DIR_NAME = "qobuz_stream"
        private const val BUFFER_BYTES = 16 * 1024
        // 1 GiB ceiling matches what most music apps spend on transient
        // playback caches; the OS may also evict under storage pressure since
        // we live under context.cacheDir.
        private const val MAX_BYTES: Long = 1L * 1024 * 1024 * 1024
    }
}
