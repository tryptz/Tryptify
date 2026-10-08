package tf.monochrome.android.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.audio.usb.BypassVolumeController
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * Wraps an [androidx.media3.exoplayer.ExoPlayer] so the Media3-managed
 * foreground notification and lock-screen controls can show working
 * next / previous buttons even though the underlying player only ever holds
 * one [androidx.media3.common.MediaItem] at a time.
 *
 * The app resolves stream URLs one-track-at-a-time (TIDAL DASH MPDs have short
 * TTLs; batch-resolving a long queue up front wastes requests and ages
 * stream URLs before they're used). ExoPlayer's own playlist is therefore
 * never the source of truth for queue position — [QueueManager] is.
 *
 * This forwarder:
 *  - Always advertises SEEK_TO_NEXT / SEEK_TO_PREVIOUS as available so the
 *    notification UI enables the buttons and the session doesn't silently
 *    drop the command when the raw player's playlist is empty. End-of-queue
 *    is handled inside `onNext` / `onPrev` (they simply stop playback).
 *  - Re-routes `seekToNext*` / `seekToPrevious*` calls to [onNext] / [onPrev]
 *    (the service's skipToNext / skipToPrevious), which handle queue
 *    advancement plus stream-URL resolution.
 *  - While a USB DAC is claimed for exclusive output and the stream plays on
 *    it ([isExclusive]; BypassVolumeController.steersOutput), reports
 *    itself as a remote device with the DAC level as its volume. Android then
 *    sends the hardware volume keys to this session, with the app in the
 *    background or the screen locked too, and its volume panel shows the
 *    DAC's level: STREAM_MUSIC, which the keys move otherwise, never reaches
 *    a DAC driven over libusb. The service calls [onExclusiveChanged] and
 *    [onDeviceVolumeChanged] so the session hears about it.
 */
@OptIn(UnstableApi::class)
class QueueForwardingPlayer(
    delegate: Player,
    @Suppress("unused") private val queueManager: QueueManager,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val dacVolume: BypassVolumeController? = null,
    private val isExclusive: () -> Boolean = { false },
) : ForwardingPlayer(delegate) {

    // The session's listeners, kept so the device events below can reach
    // them: ForwardingPlayer only passes on the wrapped player's own events.
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    private val remote: Boolean get() = dacVolume != null && isExclusive()

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return Player.Commands.Builder()
            .addAll(base)
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .apply { if (remote) addAll(*DEVICE_VOLUME_COMMANDS) }
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        // Unconditional so the notification button is always tappable and the
        // Media3 session never pre-filters the command. `onNext` / `onPrev`
        // decide what happens at end-of-queue (they can stop the player).
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
        in DEVICE_VOLUME_COMMANDS -> remote || super.isCommandAvailable(command)
        else -> super.isCommandAvailable(command)
    }

    // ── The DAC as a remote device ─────────────────────────────────────

    override fun getDeviceInfo(): DeviceInfo =
        if (remote) REMOTE_DAC else super.getDeviceInfo()

    override fun getDeviceVolume(): Int {
        val volume = dacVolume
        return if (remote && volume != null) dbToSteps(volume.levelDb.value) else super.getDeviceVolume()
    }

    override fun isDeviceMuted(): Boolean {
        val volume = dacVolume
        return if (remote && volume != null) {
            volume.levelDb.value <= BypassVolumeController.MIN_DB
        } else {
            super.isDeviceMuted()
        }
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        val dac = dacVolume
        if (remote && dac != null) dac.setLevelDb(stepsToDb(volume)) else super.setDeviceVolume(volume, flags)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun setDeviceVolume(volume: Int) {
        val dac = dacVolume
        if (remote && dac != null) dac.setLevelDb(stepsToDb(volume)) else super.setDeviceVolume(volume)
    }

    override fun increaseDeviceVolume(flags: Int) {
        val dac = dacVolume
        if (remote && dac != null) dac.stepLevel(1) else super.increaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun increaseDeviceVolume() {
        val dac = dacVolume
        if (remote && dac != null) dac.stepLevel(1) else super.increaseDeviceVolume()
    }

    override fun decreaseDeviceVolume(flags: Int) {
        val dac = dacVolume
        if (remote && dac != null) dac.stepLevel(-1) else super.decreaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun decreaseDeviceVolume() {
        val dac = dacVolume
        if (remote && dac != null) dac.stepLevel(-1) else super.decreaseDeviceVolume()
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        val dac = dacVolume
        if (remote && dac != null) dac.setMuted(muted) else super.setDeviceMuted(muted, flags)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun setDeviceMuted(muted: Boolean) {
        val dac = dacVolume
        if (remote && dac != null) dac.setMuted(muted) else super.setDeviceMuted(muted)
    }

    /**
     * A DAC was claimed or let go: the session switches between this remote
     * volume and the phone's own. Main thread, where the session listens.
     */
    fun onExclusiveChanged() {
        val info = deviceInfo
        val commands = availableCommands
        listeners.forEach {
            it.onDeviceInfoChanged(info)
            it.onAvailableCommandsChanged(commands)
            it.onDeviceVolumeChanged(deviceVolume, isDeviceMuted)
        }
    }

    /** The DAC level moved (slider, keys, a new session): the system panel follows. */
    fun onDeviceVolumeChanged() {
        if (!remote) return
        val volume = deviceVolume
        val muted = isDeviceMuted
        listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
    }

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNextMediaItem() {
        Log.i(TAG, "seekToNextMediaItem routed to QueueManager")
        onNext()
    }

    override fun seekToPreviousMediaItem() {
        Log.i(TAG, "seekToPreviousMediaItem routed to QueueManager")
        onPrev()
    }

    override fun seekToNext() {
        Log.i(TAG, "seekToNext routed to QueueManager")
        onNext()
    }

    override fun seekToPrevious() {
        Log.i(TAG, "seekToPrevious routed to QueueManager")
        onPrev()
    }

    private companion object {
        const val TAG = "QueueForwardingPlayer"

        /** Steps from silence to 0 dB: one per volume key press. */
        val MAX_STEPS = ((-BypassVolumeController.MIN_DB) / BypassVolumeController.STEP_DB).roundToInt()

        val REMOTE_DAC: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(MAX_STEPS)
            .build()

        @Suppress("DEPRECATION")
        val DEVICE_VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )

        fun dbToSteps(db: Float): Int =
            ((db - BypassVolumeController.MIN_DB) / BypassVolumeController.STEP_DB).roundToInt().coerceIn(0, MAX_STEPS)

        fun stepsToDb(steps: Int): Float =
            BypassVolumeController.MIN_DB + steps.coerceIn(0, MAX_STEPS) * BypassVolumeController.STEP_DB
    }
}
