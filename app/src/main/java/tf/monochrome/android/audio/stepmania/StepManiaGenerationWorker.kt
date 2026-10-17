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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import tf.monochrome.android.R
import tf.monochrome.android.glyph.pack.OwnPackStore
import tf.monochrome.android.glyph.chart.SscChartWriter
import tf.monochrome.android.glyph.pack.PackWriter
import tf.monochrome.android.glyph.pack.StepManiaPack
import tf.monochrome.android.glyph.pack.TreePackSink

/**
 * Chart generation as a background job: the worker that drains [ChartQueue].
 *
 * Separating a song takes time, and it used to live in the Trypt Mania
 * screen's view model: leave the screen, or let the phone put the app away,
 * and the job went with it. Here WorkManager owns it instead. It runs as a
 * foreground job with its progress in a notification — the only way Android
 * lets an app keep working for that long once it is out of sight — and says
 * how the batch ended in one when it is done.
 *
 * It charts the line's songs until none is left, a song that fails noted and
 * passed over rather than ending the batch. As many are in flight at once as
 * the server separates at a time, and one more: a server separates one song at
 * a time, so a batch goes faster only on several copies of it (Modal's CPU
 * copies, Railway's replicas), each server says how many it has, and the song
 * sent ahead uploads while the others separate. Two until a server has said,
 * which its first song does, so the line widens once that song is back.
 *
 * The screen watches [ChartQueue] rather than this job, so leaving and coming
 * back shows the same progress, and the songs waiting behind it.
 */
@HiltWorker
class StepManiaGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val conversion: StepManiaConversionService,
    private val ownPack: OwnPackStore,
    private val queue: ChartQueue,
    private val pace: ChartPace,
) : CoroutineWorker(context, params) {

    private var lastShownAt = 0L
    private var lastShown: String? = null
    private val lastReported = HashMap<String, Pair<Int, String>>()

    override suspend fun doWork(): Result {
        // A spare worker, appended behind one that emptied the line, has nothing to do.
        if (!queue.hasWork()) return Result.success()
        queue.startDraining()
        // Best effort, as for downloads: Android 12+ refuses a foreground start
        // from the background, and a job that merely loses its notification is
        // better than one that never runs.
        runCatching { setForeground(foregroundInfo(queue.state.value)) }

        val outcomes = mutableListOf<ChartOutcome>()
        try {
            // Songs in flight, counted here only; each sends on [done] as it ends.
            var inFlight = 0
            val done = Channel<Unit>(Channel.UNLIMITED)
            coroutineScope {
                while (true) {
                    while (done.tryReceive().isSuccess) inFlight--
                    // The next song's server sets how many go at once, read
                    // again each time, so it widens as soon as a server says.
                    val mode = queue.state.value.waiting.firstOrNull()?.mode ?: ChartMode.FAST
                    while (inFlight >= lanesFor(mode)) {
                        done.receive()
                        inFlight--
                    }
                    val job = queue.next() ?: break
                    inFlight++
                    launch {
                        try {
                            val outcome = chart(job)
                            queue.finish(outcome)
                            synchronized(outcomes) { outcomes += outcome }
                        } catch (stopped: CancellationException) {
                            // Stopped by the system, it is charted again when the
                            // worker restarts; cancelled, the line is gone already.
                            withContext(NonCancellable) { queue.putBack(job) }
                            throw stopped
                        } finally {
                            done.trySend(Unit)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { queue.stopDraining() }
        }
        notifyFinished(outcomes)
        return Result.success()
    }

    /** One song, start to finish; how it went, never an exception. */
    private suspend fun chart(job: ChartJob): ChartOutcome {
        val source = Uri.parse(job.source)
        val destination = File(job.destination)
        val request = StepManiaRequest(
            title = job.title,
            artist = job.artist,
            musicFileName = job.musicFileName,
            difficulties = StepManiaDifficulty.entries.toSet(),
            course = job.course,
        )
        val result = try {
            conversion.convert(
                source = source,
                destination = Uri.fromFile(destination),
                request = request,
                mode = job.mode,
            ) { progress -> report(job, progress) }
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
                pace.recordServer(success.remote)
                val saved = saveToOwnPack(destination, source, request)
                ChartOutcome(
                    trackId = job.trackId,
                    title = job.title,
                    backendName = success.backendName,
                    savedToPack = saved?.isSuccess == true,
                    packFailure = saved?.exceptionOrNull()?.let { it.message ?: "unknown error" },
                )
            },
            onFailure = { failure ->
                ChartOutcome(job.trackId, job.title, failure = failure.message ?: "Chart generation failed.")
            },
        )
    }

    /**
     * The chart, written into the player's own pack in the folder they chose
     * ([OwnPackStore]), with its music beside it, as any StepMania song. Null
     * when no folder is chosen. A failure here never fails the chart: it is
     * made and plays in the app either way.
     *
     * A song's dance and pop'n charts are kept in separate files here, but a
     * StepMania song is one simfile, so the pack's copy carries both: the one
     * just made, and the other course's charts when it has them.
     */
    private fun saveToOwnPack(chart: File, music: Uri, request: StepManiaRequest): kotlin.Result<Unit>? {
        val folder = ownPack.folder.value ?: return null
        return runCatching {
            val other = CourseType.entries.firstOrNull { it != request.course }?.let { course ->
                File(chart.parentFile, chart.name.removeSuffix(request.course.fileSuffix + ".ssc") + course.fileSuffix + ".ssc")
                    .takeIf { it.isFile }?.readText()
            }
            PackWriter.writeSong(
                sink = TreePackSink(applicationContext, folder),
                pack = StepManiaPack.OWN_PACK,
                title = request.title,
                artist = request.artist,
                ssc = SscChartWriter.withOtherCourse(chart.readText(), other),
                music = {
                    applicationContext.contentResolver.openInputStream(music)
                        ?: throw java.io.IOException("The song's file could not be read.")
                },
                art = tf.monochrome.android.glyph.pack.SongArt.of(applicationContext, music),
            )
            Unit
        }
    }

    /**
     * Songs in flight at once for a song in [mode]: as many as its server
     * separates at a time, as it last said, and one more uploading ahead.
     */
    private fun lanesFor(mode: ChartMode): Int = lanes(pace.snapshot(gpu = mode == ChartMode.FAST).parallel)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(queue.state.value)

    /**
     * Progress for the screen and the notification. The screen hears a song's
     * every whole percent and new stage; the notification at most a few times
     * a second, since Android drops updates that come faster.
     */
    @Synchronized
    private fun report(job: ChartJob, progress: StepManiaConversionProgress) {
        val shown = (progress.fraction * 100).toInt() to progress.stage
        if (lastReported[job.trackId] == shown) return
        lastReported[job.trackId] = shown
        queue.progress(job.trackId, progress.fraction, progress.stage)

        val state = queue.state.value
        val summary = "${(state.fraction * 100).toInt()} ${state.running.firstOrNull()?.stage}"
        val now = SystemClock.elapsedRealtime()
        if (summary == lastShown || now - lastShownAt < UPDATE_MS) return
        lastShown = summary
        lastShownAt = now
        // The service reports from its own threads and does not suspend.
        runCatching { setForegroundAsync(foregroundInfo(state)) }
    }

    private fun foregroundInfo(state: ChartQueueState): ForegroundInfo {
        createChannel(applicationContext)
        val current = state.running.firstOrNull()
        val title = when {
            state.total > 1 -> "Charting ${(state.finished.size + 1).coerceAtMost(state.total)} of ${state.total}"
            current != null -> "Charting ${current.job.title}"
            else -> "Charting a song"
        }
        val text = when {
            current == null -> "Preparing"
            state.total > 1 -> "${current.job.title} · ${current.stage}"
            else -> current.stage
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, (state.fraction * 100).toInt().coerceIn(0, 100), state.fraction <= 0f)
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

    /** How the batch ended, for a player who has long since left the screen. */
    private fun notifyFinished(outcomes: List<ChartOutcome>) {
        val (heading, text) = finishedMessage(outcomes) ?: return
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

        /**
         * The most songs in flight at once, however many copies a server says
         * it has: the Railway deploy runs 42 replicas, and one more uploads
         * ahead. The HTTP client lets that many requests at one host
         * (NetworkModule); OkHttp's own limit is five.
         */
        const val MAX_LANES = 43

        /** Songs in flight for a server that separates [parallel] at a time: those, and one uploading ahead. */
        fun lanes(parallel: Int?): Int = ((parallel ?: 1).coerceAtLeast(1) + 1).coerceAtMost(MAX_LANES)

        private const val CHANNEL_ID = "trypt_mania_charts"
        private const val NOTIFICATION_ID = 8200
        private const val DONE_NOTIFICATION_ID = 8201
        private const val UPDATE_MS = 500L

        /**
         * The notification for a finished run: one song as it always was, a
         * batch as a count with what failed. Null for a run that charted nothing.
         */
        internal fun finishedMessage(outcomes: List<ChartOutcome>): Pair<String, String>? {
            val single = outcomes.singleOrNull()
            if (single != null) {
                return if (single.failure != null) {
                    "Chart not made" to "${single.title}: ${single.failure}"
                } else {
                    "Chart ready" to when {
                        single.packFailure != null -> "${single.title} is ready to play here, but a copy could not be saved to your pack folder: ${single.packFailure}"
                        single.savedToPack -> "${single.title} is ready to play, and saved to your ${StepManiaPack.OWN_PACK} pack."
                        else -> "${single.title} is ready to play."
                    }
                }
            }
            if (outcomes.isEmpty()) return null
            val failed = outcomes.filter { it.failure != null }
            val made = outcomes.size - failed.size
            val heading = when {
                made == 0 -> "No charts made"
                failed.isEmpty() -> "$made charts ready"
                else -> "$made of ${outcomes.size} charts ready"
            }
            val text = buildString {
                if (failed.isEmpty()) append("All ${outcomes.size} songs are ready to play.")
                else {
                    val first = failed.first()
                    append("Not made: ${first.title}: ${first.failure}")
                    if (failed.size > 1) append(", and ${failed.size - 1} more")
                    append('.')
                }
                val unsaved = outcomes.count { it.packFailure != null }
                if (unsaved > 0) append(" $unsaved could not be saved to your pack folder.")
            }
            return heading to text
        }

        /** Start a worker on the line, after any one still finishing. */
        fun start(context: Context) {
            val work = OneTimeWorkRequestBuilder<StepManiaGenerationWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
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
