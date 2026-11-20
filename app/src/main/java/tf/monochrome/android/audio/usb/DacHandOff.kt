package tf.monochrome.android.audio.usb

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Whether the DAC can be taken from Android's own audio now.
 *
 * Taking the DAC (the first [LibusbUacDriver.start] after it is opened, or
 * after a stop) claims its interfaces with the kernel's driver detached
 * (`libusb_set_auto_detach_kernel_driver`): the phone's USB sound card is
 * pulled out from under Android. With Developer options › "Disable USB audio
 * routing" on, Android never plays to that card and nothing is lost. With it
 * off, which the Quick Settings tile needs to hand the DAC to other apps,
 * Android may be playing to that card at that moment: another app on the DAC,
 * or this app's own output. A phone that plays USB audio from its audio DSP
 * can then have the DSP and the app's own transfers on the same endpoints.
 * That is thought to be what restarted the owner's phone (2026-11-20, a
 * OnePlus on Android 17, the tile tapped to take the DAC back). No log
 * survived, so it is a guess; [DacClaimNote] is there to settle it.
 *
 * So the DAC is taken only when Android has no USB output (routing off), or
 * when no media has played and no call or ringtone been up for [QUIET_MS]:
 * long enough for Android to put its USB output to standby, which closes the
 * card (AudioFlinger's standby is 3 s unless the phone changes it). The
 * quiet is only counted while someone looks: the first look, or one after
 * [UNWATCHED_MS] without any, starts the count again, so a claim with
 * routing on waits [QUIET_MS] even when Android was quiet before. A paused
 * player looks about once a second (Media3's idle round), a playing one at
 * every buffer.
 *
 * Android's answers are binder calls, so they are asked at most every
 * [LOOK_EVERY_MS]; in between the last answer stands.
 */
internal class DacHandOff(
    private val android: AndroidOutput,
    private val nowMs: () -> Long,
) {
    /** What Android says about its own output. Both err on "yes" when it cannot tell. */
    interface AndroidOutput {
        /** Android has a USB audio output, so it may play to the DAC: USB audio routing is on. */
        fun routesToUsb(): Boolean

        /** Media is playing in any app, or a call or a ringtone is up. */
        fun busy(): Boolean
    }

    private var quietSinceMs: Long? = null
    private var lastLookMs: Long? = null
    private var lastAnswer = false
    private var lastRoutes = false
    private var lastBusy = false

    /** True when the DAC may be taken from Android now. */
    @Synchronized
    fun clear(): Boolean {
        val now = nowMs()
        val last = lastLookMs
        if (last != null && now - last < LOOK_EVERY_MS) return lastAnswer
        // Nobody looked for a while: Android may have played and stopped in
        // between, so its quiet is counted from now.
        if (last == null || now - last > UNWATCHED_MS) quietSinceMs = null
        lastLookMs = now
        lastAnswer = decide(now)
        return lastAnswer
    }

    private fun decide(now: Long): Boolean {
        lastRoutes = android.routesToUsb()
        if (!lastRoutes) {
            // Routing off: Android never plays to the card. Should it come on
            // later (the DAC plugged in again with the setting changed), the
            // quiet is counted from then.
            lastBusy = false
            quietSinceMs = null
            return true
        }
        lastBusy = android.busy()
        if (lastBusy) {
            quietSinceMs = null
            return false
        }
        val since = quietSinceMs ?: now.also { quietSinceMs = it }
        return now - since >= QUIET_MS
    }

    /** The last answer's reasons, for the log and [DacClaimNote]. */
    @Synchronized
    fun describe(): String {
        val quiet = quietSinceMs?.let { "%.1f s".format(java.util.Locale.ROOT, (nowMs() - it) / 1000f) } ?: "no"
        return "Android routes USB audio: ${yesNo(lastRoutes)}, Android busy: ${yesNo(lastBusy)}, quiet for: $quiet"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** Android's [AudioManager], as [AndroidOutput]. */
    class Of(private val audio: AudioManager) : AndroidOutput {
        override fun routesToUsb(): Boolean = runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }.getOrDefault(true)

        override fun busy(): Boolean = runCatching {
            audio.isMusicActive || audio.mode != AudioManager.MODE_NORMAL
        }.getOrDefault(true)
    }

    companion object {
        /** Android quiet this long before the DAC is taken: its 3 s standby, and a second to spare. */
        const val QUIET_MS = 4_000L

        /** How often Android is asked, at most. */
        const val LOOK_EVERY_MS = 250L

        /** Looks further apart than this leave a gap Android may have played in. */
        const val UNWATCHED_MS = 2_500L
    }
}
