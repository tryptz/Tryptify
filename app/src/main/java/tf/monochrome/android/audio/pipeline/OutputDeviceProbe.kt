package tf.monochrome.android.audio.pipeline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the sound is going, as far as the framework will say. */
data class RoutedOutput(val name: String, val typeLabel: String, val kind: OutputKind)

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

    /**
     * Whether Android's spatializer is processing a stream of [sampleRate] and
     * [channelCount] on the current output — the chain's own output format,
     * as float, since that is what reaches the platform.
     *
     * `canBeSpatialized` is the exact question: it answers for this format on
     * the route the platform would use now. Null before Android 12L, where
     * there is no spatializer API to ask, and — past "off" and "unavailable",
     * which hold for any stream — when [channelCount] is null because the
     * caller does not know what the chain hands the platform.
     */
    fun spatialAudio(sampleRate: Int?, channelCount: Int?): SpatialAudio? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return null
        return runCatching {
            val spatializer = audioManager.spatializer
            when {
                spatializer.immersiveAudioLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE ->
                    SpatialAudio.UNAVAILABLE
                !spatializer.isAvailable -> SpatialAudio.UNAVAILABLE
                !spatializer.isEnabled -> SpatialAudio.OFF
                sampleRate == null || sampleRate <= 0 || channelCount == null -> null
                spatializer.canBeSpatialized(MEDIA_ATTRIBUTES, streamFormat(sampleRate, channelCount)) ->
                    SpatialAudio.APPLIED
                else -> SpatialAudio.NOT_THIS_STREAM
            }
        }.getOrNull()
    }

    private fun streamFormat(sampleRate: Int, channelCount: Int?): AudioFormat =
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(
                when (channelCount) {
                    1 -> AudioFormat.CHANNEL_OUT_MONO
                    else -> AudioFormat.CHANNEL_OUT_STEREO
                }
            )
            .build()

    private fun currentOutput(): RoutedOutput? {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrElse { return null }
        // Every Bluetooth headset lists a hands-free (SCO) output next to its
        // A2DP one, and media never goes to it outside a call. So it only
        // counts while the phone is in a call mode — then it is first.
        val inCall = audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        // Highest priority first — the order Android itself routes in. The
        // built-in speaker is last because it is always present: it is what
        // is playing only when nothing else is. LE Audio sits beside A2DP:
        // without it an LE Audio headset read as the phone speaker.
        val ordered = listOfNotNull(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO.takeIf { inCall },
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        val device = ordered.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        } ?: outputs.firstOrNull { inCall || it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return null
        return RoutedOutput(
            name = describe(device),
            typeLabel = typeLabel(device.type),
            kind = kindOf(device.type),
        )
    }

    private fun kindOf(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputKind.BLUETOOTH_CLASSIC
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputKind.BLUETOOTH_LE
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputKind.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_HDMI -> OutputKind.HDMI
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
        else -> OutputKind.OTHER
    }

    private fun describe(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: typeLabel(device.type)

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE Audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth hands-free"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        else -> "Audio output"
    }

    private companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
