package tf.monochrome.android.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.domain.model.AudioQuality
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qobuz playback cache.
 *
 * Downloads the audio file for a (trackId, quality) pair into the app cache
 * directory and keeps it there, so a second play is instant and works offline.
 * Bounded by total size with mtime-LRU eviction.
 *
 * The download is readable while it runs: [openPartial] returns as soon as the
 * response headers are in, and playback reads the growing file behind the
 * writer. Waiting for the complete file — which is what this used to do — put
 * seconds of silence in front of every Qobuz track.
 *
 * The signed URL is still fetched once and read once, top to bottom, so the
 * time-bounded `etsp` signature is never re-presented; that was the original
 * reason for pre-fetching, and streaming the single response preserves it. What
 * changes is only that the bytes are handed to the player as they land instead
 * of after the last one.
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

    // Downloads in flight, so a second reader of the same track rides along
    // with the first instead of starting its own.
    private val inFlight = HashMap<String, PartialStream>()

    // Downloads outlive the caller on purpose: skipping a track part-way
    // through still leaves a complete file in the cache for next time.
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Begin — or join — the download for [qobuzId] at [quality] and return a
     * handle that can be read while it is still being written.
     *
     * Returns once the response headers are in, not once the file is complete,
     * so the caller can start playing on the first bytes. Two callers asking
     * for the same track share one download. Returns null when the Qobuz
     * instance isn't configured or the request fails outright — callers should
     * treat null as "playback unavailable".
     *
     * The download itself runs on this singleton's own scope, so it keeps
     * filling the cache even if the caller goes away: a track that was skipped
     * part-way through is still there, complete, next time.
     */
    suspend fun openPartial(qobuzId: Long, quality: AudioQuality): PartialStream? {
        val key = cacheKey(qobuzId, quality)
        val target = File(cacheDir, "$key.bin")

        if (isComplete(target)) {
            target.setLastModified(System.currentTimeMillis())
            return completedStream(target)
        }

        val lock = locksMutex.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            // Re-check under the lock: another caller may have finished, or may
            // already have a download in flight we should ride along with.
            if (isComplete(target)) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock completedStream(target)
            }
            inFlight[key]?.takeIf { it.failure == null && !it.isCancelled }
                ?.let { return@withLock it }

            val url = withContext(Dispatchers.IO) {
                runCatching { apiClient.getQobuzDownloadUrl(qobuzId, quality) }.getOrNull()
            } ?: return@withLock null

            withContext(Dispatchers.IO) { evictIfNeeded() }
            val part = File(cacheDir, "$key.bin.part").also { if (it.exists()) it.delete() }
            val stream = PartialStream(part)
            inFlight[key] = stream
            downloadScope.launch { download(url, stream, target, key) }
            stream
        }
    }

    /**
     * Fetches [url] into [stream]'s part file, publishing progress as it goes,
     * and installs it at [target] when every byte has landed.
     */
    private suspend fun download(url: String, stream: PartialStream, target: File, key: String) {
        val part = stream.file
        try {
            // prepareGet + execute is the STREAMING request shape. A plain
            // httpClient.get() in Ktor 3 saves the entire response body into
            // a byte array before handing it over (SavedCall) — for a full
            // FLAC that's a 30-60 MB heap allocation, which OOM-crashed the
            // app on devices already near the 256 MB art heap limit.
            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw IOException("Qobuz fetch failed: ${response.status}")
                }
                response.contentLength()?.takeIf { it > 0 }?.let { stream.setTotalBytes(it) }

                val buffer = ByteArray(BUFFER_BYTES)
                var written = 0L
                part.outputStream().use { out ->
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        // Flush before publishing: a reader must never be told
                        // about bytes that are still sitting in the stream's
                        // buffer and would read back as a short file.
                        out.flush()
                        stream.publish(written)
                    }
                }

                // Atomic install. If rename fails (e.g. cross-mount), fall back
                // to a streamed copy — never a whole-file byte array. Readers
                // hold descriptors on the part file, which stay valid either
                // way; the copy path just leaves the part file until cleanup.
                val installed = if (part.renameTo(target)) {
                    target
                } else {
                    part.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                }
                installed.setLastModified(System.currentTimeMillis())
                stream.complete(written, installed)
            }
        } catch (e: Exception) {
            part.delete()
            stream.fail(e as? IOException ?: IOException(e))
        } finally {
            locksMutex.withLock {
                if (inFlight[key] === stream) inFlight.remove(key)
                locks.remove(key)
            }
        }
    }

    private fun completedStream(target: File): PartialStream =
        PartialStream(target).apply { complete(target.length(), target) }

    /**
     * Returns the local cache file for [qobuzId] at [quality], fetching it over
     * the network on first request and suspending until every byte is on disk.
     *
     * Playback does not use this — it streams via [openPartial]. This is for
     * the callers that genuinely need a whole file: handing the audio to
     * another app via the share sheet, and download-on-demand.
     */
    suspend fun getOrFetch(qobuzId: Long, quality: AudioQuality): File? {
        val stream = openPartial(qobuzId, quality) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { stream.awaitCompletion() }.getOrNull()
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
