package tf.monochrome.android.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Kotlin face of the libusb-backed USB Audio Class direct-output
 * driver. Mirrors UAPP's bypass approach: we open the DAC's USB file
 * descriptor through Android's UsbManager, hand it to libusb via
 * libusb_wrap_sys_device, and run an isochronous-transfer pump on a
 * dedicated audio thread that never touches AudioTrack.
 *
 * Stage 1 (this commit) — scaffolding: permission flow, native open /
 * close, state flows. Stage 2 will add UAC2 descriptor enumeration and
 * the alt-setting negotiation; Stage 3 adds the iso transfer pump.
 *
 * Permission flow: the user must explicitly grant Android's USB
 * permission to our package for the specific DAC. UsbManager broadcasts
 * `ACTION_USB_PERMISSION` with the result; we wait for it via a
 * suspending function. On most non-rooted devices the user must also
 * have toggled "Disable USB audio routing" in Developer Options before
 * libusb_claim_interface succeeds — Settings UI surfaces this.
 */
@Singleton
class LibusbUacDriver @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** Device the driver currently owns, or null. */
    private val _device = MutableStateFlow<UsbDevice?>(null)
    val device: StateFlow<UsbDevice?> = _device.asStateFlow()

    private var connection: UsbDeviceConnection? = null

    init {
        UsbNativeLoader.ensureLoaded()
        nativeInit()
    }

    /**
     * Suspends until either (a) Android already granted permission for
     * [device] (returns true immediately) or (b) the user accepts the
     * system permission dialog. Returns false if the user denies or
     * UsbManager isn't reachable.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    runCatching { ctx.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(granted)
                }
            }
            // Android 14+ requires explicit RECEIVER_NOT_EXPORTED for
            // app-private broadcasts.
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    /**
     * Opens the device for exclusive PCM output. Caller must already
     * hold permission (see [requestPermission]) and the device should
     * be a UAC class-1-device (audio class). Returns false on any
     * failure — call sites surface a Snackbar / error UI.
     */
    fun open(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "open() called without permission for $device")
            return false
        }
        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "UsbManager.openDevice returned null for $device")
            return false
        }
        val fd = conn.fileDescriptor
        if (fd < 0) {
            Log.e(TAG, "UsbDeviceConnection has invalid fd")
            conn.close()
            return false
        }
        val ok = nativeOpen(fd)
        if (!ok) {
            conn.close()
            return false
        }
        connection = conn
        _device.value = device
        _isOpen.value = true
        return true
    }

    fun close() {
        if (connection == null && !nativeIsOpen()) return
        nativeClose()
        connection?.close()
        connection = null
        _device.value = null
        _isOpen.value = false
    }

    /**
     * Negotiates a UAC2 alt setting matching [sampleRate]/[bitsPerSample]/[channels],
     * claims the streaming interface, sets the clock-source rate via
     * a class-specific control transfer, and spins up the iso pump.
     * Returns false on any failure (logged with TAG "LibusbUacDriver");
     * LibusbAudioSink then falls back to its DefaultAudioSink delegate.
     *
     * Caveats today:
     *  - Only rates that divide evenly into 8000 (HS) or 1000 (FS) Hz
     *    work — i.e. 48 / 96 / 192 kHz family. 44.1 / 88.2 / 176.4 are
     *    rejected with a clear logcat line; fixing that needs the
     *    fractional-packet accumulator + feedback-EP reader (next).
     *  - No DSP / EQ / ProjectM tap on this path yet (they live inside
     *    DefaultAudioSink's processor chain). Exclusive mode is raw
     *    bit-perfect output for now; chain re-integration is a
     *    follow-up commit.
     */
    fun start(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean {
        val ok = nativeStart(sampleRate, bitsPerSample, channels)
        _isStreaming.value = ok
        return ok
    }

    fun stop() {
        nativeStop()
        _isStreaming.value = false
    }

    fun nativeStreaming(): Boolean = nativeIsStreaming()

    /** Number of PCM frames the driver can accept right now. */
    fun writableFrames(): Int = nativeWritableFrames()

    /** Pushes [frames] frames from [buffer] (direct, native-byte-order). */
    fun write(buffer: ByteBuffer, frames: Int): Int = nativeWrite(buffer, frames)

    private external fun nativeInit(): Boolean
    private external fun nativeOpen(fd: Int): Boolean
    private external fun nativeClose()
    private external fun nativeIsOpen(): Boolean
    private external fun nativeStart(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean
    private external fun nativeStop()
    private external fun nativeIsStreaming(): Boolean
    private external fun nativeWrite(buffer: ByteBuffer, frames: Int): Int
    private external fun nativeWritableFrames(): Int

    companion object {
        private const val TAG = "LibusbUacDriver"
        const val ACTION_USB_PERMISSION = "tf.monochrome.android.USB_PERMISSION"
    }
}
