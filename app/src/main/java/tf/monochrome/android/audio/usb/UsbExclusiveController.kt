package tf.monochrome.android.audio.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the user-facing "Exclusive USB DAC" toggle to the actual
 * libusb driver lifecycle, and exposes an honest status flow the UI
 * can render. Without this, the Settings switch is just a persisted
 * boolean — there's no way for the user to tell whether their DAC is
 * actually being claimed.
 *
 * Reachable states by stage:
 *  - Stage 1 (now): up to [Status.DeviceOpen] — libusb_wrap_sys_device
 *    succeeded against the DAC's fd.
 *  - Stage 2: [Status.InterfaceClaimed] — UAC2 streaming interface
 *    claimed and alt setting matching the negotiated rate selected.
 *  - Stage 3: [Status.Streaming] — iso transfer pump is active and
 *    [LibusbAudioSink] is the configured sink in PlaybackService.
 *
 * Concurrency: a single reconcile coroutine consumes a Channel of
 * "something changed" events. The pref collector and the
 * ATTACH/DETACH BroadcastReceiver both feed the channel, so reconcile
 * never overlaps with itself even though its inputs fan in from two
 * threads.
 */
@Singleton
class UsbExclusiveController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val driver: LibusbUacDriver,
    private val preferences: PreferencesManager,
) {
    enum class Status {
        Disabled,
        NoDevice,
        AwaitingPermission,
        /** Stage 1 ceiling — libusb opened the fd. */
        DeviceOpen,
        /** Stage 2 — streaming interface claimed. */
        InterfaceClaimed,
        /** Stage 3 — iso pump live. */
        Streaming,
        /** Open or claim failed — usually means Developer Options'
         *  "Disable USB audio routing" is still OFF. */
        Error,
    }

    private val _status = MutableStateFlow(Status.Disabled)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** What the iso pump is currently doing — null when not streaming.
     *  Forwarded straight from the driver so the UI binds to one place
     *  (the controller) for everything bypass-related instead of
     *  reaching into the driver from ViewModels. */
    val diagnostics: StateFlow<BypassDiagnostics?> = driver.diagnostics

    /** Categorised + detailed reason the most recent start() failed.
     *  Null when bypass is succeeding or hasn't been attempted. */
    val lastStartError: StateFlow<StartFailure?> = driver.lastStartError

    /** GET_RANGE inventory — what rates the DAC actually supports. */
    val supportedRates: StateFlow<List<ClockRateRange>> = driver.supportedRates

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var enabled = false
    private val tick = Channel<Unit>(Channel.CONFLATED)

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> tick.trySend(Unit)
            }
        }
    }

    /** Called once from [tf.monochrome.android.MonochromeApp.onCreate]. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(attachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(attachReceiver, filter)
        }

        scope.launch {
            preferences.usbExclusiveBitPerfectEnabled
                .distinctUntilChanged()
                .collect {
                    enabled = it
                    tick.trySend(Unit)
                }
        }
        scope.launch {
            for (ignored in tick) reconcile()
        }
        // Mirror the driver's streaming flag onto our public Status so
        // Settings UI flips DeviceOpen → Streaming the moment
        // LibusbAudioSink.configure() succeeds in starting the iso
        // pump for a track. If the pump tears down (track end, flush)
        // we fall back to DeviceOpen, which is honest: the DAC handle
        // is still ours, just no audio is flowing.
        scope.launch {
            driver.isStreaming.collect { streaming ->
                if (streaming) {
                    _status.value = Status.Streaming
                } else if (_status.value == Status.Streaming) {
                    _status.value = if (driver.isOpen.value) Status.DeviceOpen else Status.NoDevice
                }
            }
        }
        // When LibusbAudioSink tries to start the iso pump and fails
        // (most common reason: the kernel UAC driver still owns the
        // streaming interface because Developer Options → Disable USB
        // audio routing is OFF), the driver records a reason — bump
        // status to Error so the user gets actionable text instead of
        // a stale "DAC handle acquired" line that doesn't say why
        // bypass isn't engaging.
        scope.launch {
            driver.lastStartError.collect { err ->
                if (err != null && enabled && !driver.isStreaming.value) {
                    _status.value = Status.Error
                }
            }
        }
    }

    private suspend fun reconcile() {
        if (!enabled) {
            if (driver.isOpen.value) driver.close()
            _status.value = Status.Disabled
            return
        }
        val device = findAudioDevice() ?: run {
            if (driver.isOpen.value) driver.close()
            _status.value = Status.NoDevice
            return
        }
        if (!usbManager.hasPermission(device)) {
            _status.value = Status.AwaitingPermission
            val granted = driver.requestPermission(device)
            if (!granted) {
                _status.value = Status.AwaitingPermission
                return
            }
        }
        val opened = driver.open(device)
        if (!opened) {
            _status.value = Status.Error
            Log.w(TAG,
                "driver.open failed — likely Developer Options → " +
                "Disable USB audio routing is OFF (kernel UAC driver " +
                "still owns the streaming interface)."
            )
            return
        }
        _status.value = Status.DeviceOpen
    }

    /** First attached device that exposes USB Audio class on any
     *  interface. Iteration order isn't guaranteed by UsbManager but
     *  in practice there's only ever one DAC plugged at a time. */
    private fun findAudioDevice(): UsbDevice? {
        for (dev in usbManager.deviceList.values) {
            for (i in 0 until dev.interfaceCount) {
                if (dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    return dev
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "UsbExclusiveCtl"
    }
}
