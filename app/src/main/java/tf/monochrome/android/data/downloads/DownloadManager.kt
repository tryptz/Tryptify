package tf.monochrome.android.data.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadStatus {
    IDLE, QUEUED, DOWNLOADING, COMPLETED, FAILED
}

data class TrackDownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f
)

/** A single in-flight download with the metadata needed to render it (pill / monitor). */
data class ActiveDownload(
    val trackId: Long,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val status: DownloadStatus,
    val progress: Float,
    val isThxSpatialAudio: Boolean = false,
)

/**
 * The app's download entry point: hand it tracks, ask it what's happening.
 *
 * It owns a [DownloadQueue] and exactly one WorkManager job to drain it. Any
 * number of tracks can be queued — an album, a playlist, fifty at once — and
 * [DownloadQueue.CONCURRENCY] of them transfer at a time. Queueing more work
 * while the job is already running just appends to the list; the running job
 * picks it up on its next pass, so nothing is scheduled twice.
 *
 * This replaces one expedited, self-foregrounding worker per track. That design
 * turned "download this album" into a request for a dozen concurrent foreground
 * services sharing one WorkManager service and a colliding notification id —
 * see [DownloadQueue] and [DownloadQueueWorker] for the details.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: DownloadQueue,
    private val preferences: PreferencesManager,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The queue persists itself through here rather than knowing about
        // storage: a fifty-track queue has to outlive the process that made it.
        queue.onChanged = { json -> scope.launch { preferences.setDownloadQueueJson(json) } }
        scope.launch {
            queue.restore(preferences.downloadQueueJson.first())
            if (queue.hasWork()) startWorker()
        }
    }

    fun downloadTrack(track: Track) {
        downloadTracks(listOf(track))
    }

    fun downloadTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue.enqueue(tracks.map { DownloadItem.from(it) })
        startWorker()
    }

    /** Drops a track off the queue, cancelling it if it happens to be running. */
    fun cancel(trackId: Long) {
        queue.remove(trackId)
    }

    /** Empties the queue and stops the job draining it. */
    fun cancelAll() {
        queue.clear()
        workManager.cancelUniqueWork(DownloadQueueWorker.WORK_NAME)
    }

    /**
     * Puts a failed track back in line.
     *
     * Unlike the old implementation this works after a restart: the queue entry
     * carries everything the download needs and is persisted, so a failed row
     * is never a retry button that silently does nothing.
     */
    fun retry(trackId: Long) {
        queue.retry(trackId)
        startWorker()
    }

    /**
     * KEEP, not REPLACE: enqueueing more tracks while the job is draining must
     * not restart it — the running job already reads the same queue and will
     * pick them up on its next pass. REPLACE would cancel mid-transfer and lose
     * whatever was in flight.
     */
    private fun startWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(constraints)
            // Downloading is user-initiated, not deferrable housekeeping.
            // Without this it is an ordinary JobScheduler job that Doze and OEM
            // battery managers may postpone indefinitely once the app leaves
            // the foreground, leaving the queue sitting on "Queued" forever.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("download")
            .build()
        workManager.enqueueUniqueWork(
            DownloadQueueWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun observeDownloadState(trackId: Long): Flow<TrackDownloadState> =
        queue.entries.map { entries ->
            val entry = entries.firstOrNull { it.item.trackId == trackId }
                ?: return@map TrackDownloadState(DownloadStatus.IDLE, 0f)
            TrackDownloadState(entry.status, entry.progress)
        }

    fun observeAllActiveDownloads(): Flow<Map<Long, TrackDownloadState>> =
        queue.entries.map { entries ->
            entries.associate { it.item.trackId to TrackDownloadState(it.status, it.progress) }
        }

    /**
     * Active downloads enriched with title/artist/cover, ready to render in the
     * progress pill and the downloads monitor. Running items first, then
     * whatever is furthest along — queue order otherwise, so an album reads in
     * track order rather than jumping about.
     */
    fun observeActiveDownloads(): Flow<List<ActiveDownload>> =
        queue.entries.map { entries ->
            entries
                .map { entry ->
                    ActiveDownload(
                        trackId = entry.item.trackId,
                        title = entry.item.title,
                        artistName = entry.item.artistName,
                        artworkUri = entry.item.albumCover,
                        status = entry.status,
                        progress = entry.progress,
                        isThxSpatialAudio = entry.item.isThxSpatialAudio,
                    )
                }
                .sortedWith(
                    compareByDescending<ActiveDownload> { it.status == DownloadStatus.DOWNLOADING }
                        .thenByDescending { it.progress }
                )
        }
}
