package tf.monochrome.android.debug

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service (type mediaProjection) hosting [DebugScreenRecorder].
 * Started from the Settings debug row with the projection consent result;
 * stopped from the row, the notification action, or the system revoking the
 * projection. Output lands in MediaStore under Movies/Tryptify as
 * TryptifyDebug_<timestamp>.mp4.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class DebugScreenRecordService : Service() {

    private var recorder: DebugScreenRecorder? = null
    private var projection: MediaProjection? = null
    private var pfd: ParcelFileDescriptor? = null
    private var fileUri: Uri? = null
    private var fileName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (recorder != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (data == null) { stopSelf(); return }

        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.w(TAG, "projection rejected: $e"); null
        }
        if (proj == null) { stopAndExit(); return }
        projection = proj
        // API 34+ requires a callback registered before capture starts; it is
        // also how we notice the user revoking from the status bar.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, null)

        val name = "TryptifyDebug_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Tryptify")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
        )
        if (uri == null) { stopAndExit(); return }
        fileUri = uri
        fileName = name
        val fd = contentResolver.openFileDescriptor(uri, "w")
        if (fd == null) { stopAndExit(); return }
        pfd = fd

        val captureAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val rec = DebugScreenRecorder(this, proj)
        try {
            rec.start(fd.fileDescriptor, captureAudio)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            runCatching { rec.stop() }
            stopAndExit(); return
        }
        recorder = rec
        setRecording(true)
    }

    private fun stopCapture() {
        recorder?.let { rec ->
            recorder = null
            rec.stop()
            runCatching { pfd?.close() }
            pfd = null
            fileUri?.let { uri ->
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                runCatching { contentResolver.update(uri, done, null, null) }
            }
            lastSavedName.value = fileName?.let { "Movies/Tryptify/$it" }
        }
        stopAndExit()
    }

    private fun stopAndExit() {
        projection = null
        setRecording(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.let { rec ->
            recorder = null
            rec.stop()
            runCatching { pfd?.close() }
        }
        setRecording(false)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Debug screen recorder", NotificationManager.IMPORTANCE_LOW,
            )
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DebugScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording screen (debug)")
            .setContentText("Native resolution/fps, stereo media audio")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "DebugScreenRecordSvc"
        private const val CHANNEL_ID = "debug_screen_recorder"
        private const val NOTIF_ID = 0x7EC0
        private const val ACTION_START = "tf.monochrome.android.debug.record.START"
        private const val ACTION_STOP = "tf.monochrome.android.debug.record.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        private val _recording = MutableStateFlow(false)

        /** Live recording state for the Settings row. */
        val recording: StateFlow<Boolean> = _recording.asStateFlow()

        /** Relative path of the last finished recording, for the row subtitle. */
        val lastSavedName = MutableStateFlow<String?>(null)

        private fun setRecording(v: Boolean) { _recording.value = v }

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, DebugScreenRecordService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DebugScreenRecordService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
