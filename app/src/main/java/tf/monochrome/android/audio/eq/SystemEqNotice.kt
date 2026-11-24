package tf.monochrome.android.audio.eq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import tf.monochrome.android.R
import tf.monochrome.android.locale.AppLanguage

/**
 * The ongoing "System-wide EQ active" notification: posted by
 * [SystemAudioEqController] while the switch is on, and the one
 * [SystemEqService] stands in the foreground with. One notification, under
 * one id, whichever of the two put it up.
 */
internal object SystemEqNotice {
    const val CHANNEL_ID = "system_wide_eq"
    const val NOTIFICATION_ID = 42010

    fun build(context: Context): Notification {
        ensureChannel(context)
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val text = AppLanguage.wrap(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(text.getString(R.string.notif_system_eq_title))
            .setContentText(text.getString(R.string.notif_system_eq_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val text = AppLanguage.wrap(context)
        // Creating a channel that exists only updates its name and description.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                text.getString(R.string.notif_system_eq_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = text.getString(R.string.notif_system_eq_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }
}
