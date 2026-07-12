package tf.monochrome.android.data.import_

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import javax.inject.Inject

/**
 * Foreground service for Spotify playlist imports. Large playlists take
 * minutes (one catalog search per track), and running the import in a
 * ViewModel scope meant leaving the screen — or the app — killed it halfway.
 * The service owns the import lifecycle instead: it holds a persistent
 * notification with a determinate progress bar that keeps counting until the
 * entire playlist has been matched, then swaps to a dismissible result
 * summary and stops itself.
 *
 * Progress is read from the shared [PlaylistImportService.progress] flow —
 * the same one Settings renders — so the in-app counters and the
 * notification can never disagree.
 */
@AndroidEntryPoint
class SpotifyImportForegroundService : Service() {

    @Inject lateinit var playlistImporter: PlaylistImporter
    @Inject lateinit var importService: PlaylistImportService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null
    private var lastNotifyElapsed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            importJob?.cancel()
            importService.reportFailure("Import cancelled")
            finish(success = false, message = "Import cancelled")
            return START_NOT_STICKY
        }

        // One import at a time — the Settings UI disables its buttons while
        // one runs, but a second start via stale UI must not corrupt the
        // notification state machine of the one in flight.
        if (importJob?.isActive == true) return START_NOT_STICKY

        createChannel()
        startForegroundCompat(buildProgressNotification("Preparing import…", 0, 0))

        val strict = intent?.getBooleanExtra(EXTRA_STRICT, false) ?: false
        val request: suspend () -> Result<ImportProgress.Done> = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_URL -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                ({ playlistImporter.importFromUrl(url, strict) })
            }
            MODE_PLAYLIST -> {
                val id = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME)
                ({ playlistImporter.importSpotifyPlaylist(id, name, strict) })
            }
            MODE_LIKED -> ({ playlistImporter.importSpotifyLikedSongs(strict) })
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        importJob = scope.launch {
            // Mirror the shared progress flow into the notification while the
            // import runs in the sibling job below.
            val progressMirror = launch {
                importService.progress.collect { progress ->
                    when (progress) {
                        is ImportProgress.Fetching ->
                            notifyProgress("Fetching playlist from ${progress.source}…", 0, 0)
                        is ImportProgress.Matching ->
                            notifyProgress(
                                "Matching ${progress.current} of ${progress.total} · ${progress.matched} found",
                                progress.current,
                                progress.total,
                            )
                        else -> Unit // Terminal states are handled by finish().
                    }
                }
            }
            val result = request()
            progressMirror.cancel()
            result
                .onSuccess { done ->
                    finish(
                        success = true,
                        message = "Imported ${done.matched}/${done.total} tracks into '${done.playlistName}'",
                    )
                }
                .onFailure { finish(success = false, message = it.message ?: "Import failed") }
        }
        return START_NOT_STICKY
    }

    /** Replace the ongoing notification with a dismissible summary and stop. */
    private fun finish(success: Boolean, message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val summary = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (success) "Playlist import complete" else "Playlist import failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(launchAppIntent())
            .setAutoCancel(true)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, summary)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Throttled to ~2 updates/second: Matching emits once per track, and
     * flooding NotificationManager both rate-limits (updates get dropped,
     * the bar looks frozen) and burns battery. The final tick always lands
     * because [finish] posts its own notification.
     */
    private fun notifyProgress(text: String, current: Int, total: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyElapsed < 500) return
        lastNotifyElapsed = now
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ONGOING_NOTIFICATION_ID, buildProgressNotification(text, current, total))
    }

    private fun buildProgressNotification(text: String, current: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Importing Spotify playlist")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchAppIntent())
            .apply {
                if (total > 0) setProgress(total, current.coerceIn(0, total), false)
                else setProgress(0, 0, true) // indeterminate while fetching
            }
            .addAction(
                0,
                "Cancel",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, SpotifyImportForegroundService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun launchAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playlist import",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of Spotify playlist imports."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playlist_import"
        private const val ONGOING_NOTIFICATION_ID = 41001
        private const val RESULT_NOTIFICATION_ID = 41002

        private const val ACTION_CANCEL = "tf.monochrome.android.import.CANCEL"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URL = "url"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_STRICT = "strict"
        private const val MODE_URL = "url"
        private const val MODE_PLAYLIST = "playlist"
        private const val MODE_LIKED = "liked"

        fun importUrl(context: Context, url: String, strictAlbumMatch: Boolean) =
            start(context) {
                putExtra(EXTRA_MODE, MODE_URL)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_STRICT, strictAlbumMatch)
            }

        fun importPlaylist(context: Context, playlistId: String, name: String, strictAlbumMatch: Boolean) =
            start(context) {
                putExtra(EXTRA_MODE, MODE_PLAYLIST)
                putExtra(EXTRA_PLAYLIST_ID, playlistId)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_STRICT, strictAlbumMatch)
            }

        fun importLikedSongs(context: Context, strictAlbumMatch: Boolean) =
            start(context) {
                putExtra(EXTRA_MODE, MODE_LIKED)
                putExtra(EXTRA_STRICT, strictAlbumMatch)
            }

        private fun start(context: Context, configure: Intent.() -> Unit) {
            val intent = Intent(context, SpotifyImportForegroundService::class.java).apply(configure)
            context.startForegroundService(intent)
        }
    }
}
