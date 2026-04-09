package tf.monochrome.android.data.downloads

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    fun downloadTrack(track: Track) {
        enqueueDownload(track)
    }

    fun downloadTracks(tracks: List<Track>) {
        tracks.forEach { enqueueDownload(it) }
    }

    private fun enqueueDownload(track: Track) {
        val inputData = workDataOf(
            DownloadWorker.KEY_TRACK_ID to track.id,
            DownloadWorker.KEY_TRACK_TITLE to track.title,
            DownloadWorker.KEY_ARTIST_NAME to (track.artist?.name ?: "Unknown Artist"),
            DownloadWorker.KEY_ALBUM_TITLE to (track.album?.title),
            DownloadWorker.KEY_ALBUM_COVER to (track.album?.cover),
            DownloadWorker.KEY_DURATION to track.duration
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag("download")
            .build()

        workManager.enqueueUniqueWork(
            "download_${track.id}",
            ExistingWorkPolicy.KEEP,
            downloadRequest
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
        return workManager.getWorkInfosByTagLiveData("download")
            .asFlow()
            .map { workInfos ->
                workInfos
                    .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
                    .mapNotNull { info ->
                        val trackId = info.tags
                            .firstOrNull { it.startsWith("download_") }
                            ?.removePrefix("download_")
                            // WorkManager unique work names are stored as tags too
                            ?: return@mapNotNull null
                        val id = trackId.toLongOrNull() ?: return@mapNotNull null
                        val progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
                        val status = when (info.state) {
                            WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                            else -> DownloadStatus.QUEUED
                        }
                        id to TrackDownloadState(status, progress)
                    }
                    .toMap()
            }
    }
}
