package tf.monochrome.android.audio.eq

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app's process, and with it the system-wide equalizer, alive
 * while the switch is on.
 *
 * The effect is this process's: Android takes it off the moment the process
 * goes, and a process with nothing in the foreground goes whenever the phone
 * wants its memory. So with the app closed and another app playing, the
 * correction used to stop without a word, the notification still saying it
 * was on. This service has no work of its own. It stands in the foreground
 * under that notification, which is what keeps the process, and is restarted
 * by Android if the process is killed all the same (START_STICKY): the app
 * starts with it, and [SystemAudioEqController] puts the effect back.
 *
 * Android does not let an app start a foreground service from the
 * background (from 12 on). A start that is refused is not an error here: the
 * effect plays as it did before there was a service, for as long as the
 * process lasts, and [sync] is asked again when the app is next opened.
 */
class SystemEqService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        val up = runCatching {
            ServiceCompat.startForeground(this, SystemEqNotice.NOTIFICATION_ID, SystemEqNotice.build(this), type)
        }.onFailure { Log.w(TAG, "could not stand in the foreground: ${it.message}") }.isSuccess
        if (!up) {
            // Started in the background after all (a restart Android would
            // not let into the foreground): leave rather than be an error.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The notification is the controller's too: it takes it down when
        // the switch goes off, and keeps it while the effect plays on.
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SystemEqService"

        /**
         * Starts the service while system-wide is [on] and stops it when it
         * is off. Returns whether it is now as asked; false is a start
         * Android refused, to be asked again from the foreground.
         */
        fun sync(context: Context, on: Boolean): Boolean {
            val intent = Intent(context, SystemEqService::class.java)
            return runCatching {
                if (on) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            }.onFailure { Log.i(TAG, "service ${if (on) "start" else "stop"} refused: ${it.message}") }.isSuccess
        }
    }
}
