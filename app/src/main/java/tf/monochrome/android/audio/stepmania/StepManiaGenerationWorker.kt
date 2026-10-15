// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import tf.monochrome.android.R

/**
 * Chart generation as a background job.
 *
 * Separating a song takes minutes, and it used to live in the Trypt Mania
 * screen's view model: leave the screen, or let the phone put the app away,
 * and the job went with it. Here WorkManager owns it instead. It runs as a
 * foreground job with its progress in a notification — the only way Android
 * lets an app keep working for that long once it is out of sight — and says
 * how it ended in one when it is done.
 *
 * One at a time: separation holds a model and several stems of a piece in
 * memory, and two at once would hold twice that for no gain. A new one
 * replaces whatever was running, as the screen's Generate button always did.
 *
 * The screen watches the job ([WORK_NAME]) instead of running it, so leaving
 * and coming back shows the same progress.
 */
@HiltWorker
class StepManiaGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val conversion: StepManiaConversionService,
) : CoroutineWorker(context, params) {

    private val title: String get() = inputData.getString(KEY_TITLE).orEmpty()
    private var lastShownAt = 0L
    private var lastShown: Pair<Int, String>? = null

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val source = inputData.getString(KEY_SOURCE)?.let(Uri::parse) ?: return Result.failure()
        val destination = inputData.getString(KEY_DESTINATION)?.let(::File) ?: return Result.failure()
        val request = StepManiaRequest(
            title = title,
            artist = inputData.getString(KEY_ARTIST).orEmpty(),
            musicFileName = inputData.getString(KEY_MUSIC_FILE).orEmpty(),
            difficulties = StepManiaDifficulty.entries.toSet(),
        )

        // Best effort, as for downloads: Android 12+ refuses a foreground start
        // from the background, and a job that merely loses its notification is
        // better than one that never runs.
        runCatching { setForeground(foregroundInfo(0f, "Preparing")) }

        val result = try {
            conversion.convert(
                source = source,
                destination = Uri.fromFile(destination),
                request = request,
            ) { progress -> report(trackId, progress) }
        } finally {
            // A picked file was granted for this job; nothing reads it after.
            if (source.scheme == "content") {
                runCatching {
                    applicationContext.contentResolver.releasePersistableUriPermission(
                        source,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }

        return result.fold(
            onSuccess = { success ->
                notifyFinished("Chart ready", "$title is ready to play.")
                Result.success(workDataOf(KEY_TRACK_ID to trackId, KEY_BACKEND to success.backendName))
            },
            onFailure = { failure ->
                val reason = failure.message ?: "Chart generation failed."
                notifyFinished("Chart not made", "$title: $reason")
                Result.failure(workDataOf(KEY_TRACK_ID to trackId, KEY_FAILURE to reason))
            },
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f, "Preparing")

    /**
     * Progress for the screen and the notification, at most a few times a
     * second: the service reports far more often than that, and Android drops
     * notification updates that come too fast.
     */
    @Synchronized
    private fun report(trackId: String, progress: StepManiaConversionProgress) {
        val percent = (progress.fraction * 100).toInt()
        val shown = percent to progress.stage
        val now = SystemClock.elapsedRealtime()
        if (shown == lastShown || (now - lastShownAt < UPDATE_MS && shown.second == lastShown?.second)) return
        lastShown = shown
        lastShownAt = now
        // The service reports from its own threads and does not suspend, so
        // both go through WorkManager's fire-and-forget calls.
        setProgressAsync(
            workDataOf(
                KEY_TRACK_ID to trackId,
                KEY_FRACTION to progress.fraction,
                KEY_STAGE to progress.stage,
            ),
        )
        runCatching { setForegroundAsync(foregroundInfo(progress.fraction, progress.stage)) }
    }

    private fun foregroundInfo(fraction: Float, stage: String): ForegroundInfo {
        createChannel(applicationContext)
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (title.isNotBlank()) "Charting $title" else "Charting a song")
            .setContentText(stage)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), fraction <= 0f)
            .build()
        return when {
            // Android 15 has a type for exactly this: long work on media
            // already on the device, with its own time allowance.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** How it ended, for a player who has long since left the screen. */
    private fun notifyFinished(heading: String, text: String) {
        val context = applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun openApp(): PendingIntent? {
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(
            applicationContext,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val WORK_NAME = "trypt_mania_chart"

        /** Prefix of the tag naming the song a job is for, readable before any progress arrives. */
        const val TRACK_TAG = "trypt_mania_track:"

        const val KEY_TRACK_ID = "track_id"
        const val KEY_FRACTION = "fraction"
        const val KEY_STAGE = "stage"
        const val KEY_BACKEND = "backend"
        const val KEY_FAILURE = "failure"

        private const val KEY_SOURCE = "source"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_MUSIC_FILE = "music_file"

        private const val CHANNEL_ID = "trypt_mania_charts"
        private const val NOTIFICATION_ID = 8200
        private const val DONE_NOTIFICATION_ID = 8201
        private const val UPDATE_MS = 500L

        /** Start charting [source] into [destination], replacing any job already running. */
        fun enqueue(
            context: Context,
            trackId: String,
            source: Uri,
            destination: File,
            request: StepManiaRequest,
        ) {
            val work = OneTimeWorkRequestBuilder<StepManiaGenerationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TRACK_ID to trackId,
                        KEY_SOURCE to source.toString(),
                        KEY_DESTINATION to destination.absolutePath,
                        KEY_TITLE to request.title,
                        KEY_ARTIST to request.artist,
                        KEY_MUSIC_FILE to request.musicFileName,
                    ),
                )
                .addTag(TRACK_TAG + trackId)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trypt Mania charts", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of charts being generated in the background"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }
}
