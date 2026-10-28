package tf.monochrome.android.data.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.Track
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
    // Deezer identity, captured at enqueue for the same reason as [appleId].
    // Defaulted so a queue persisted before it existed still decodes.
    val deezerId: Long = -1L,
    // Qobuz identity, captured at enqueue for the same reason. Qobuz ids are
    // bare numbers too, and the registry that knows them loads from disk
    // after startup: a queue restored before it has would send them to TIDAL,
    // where the same number is some other recording.
    val isQobuz: Boolean = false,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val albumCover: String? = null,
    val duration: Int = 0,
    val version: String? = null,
    val isThxSpatialAudio: Boolean = false,
    // TIDAL lists a Dolby Atmos mix: with TIDAL Dolby Atmos on, that mix is
    // what downloads, or the download fails rather than save stereo.
    val isDolbyAtmos: Boolean = false,
    // Written into the file's own tags so strict players (Auxio, Symfonium,
    // MediaStore) can order and group the track offline. All defaulted, so a
    // queue persisted before they existed still decodes.
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
) {
    companion object {
        fun from(track: Track, isQobuz: Boolean = false): DownloadItem = DownloadItem(
            trackId = track.id,
            appleId = track.appleId ?: -1L,
            deezerId = track.deezerId ?: -1L,
            isQobuz = isQobuz,
            title = track.title,
            artistName = track.artist?.name
                ?: track.displayArtist.ifBlank { "Unknown Artist" },
            albumTitle = track.album?.title,
            albumCover = track.album?.cover ?: track.coverUrl,
            duration = track.duration,
            version = track.version,
            isThxSpatialAudio = track.isThxSpatialAudio,
            isDolbyAtmos = track.isDolbyAtmos,
            trackNumber = track.trackNumber,
            discNumber = track.volumeNumber,
            albumArtist = track.album?.displayArtist?.ifBlank { null },
            releaseDate = track.album?.releaseDate,
            genre = track.album?.genre,
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
    /** Why a FAILED entry failed, when the downloader said. */
    val error: String? = null,
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
 * The list of tracks is persisted as JSON, so a queue outlives the process:
 * every change to which tracks are queued bumps [saveRevision], and
 * [DownloadManager] writes the list from there. Status and progress
 * deliberately are not saved, since a download that was interrupted restarts
 * from zero anyway.
 */
@Singleton
class DownloadQueue @Inject constructor() {

    private val _entries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val entries: StateFlow<List<QueueEntry>> = _entries.asStateFlow()

    /**
     * Counts the changes to what [savedJson] would write: tracks added,
     * finished, removed or reordered. A status change is not one, so taking
     * the next batch or recording a failure writes nothing.
     *
     * A count rather than the JSON itself, so nothing is encoded until the
     * one writer in [DownloadManager] gets to it. Before, each change encoded
     * the whole list on the caller's thread and launched its own write, and
     * the writes could land in any order: an older queue saved after a newer
     * one was the queue that came back after a restart.
     */
    private val _saveRevision = MutableStateFlow(0L)
    val saveRevision: StateFlow<Long> = _saveRevision.asStateFlow()

    // What each running entry's progress was when last published, and when,
    // so the read loop can report every few kilobytes without the queue
    // emitting that often. Cleared as a transfer starts, so its first report
    // always goes out.
    private class Published(val progress: Float, val at: Long)
    private val progressPublished = ConcurrentHashMap<Long, Published>()

    /** Milliseconds on a clock that only moves forward. Replaceable for tests. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

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
            // The transform runs again if a progress update lands between its
            // read and its write; without this a retry would hand the same
            // track out twice.
            taken.clear()
            var room = max
            current.map { entry ->
                if (room > 0 && entry.status == DownloadStatus.QUEUED) {
                    room--
                    taken += entry.item
                    entry.copy(status = DownloadStatus.DOWNLOADING, progress = 0f)
                } else entry
            }
        }
        taken.forEach { progressPublished.remove(it.trackId) }
        return taken
    }

    /**
     * Moves a running entry's progress bar.
     *
     * The downloader calls this on every read of the body, which is every 8 KB
     * or so: five thousand times for one 40 MB FLAC, each copying the whole
     * queue and waking everything that watches it. So a change is published
     * only once it has moved [PROGRESS_STEP] or [PROGRESS_INTERVAL_MS] has
     * passed since the last one. The first report for a transfer and the
     * finish always go out, and status changes never pass through here.
     *
     * Progress is not persisted: it is meaningless across a restart.
     */
    fun setProgress(trackId: Long, progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val now = clock()
        // Checked against what was last published before the queue is even
        // looked at, so the reads that publish nothing cost next to nothing.
        progressPublished[trackId]?.let { last ->
            if (clamped == last.progress) return
            val small = clamped < 1f &&
                abs(clamped - last.progress) < PROGRESS_STEP &&
                now - last.at < PROGRESS_INTERVAL_MS
            if (small) return
        }
        var applied = false
        _entries.update { current ->
            applied = false
            current.map {
                if (it.item.trackId == trackId && it.status == DownloadStatus.DOWNLOADING) {
                    applied = true
                    it.copy(progress = clamped)
                } else it
            }
        }
        if (applied) progressPublished[trackId] = Published(clamped, now)
    }

    /** Drops a finished entry off the queue entirely. */
    fun complete(trackId: Long) {
        progressPublished.remove(trackId)
        mutate { current -> current.filterNot { it.item.trackId == trackId } }
    }

    /**
     * Records a failure. The entry goes back to QUEUED for another attempt until
     * [MAX_ATTEMPTS] is reached, after which it stays visible as FAILED so it can
     * be retried by hand or dismissed — silently dropping it would leave someone
     * wondering which of fifty tracks never arrived.
     */
    fun fail(trackId: Long, retryable: Boolean, reason: String? = null) {
        progressPublished.remove(trackId)
        mutate { current ->
            current.map { entry ->
                if (entry.item.trackId != trackId) return@map entry
                val attempts = entry.attempts + 1
                if (retryable && attempts < MAX_ATTEMPTS) {
                    entry.copy(status = DownloadStatus.QUEUED, progress = 0f, attempts = attempts)
                } else {
                    entry.copy(status = DownloadStatus.FAILED, progress = 0f, attempts = attempts, error = reason)
                }
            }
        }
    }

    /** Puts a FAILED entry back in line. No-op for anything else. */
    fun retry(trackId: Long) {
        mutate { current ->
            current.map { entry ->
                if (entry.item.trackId == trackId && entry.status == DownloadStatus.FAILED) {
                    entry.copy(status = DownloadStatus.QUEUED, progress = 0f, attempts = 0, error = null)
                } else entry
            }
        }
    }

    fun remove(trackId: Long) {
        val wasRunning = _entries.value.any {
            it.item.trackId == trackId && it.status == DownloadStatus.DOWNLOADING
        }
        progressPublished.remove(trackId)
        mutate { current -> current.filterNot { it.item.trackId == trackId } }
        if (wasRunning) onCancel?.invoke(trackId)
    }

    fun clear() {
        val running = _entries.value
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .map { it.item.trackId }
        progressPublished.clear()
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
    fun hasWork(): Boolean = _entries.value.hasWork()

    /** Suspends until nothing is waiting or running; returns at once when nothing is. */
    suspend fun awaitNoWork() {
        _entries.first { !it.hasWork() }
    }

    fun runningCount(): Int = _entries.value.count { it.status == DownloadStatus.DOWNLOADING }

    /**
     * Puts back the queue a previous process saved.
     *
     * Anything queued in this process before the saved one was read is kept,
     * after the saved tracks: replacing the list outright dropped a download
     * asked for in the first moments after launch. A track in both keeps the
     * entry this process already has, which may be running.
     */
    fun restore(json: String?) {
        if (json.isNullOrBlank()) return
        val items = runCatching { this.json.decodeFromString<List<DownloadItem>>(json) }.getOrNull()
            ?: return
        mutate { current ->
            val live = current.associateBy { it.item.trackId }
            val saved = items.distinctBy { it.trackId }
            val savedIds = saved.mapTo(HashSet()) { it.trackId }
            saved.map { live[it.trackId] ?: QueueEntry(it) } +
                current.filterNot { it.item.trackId in savedIds }
        }
    }

    /** What is saved for the next process: the queued tracks, in order. */
    fun savedJson(): String = encode(_entries.value)

    private fun mutate(transform: (List<QueueEntry>) -> List<QueueEntry>) {
        while (true) {
            val current = _entries.value
            val next = transform(current)
            if (_entries.compareAndSet(current, next)) {
                if (!sameItems(current, next)) _saveRevision.update { it + 1 }
                return
            }
        }
    }

    // By reference, which is all it takes: a status change copies the entry
    // but keeps its item, so only a change to which tracks are queued, or
    // their order, differs here.
    private fun sameItems(a: List<QueueEntry>, b: List<QueueEntry>): Boolean =
        a.size == b.size && a.indices.all { a[it].item === b[it].item }

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

        /** How far a progress bar moves before the move is published: one percent. */
        const val PROGRESS_STEP = 0.01f

        /** How long a smaller move waits before it is published anyway. */
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

private fun List<QueueEntry>.hasWork(): Boolean = any {
    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
}
