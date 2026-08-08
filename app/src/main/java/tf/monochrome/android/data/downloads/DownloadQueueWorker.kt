package tf.monochrome.android.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import tf.monochrome.android.R

/**
 * The one job that drains [DownloadQueue].
 *
 * There is exactly one of these no matter how many tracks are waiting, and it
 * owns the single "Downloading" notification. That is the whole point: the
 * previous design enqueued one expedited worker per track, each promoting
 * itself to a foreground service with a notification id of
 * `base + (trackId % 1000)`. Two tracks whose ids are a thousand apart shared
 * an id, and a queue of any size had several workers starting and stopping
 * foreground state on one service at once — which on Android 12+ is also where
 * a background `startForeground` throws, inside WorkManager's own dispatcher
 * rather than anywhere this app can catch it.
 *
 * One job, one notification, [DownloadQueue.CONCURRENCY] transfers at a time,
 * and the queue itself decides what runs next.
 */
@HiltWorker
class DownloadQueueWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val queue: DownloadQueue,
    private val downloader: TrackDownloader,
) : CoroutineWorker(context, params) {

    // Live transfers, so cancelling an entry stops the bytes rather than just
    // hiding the row. Keyed by track id; entries are removed as they settle.
    private val running = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    override suspend fun doWork(): Result {
        queue.onCancel = { trackId -> running.remove(trackId)?.cancel() }
        // Anything still marked as running belongs to a process that died
        // mid-transfer; nothing is going to finish it.
        queue.requeueOrphans()
        notify(initial = true)

        try {
            while (queue.hasWork()) {
                val room = DownloadQueue.CONCURRENCY - queue.runningCount()
                val batch = queue.takeNext(room)
                if (batch.isEmpty()) {
                    // Nothing runnable but the queue isn't empty — everything
                    // left is FAILED and waiting on the user. Stop; a retry or
                    // a new download re-enqueues this worker.
                    if (queue.runningCount() == 0) break
                    delay(IDLE_POLL_MS)
                    continue
                }
                coroutineScope {
                    batch.map { item ->
                        async { run(item) }.also { running[item.trackId] = it }
                    }.awaitAll()
                }
                notify(initial = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The loop itself failing must not lose the queue. Leave it as it
            // stands and let the next enqueue restart the worker.
            Log.w(TAG, "download queue stopped unexpectedly", e)
            return Result.failure()
        } finally {
            queue.onCancel = null
            running.clear()
        }
        return Result.success()
    }

    private suspend fun run(item: DownloadItem) {
        val outcome = try {
            downloader.download(item) { progress ->
                queue.setProgress(item.trackId, progress)
                // Refreshing the notification per progress callback would post
                // hundreds of updates a second; the batch boundary is enough.
            }
        } catch (e: CancellationException) {
            // Cancelled by the user: the entry is already off the queue, so
            // there is nothing left to mark.
            running.remove(item.trackId)
            return
        } catch (e: Exception) {
            Log.w(TAG, "download threw for \"${item.title}\"", e)
            TrackDownloader.Outcome.RETRYABLE
        }
        running.remove(item.trackId)
        when (outcome) {
            TrackDownloader.Outcome.SUCCESS -> queue.complete(item.trackId)
            TrackDownloader.Outcome.RETRYABLE -> queue.fail(item.trackId, retryable = true)
            TrackDownloader.Outcome.PERMANENT -> queue.fail(item.trackId, retryable = false)
        }
    }

    /**
     * WorkManager needs this up front to satisfy an expedited request below
     * API 31, and [doWork] re-posts the same notification as the queue drains.
     * Best-effort on the way in: Android 14+ refuses a background foreground
     * start, and a queue that merely loses its notification is better than one
     * that never runs.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private suspend fun notify(initial: Boolean) {
        if (!initial && !queue.hasWork()) return
        runCatching { setForeground(foregroundInfo()) }
    }

    private fun foregroundInfo(): ForegroundInfo {
        createChannel()
        val entries = queue.entries.value
        val remaining = entries.count {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        val running = entries.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                if (remaining > 1) "Downloading $remaining tracks" else "Downloading",
            )
            .setContentText(running?.item?.title ?: "Preparing…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of track downloads."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    companion object {
        const val WORK_NAME = "download_queue"
        private const val TAG = "DownloadQueueWorker"
        private const val CHANNEL_ID = "downloads"
        // A single fixed id, because there is a single notification. The old
        // per-track id was derived from the track id and collided.
        private const val NOTIFICATION_ID = 8100
        private const val IDLE_POLL_MS = 250L
    }
}
