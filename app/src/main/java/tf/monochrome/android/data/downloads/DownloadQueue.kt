package tf.monochrome.android.data.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One track waiting to be, or being, downloaded.
 *
 * Carries everything the download needs, resolved at enqueue time. In
 * particular [appleId] is captured while the source is still known — deriving
 * it later from [trackId] is what used to mis-route Apple tracks with synthetic
 * ids to the Qobuz backend.
 */
@Serializable
data class DownloadItem(
    val trackId: Long,
    val appleId: Long = -1L,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val duration: Int = 0,
    val version: String? = null,
    val isThxSpatialAudio: Boolean = false,
) {
    companion object {
        fun from(track: Track): DownloadItem = DownloadItem(
            trackId = track.id,
            appleId = track.appleId ?: -1L,
            title = track.title,
            artistName = track.artist?.name
                ?: track.displayArtist.ifBlank { "Unknown Artist" },
            albumTitle = track.album?.title,
            albumCover = track.album?.cover ?: track.coverUrl,
            duration = track.duration,
            version = track.version,
            isThxSpatialAudio = track.isThxSpatialAudio,
        )
    }
}

/** A queued download and where it has got to. */
data class QueueEntry(
    val item: DownloadItem,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    /** Attempts made so far; a track is dropped after [DownloadQueue.MAX_ATTEMPTS]. */
    val attempts: Int = 0,
)

/**
 * The download queue: what is waiting, what is running, and how far each has got.
 *
 * Downloads used to be one WorkManager job per track, each of them expedited and
 * each calling `setForeground`. Asking for a handful at once therefore asked the
 * platform for a handful of concurrent foreground services, multiplexed through
 * WorkManager's single [androidx.work.impl.foreground.SystemForegroundService]
 * with a notification id derived as `base + (trackId % 1000)` — which collides
 * outright for any two ids a thousand apart. Queueing an album, never mind fifty
 * tracks, put that machinery under exactly the load it handles worst.
 *
 * So the scheduler now owns one job. This class is the state it works from:
 * order is preserved, [CONCURRENCY] entries run at a time, and everything else
 * waits its turn. Enqueueing a thousand tracks is a thousand rows in a list, not
 * a thousand jobs.
 *
 * The list is persisted as JSON on every mutation, so a queue outlives the
 * process; per-entry progress deliberately is not, since a download that was
 * interrupted restarts from zero anyway.
 */
@Singleton
class DownloadQueue @Inject constructor() {

    private val _entries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val entries: StateFlow<List<QueueEntry>> = _entries.asStateFlow()

    /** Set by [DownloadManager] so mutations persist without this class knowing about storage. */
    @Volatile
    var onChanged: ((String) -> Unit)? = null

    /**
     * Set by [DownloadQueueWorker] so a cancelled entry stops transferring
     * rather than quietly finishing. Dropping the row alone would leave the
     * bytes still arriving and the file landing on disk after the user asked
     * for it to stop.
     */
    @Volatile
    var onCancel: ((Long) -> Unit)? = null

    /** Adds anything not already queued or running, preserving the given order. */
    fun enqueue(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        mutate { current ->
            val known = current.filter { it.status != DownloadStatus.FAILED }
                .mapTo(HashSet()) { it.item.trackId }
            // A failed entry that gets re-requested restarts rather than
            // stacking a second row for the same track.
            val retried = items.mapTo(HashSet()) { it.trackId }
            current.filterNot { it.status == DownloadStatus.FAILED && it.item.trackId in retried } +
                items.filterNot { it.trackId in known }.map { QueueEntry(it) }
        }
    }

    /**
     * The next entries to work on, marked as running.
     *
     * Takes the queue's own order, so an album downloads in track order rather
     * than whatever the scheduler felt like. Returns fewer than [max] — or
     * nothing — when there is nothing left waiting.
     */
    fun takeNext(max: Int): List<DownloadItem> {
        if (max <= 0) return emptyList()
        val taken = mutableListOf<DownloadItem>()
        mutate { current ->
            var room = max
            current.map { entry ->
                if (room > 0 && entry.status == DownloadStatus.QUEUED) {
                    room--
                    taken += entry.item
                    entry.copy(status = DownloadStatus.DOWNLOADING, progress = 0f)
                } else entry
            }
        }
        return taken
    }

    fun setProgress(trackId: Long, progress: Float) {
        // Progress is not persisted — it changes hundreds of times per track and
        // is meaningless across a restart. Update in place without notifying.
        _entries.update { current ->
            current.map {
                if (it.item.trackId == trackId && it.status == DownloadStatus.DOWNLOADING) {
                    it.copy(progress = progress.coerceIn(0f, 1f))
                } else it
            }
        }
    }

    /** Drops a finished entry off the queue entirely. */
    fun complete(trackId: Long) {
        mutate { current -> current.filterNot { it.item.trackId == trackId } }
    }

    /**
     * Records a failure. The entry goes back to QUEUED for another attempt until
     * [MAX_ATTEMPTS] is reached, after which it stays visible as FAILED so it can
     * be retried by hand or dismissed — silently dropping it would leave someone
     * wondering which of fifty tracks never arrived.
     */
    fun fail(trackId: Long, retryable: Boolean) {
        mutate { current ->
            current.map { entry ->
                if (entry.item.trackId != trackId) return@map entry
                val attempts = entry.attempts + 1
                if (retryable && attempts < MAX_ATTEMPTS) {
                    entry.copy(status = DownloadStatus.QUEUED, progress = 0f, attempts = attempts)
                } else {
                    entry.copy(status = DownloadStatus.FAILED, progress = 0f, attempts = attempts)
                }
            }
        }
    }

    /** Puts a FAILED entry back in line. No-op for anything else. */
    fun retry(trackId: Long) {
        mutate { current ->
            current.map { entry ->
                if (entry.item.trackId == trackId && entry.status == DownloadStatus.FAILED) {
                    entry.copy(status = DownloadStatus.QUEUED, progress = 0f, attempts = 0)
                } else entry
            }
        }
    }

    fun remove(trackId: Long) {
        val wasRunning = _entries.value.any {
            it.item.trackId == trackId && it.status == DownloadStatus.DOWNLOADING
        }
        mutate { current -> current.filterNot { it.item.trackId == trackId } }
        if (wasRunning) onCancel?.invoke(trackId)
    }

    fun clear() {
        val running = _entries.value
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .map { it.item.trackId }
        mutate { emptyList() }
        running.forEach { onCancel?.invoke(it) }
    }

    /**
     * Marks anything left DOWNLOADING as waiting again. Called when the worker
     * starts: an entry in that state can only be a leftover from a process that
     * died mid-transfer, and nothing is going to finish it.
     */
    fun requeueOrphans() {
        mutate { current ->
            current.map {
                if (it.status == DownloadStatus.DOWNLOADING) {
                    it.copy(status = DownloadStatus.QUEUED, progress = 0f)
                } else it
            }
        }
    }

    /** True while anything is waiting or running — what keeps the worker alive. */
    fun hasWork(): Boolean = _entries.value.any {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }

    fun runningCount(): Int = _entries.value.count { it.status == DownloadStatus.DOWNLOADING }

    fun restore(json: String?) {
        if (json.isNullOrBlank()) return
        val items = runCatching { this.json.decodeFromString<List<DownloadItem>>(json) }.getOrNull()
            ?: return
        _entries.value = items.map { QueueEntry(it) }
    }

    private fun mutate(transform: (List<QueueEntry>) -> List<QueueEntry>) {
        val updated = _entries.updateAndGet(transform)
        onChanged?.invoke(encode(updated))
    }

    private fun encode(entries: List<QueueEntry>): String =
        runCatching { json.encodeToString(entries.map { it.item }) }.getOrDefault("[]")

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /**
         * How many transfers run at once.
         *
         * Not the same question as how many can be *asked for* — any number can
         * be queued. Three keeps a fast connection busy without splitting it so
         * many ways that every track crawls, and bounds how many temp files and
         * HTTP connections exist at once.
         */
        const val CONCURRENCY = 3

        /** Attempts before a track is left as FAILED rather than retried again. */
        const val MAX_ATTEMPTS = 3
    }
}

private fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
    while (true) {
        val current = value
        val next = transform(current)
        if (compareAndSet(current, next)) return next
    }
}
