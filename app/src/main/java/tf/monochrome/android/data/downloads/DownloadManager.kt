package tf.monochrome.android.data.downloads

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tf.monochrome.android.domain.model.Track
import java.util.concurrent.ConcurrentHashMap
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

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    // Track metadata for rendering active downloads (title/artist/cover). WorkInfo
    // can't carry it, so we keep it here, keyed by track id. Process-scoped: a
    // download still in flight after a restart falls back to a generic label.
    private data class DownloadMeta(
        val title: String,
        val artistName: String,
        val artworkUri: String?,
        val isThxSpatialAudio: Boolean,
        // The exact worker input used to enqueue this download, kept so a failed
        // download can be re-run without needing the original Track back.
        val inputData: Data,
    )
    private val meta = ConcurrentHashMap<Long, DownloadMeta>()

    // FAILED WorkSpecs are terminal: cancelUniqueWork/cancelAllWorkByTag do NOT
    // touch them, so a "Cancel" tap left the row on screen forever. Cancelling
    // a failed row instead hides it here (re-enqueueing un-hides it), and
    // "Cancel all" prunes the terminal records out of WorkManager's db for real.
    private val dismissed = MutableStateFlow<Set<Long>>(emptySet())

    fun downloadTrack(track: Track) {
        enqueueDownload(track)
    }

    fun downloadTracks(tracks: List<Track>) {
        tracks.forEach { enqueueDownload(it) }
    }

    /**
     * Cancel a single download. For in-flight work this stops it; for a FAILED
     * row (which cancellation can't remove) it dismisses the row from the UI.
     */
    fun cancel(trackId: Long) {
        workManager.cancelUniqueWork("download_$trackId")
        dismissed.update { it + trackId }
    }

    /**
     * Cancel everything still downloading or queued, and clear out terminal
     * (failed/cancelled/succeeded) records so the list actually empties.
     */
    fun cancelAll() {
        workManager.cancelAllWorkByTag("download")
        workManager.pruneWork()
        dismissed.update { emptySet() }
    }

    /**
     * Re-run a previously enqueued download (typically after a FAILED state).
     * REPLACE clears the terminal work record and starts fresh, so the failed
     * row turns back into a queued/downloading one.
     *
     * The original input is only known for downloads enqueued in THIS process
     * (meta is memory-only, and WorkInfo never exposes input data back). After
     * a restart a failed row is unrecoverable — dismiss it instead of leaving
     * a retry button that silently does nothing.
     */
    fun retry(trackId: Long) {
        val data = meta[trackId]?.inputData
        if (data == null) {
            dismissed.update { it + trackId }
            return
        }
        dismissed.update { it - trackId }
        workManager.enqueueUniqueWork(
            "download_$trackId",
            ExistingWorkPolicy.REPLACE,
            buildRequest(trackId, data),
        )
    }

    private fun buildRequest(trackId: Long, inputData: Data): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            // A download is user-initiated, not deferrable housekeeping. Without
            // this the request is an ordinary JobScheduler job, which Doze and
            // OEM battery managers are free to postpone indefinitely once the
            // app leaves the foreground — the row then sits on "Queued" forever
            // because ENQUEUED is exactly what QUEUED renders. Expedited work is
            // scheduled immediately; if the app has exhausted its expedited
            // quota it degrades to a normal request rather than being dropped.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("download")
            // Per-track tag so the aggregate observer can recover the track id
            // from WorkInfo (which doesn't expose the unique work name).
            .addTag("download_$trackId")
            .build()
    }

    private fun enqueueDownload(track: Track) {
        val inputData = workDataOf(
            DownloadWorker.KEY_TRACK_ID to track.id,
            // Separate Apple identity, resolved NOW while the source is known.
            // The worker must not re-derive it from track.id at run time — the
            // shared-id registry lookup is exactly what mis-routed Apple tracks
            // with synthetic ids to the Qobuz backend.
            DownloadWorker.KEY_APPLE_ID to (track.appleId ?: -1L),
            DownloadWorker.KEY_TRACK_TITLE to track.title,
            DownloadWorker.KEY_ARTIST_NAME to (track.artist?.name ?: "Unknown Artist"),
            DownloadWorker.KEY_ALBUM_TITLE to (track.album?.title),
            DownloadWorker.KEY_ALBUM_COVER to (track.album?.cover),
            DownloadWorker.KEY_DURATION to track.duration,
            DownloadWorker.KEY_VERSION to track.version,
            DownloadWorker.KEY_IS_THX to track.isThxSpatialAudio,
        )
        meta[track.id] = DownloadMeta(
            title = track.title,
            artistName = track.artist?.name ?: track.displayArtist.ifBlank { "Unknown Artist" },
            artworkUri = track.coverUrl,
            isThxSpatialAudio = track.isThxSpatialAudio,
            inputData = inputData,
        )

        dismissed.update { it - track.id }
        workManager.enqueueUniqueWork(
            "download_${track.id}",
            ExistingWorkPolicy.KEEP,
            buildRequest(track.id, inputData),
        )
    }

    fun observeDownloadState(trackId: Long): Flow<TrackDownloadState> {
        return workManager.getWorkInfosForUniqueWorkLiveData("download_$trackId")
            .asFlow()
            .map { workInfos ->
                val info = workInfos.firstOrNull()
                if (info == null) {
                    TrackDownloadState(DownloadStatus.IDLE, 0f)
                } else {
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                            TrackDownloadState(DownloadStatus.QUEUED, 0f)
                        WorkInfo.State.RUNNING -> {
                            val progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
                            TrackDownloadState(DownloadStatus.DOWNLOADING, progress)
                        }
                        WorkInfo.State.SUCCEEDED ->
                            TrackDownloadState(DownloadStatus.COMPLETED, 1f)
                        WorkInfo.State.FAILED ->
                            TrackDownloadState(DownloadStatus.FAILED, 0f)
                        WorkInfo.State.CANCELLED ->
                            TrackDownloadState(DownloadStatus.IDLE, 0f)
                    }
                }
            }
    }

    fun observeAllActiveDownloads(): Flow<Map<Long, TrackDownloadState>> {
        return combine(
            workManager.getWorkInfosByTagLiveData("download").asFlow(),
            dismissed,
        ) { workInfos, hidden ->
                workInfos
                    // Keep FAILED alongside the in-flight states — otherwise a
                    // failed download silently disappeared from the pill and the
                    // monitor with no way to see or retry it.
                    .filter {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED ||
                            it.state == WorkInfo.State.FAILED
                    }
                    .mapNotNull { info ->
                        val trackId = info.tags
                            .firstOrNull { it.startsWith("download_") }
                            ?.removePrefix("download_")
                            // WorkManager unique work names are stored as tags too
                            ?: return@mapNotNull null
                        val id = trackId.toLongOrNull() ?: return@mapNotNull null
                        if (id in hidden) return@mapNotNull null
                        val progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
                        val status = when (info.state) {
                            WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                            WorkInfo.State.FAILED -> DownloadStatus.FAILED
                            else -> DownloadStatus.QUEUED
                        }
                        id to TrackDownloadState(status, progress)
                    }
                    .toMap()
        }
    }

    /**
     * Active downloads (queued + running) enriched with title/artist/cover, ready
     * to render in the progress pill and the downloads monitor. Sorted with the
     * currently-downloading items first.
     */
    fun observeActiveDownloads(): Flow<List<ActiveDownload>> =
        observeAllActiveDownloads().map { stateById ->
            stateById.entries
                .map { (id, state) ->
                    val m = meta[id]
                    ActiveDownload(
                        trackId = id,
                        title = m?.title ?: "Track $id",
                        artistName = m?.artistName ?: "",
                        artworkUri = m?.artworkUri,
                        status = state.status,
                        progress = state.progress,
                        isThxSpatialAudio = m?.isThxSpatialAudio ?: false,
                    )
                }
                .sortedWith(
                    compareByDescending<ActiveDownload> { it.status == DownloadStatus.DOWNLOADING }
                        .thenByDescending { it.progress }
                        .thenBy { it.title }
                )
        }
}
