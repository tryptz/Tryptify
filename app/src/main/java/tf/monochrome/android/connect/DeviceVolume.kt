// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import tf.monochrome.android.audio.usb.BypassVolumeController
import tf.monochrome.android.audio.usb.LibusbUacDriver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * This phone's volume as the listener's other devices see and set it
 * (Tryptify Connect), from 0 to 1.
 *
 * It is the volume of what is heard: the exclusive USB DAC's level while the
 * DAC carries the song (Android's volume steers nothing then, see
 * [BypassVolumeController]), else Android's media volume. A phone whose
 * volume is fixed (a TV box, some car units) has none: the others then show
 * no slider for it.
 */
@Singleton
class DeviceVolume @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bypass: BypassVolumeController,
    private val libusb: LibusbUacDriver,
) {
    private val audio: AudioManager? by lazy { context.getSystemService(AudioManager::class.java) }

    private fun onDac(): Boolean = bypass.steersOutput(libusb.isOpen.value)

    private fun min(am: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0

    /** The volume now, or null where this phone has none to set. */
    fun now(): Double? {
        if (onDac()) return BypassVolumeController.dbToPosition(bypass.levelDb.value).toDouble()
        val am = audio ?: return null
        if (am.isVolumeFixed) return null
        return runCatching {
            fractionOf(am.getStreamVolume(AudioManager.STREAM_MUSIC), min(am), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }.getOrNull()
    }

    /** Sets it to [v], as another device's slider asked; the nearest step Android has. */
    fun set(v: Double) {
        val to = ConnectProtocol.volume(v) ?: return
        if (onDac()) {
            bypass.setLevelDb(BypassVolumeController.positionToDb(to.toFloat()))
            return
        }
        val am = audio ?: return
        if (am.isVolumeFixed) return
        // No flags: no system panel pops up on a phone nobody is holding.
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, indexFor(to, min(am), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
        }.onFailure { Log.w(TAG, "volume not set: ${it.message}") }
    }

    /**
     * One item each time the volume may have changed, whoever changed it: the
     * keys, the system panel, the DAC's slider, a command from another
     * device, or the DAC taking the song or giving it back. Collected only
     * while Connect is connected, so nothing is registered otherwise.
     */
    val changes: Flow<Unit> = merge(
        androidChanges(),
        bypass.levelDb.drop(1).map { },
        bypass.dacCarriesStream.drop(1).map { },
        bypass.androidStreamAudible.drop(1).map { },
        libusb.isOpen.drop(1).map { },
    )

    private fun androidChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Other streams (ring, alarm, call) change nothing heard here.
                val stream = intent.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC)
                if (stream == AudioManager.STREAM_MUSIC) trySend(Unit)
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.isSuccess
        awaitClose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }

    companion object {
        private const val TAG = "TryptifyConnect"

        // Android sends this on every stream volume change; the constants are
        // not in the public SDK, the strings have been the same since API 1.
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        /** Android's step [index] of [min]..[max] as 0 to 1; null where there are no steps. */
        fun fractionOf(index: Int, min: Int, max: Int): Double? =
            if (max <= min) null else ((index - min).toDouble() / (max - min)).coerceIn(0.0, 1.0)

        /** The step of [min]..[max] nearest [v] (0 to 1). */
        fun indexFor(v: Double, min: Int, max: Int): Int =
            if (max <= min) min else (min + v.coerceIn(0.0, 1.0) * (max - min)).roundToInt().coerceIn(min, max)
    }
}
