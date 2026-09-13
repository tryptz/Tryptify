package tf.monochrome.android.audio.pipeline

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the sound is going, as far as the framework will say. */
data class RoutedOutput(val name: String, val typeLabel: String)

/**
 * Which output device is most likely carrying playback, and what rate the
 * HAL runs at.
 *
 * **This is inference, not a reading.** Android has no public "what is the
 * current output route" call: `AudioTrack.getRoutedDevice` needs the
 * AudioTrack, which lives inside Media3's sink, and there is no way to it
 * from here. What there is, is the list of connected outputs — so this picks
 * from that list in the order the platform's own routing policy prefers,
 * which is right whenever exactly one thing is plugged in, and right nearly
 * always when more than one is.
 *
 * `UsbAudioRouter` already makes the same inference for the USB subset; this
 * covers everything else so the panel can name a Bluetooth or wired output
 * rather than showing a dash for the common case.
 */
@Singleton
class OutputDeviceProbe @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val handler = Handler(Looper.getMainLooper())

    private val _routed = MutableStateFlow(currentOutput())
    val routed: StateFlow<RoutedOutput?> = _routed.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    _routed.value = currentOutput()
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    _routed.value = currentOutput()
                }
            },
            handler,
        )
    }

    /**
     * What the HAL says its output mix runs at.
     *
     * The nearest thing to an "output sample rate" the platform exposes. It
     * is the device's preferred rate rather than a per-stream measurement —
     * so it answers "what is everything being converted to", which is the
     * question the Resampler section is asking. Null rather than a guess when
     * the property is missing or unparseable.
     */
    fun halSampleRateHz(): Int? =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    private fun currentOutput(): RoutedOutput? {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrElse { return null }
        // Highest priority first — the order Android itself routes in. The
        // built-in speaker is last because it is always present: it is what
        // is playing only when nothing else is.
        val ordered = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        val device = ordered.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        } ?: outputs.firstOrNull() ?: return null
        return RoutedOutput(name = describe(device), typeLabel = typeLabel(device.type))
    }

    private fun describe(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: typeLabel(device.type)

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        else -> "Audio output"
    }
}
