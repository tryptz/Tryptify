package tf.monochrome.android.audio.usb.daceq

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.native.KtMicroSaveWait
import io.github.chronosauros.contour.usb.DacClient
import io.github.chronosauros.contour.usb.DacHandle
import io.github.chronosauros.contour.usb.DacSnapshot
import io.github.chronosauros.contour.usb.PendingSave
import io.github.chronosauros.contour.usb.UsbAudioKeepAlive
import io.github.chronosauros.contour.usb.UsbLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.audio.eq.dac.DacEqUntested
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is plugged in, as the DAC EQ page sees it. [untested] is set for a DAC
 * Contour does not know, which the app drives with a proven sibling's
 * commands ([DacEqUntested]): `dac.target` is then the sibling's.
 */
sealed interface DacLink {
    data object None : DacLink

    /**
     * A USB audio device the page does not know: nothing is sent to it.
     * [usbId] is its vendor and product id ("3302:4325"), shown so that its
     * owner can say exactly which device it is.
     */
    data class Unknown(val name: String, val usbId: String = "") : DacLink

    /** A DAC the page knows, which Android has not let the app talk to yet. */
    data class NeedsPermission(val dac: DacHandle, val untested: DacEqUntested.Entry? = null) : DacLink

    data class Ready(val dac: DacHandle, val untested: DacEqUntested.Entry? = null) : DacLink
}

/**
 * The limits the page holds an EQ to for the DAC plugged in, under the name it
 * shows: the DAC's own, or an untested DAC's (its sibling's, inside its own
 * gain range). Null with no DAC the page knows.
 */
val DacLink.caps: DeviceCapabilities?
    get() = when (this) {
        is DacLink.Ready -> untested?.caps() ?: dac.target.caps
        is DacLink.NeedsPermission -> untested?.caps() ?: dac.target.caps
        else -> null
    }

/** The name the page shows for the DAC plugged in; null with none it knows. */
val DacLink.dacName: String? get() = caps?.name

/** The untested entry the DAC plugged in is driven by, if it is one. */
val DacLink.untestedDac: DacEqUntested.Entry?
    get() = when (this) {
        is DacLink.Ready -> untested
        is DacLink.NeedsPermission -> untested
        else -> null
    }

/** Why a DAC that restarts when it saves was not read again. */
enum class DacNotConfirmed { TIMEOUT, PERMISSION, SILENT }

/**
 * How a send ended. [verified] only when the DAC was read back and every
 * register is what was written; [mismatches] says what differed. A DAC that
 * leaves the USB bus to save is read once it is back, and [notConfirmed] says
 * why when that read never happened. [previousIndex] is the EQ bank a
 * Moondrop DAC was on before the send moved it to its writable one.
 */
class DacSendResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val readBack: DacSnapshot?,
    val notConfirmed: DacNotConfirmed? = null,
    val previousIndex: Int? = null,
)

/**
 * A USB DAC as the DAC EQ page uses one: [DacEqController] on a real phone,
 * and in the page's tests one of Contour's simulated DACs.
 */
interface DacEqDevice {
    val link: StateFlow<DacLink>

    /** The page is on screen: follow what is plugged in. Pair with [unwatch]. */
    fun watch()
    fun unwatch()

    /** Shows Android's own "allow this app to use the device" dialog. */
    fun requestPermission()

    /** What the DAC holds now. Reads only. */
    suspend fun read(): DacSnapshot

    /**
     * Writes [profile] and proves it: the DAC is read back and compared. The
     * one call that changes a DAC. [onProgress] goes from 0 to 1;
     * [onRestarting] is told when the write is done and the DAC has left the
     * bus to save it, which can take most of a minute to prove.
     */
    suspend fun send(profile: Profile, slot: Int?, onProgress: (Float) -> Unit, onRestarting: () -> Unit = {}): DacSendResult
}

/**
 * Finds a supported USB DAC, asks Android for it, and reads and writes its
 * EQ through Contour's client (third_party/contour).
 *
 * Only the DAC's HID interface is opened, and only for the length of one
 * read or one send: the audio interfaces are never touched, so Android (or
 * the app's own exclusive USB output) keeps playing through the DAC.
 * Nothing is written except by [send], which the page calls from a held
 * button.
 *
 * Contour's own controller, which this stands in for, also holds its screens'
 * state; only its USB rules are kept here: the session guard, and the wait
 * for a DAC that restarts on save.
 */
@Singleton
class DacEqController @Inject constructor(
    @ApplicationContext private val context: Context,
) : DacEqDevice {
    private val manager: UsbManager? = context.getSystemService(UsbManager::class.java)

    private val _link = MutableStateFlow<DacLink>(DacLink.None)
    override val link: StateFlow<DacLink> = _link.asStateFlow()

    /** Goes up whenever a device leaves the bus: an operation begun before is over. */
    @Volatile
    private var epoch = 0
    private var watchers = 0

    private val client: DacClient? by lazy {
        manager?.let { usb ->
            DacClient(
                manager = usb,
                guardFor = ::sessionGuard,
                audioKeepAlive = { UsbAudioKeepAlive.start(context, it) },
            )
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) epoch++
            refresh()
        }
    }

    override fun watch() {
        if (watchers++ == 0) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }
        refresh()
    }

    override fun unwatch() {
        if (--watchers == 0) {
            runCatching { context.unregisterReceiver(receiver) }
            // Nobody is told about the bus any more: what was last seen there
            // must not greet the next visit as if it were still plugged in.
            _link.value = DacLink.None
            lastSeen = null
        }
        if (watchers < 0) watchers = 0
    }

    /** Looks at the USB bus again. */
    fun refresh() {
        val usb = manager ?: return
        val devices = runCatching { usb.deviceList.values.toList() }.getOrDefault(emptyList())
        noteSeen(devices)
        val known = devices.firstNotNullOfOrNull { DacHandle.of(it) }
        val now: DacLink = when {
            known != null -> {
                // The same unit keeps its handle, so the page is not told
                // about a "new" DAC each time the bus is looked at.
                val dac = handleOf(_link.value)?.takeIf { it.key == known.key } ?: known
                val device = requireNotNull(dac.usb)
                val untested = DacEqUntested.find(device.vendorId, device.productId, device.productName)
                if (usb.hasPermission(device)) DacLink.Ready(dac, untested) else DacLink.NeedsPermission(dac, untested)
            }
            else -> devices.firstOrNull(::isAudio)
                ?.let { DacLink.Unknown(it.productName ?: it.manufacturerName ?: "USB", usbId(it)) }
                ?: DacLink.None
        }
        _link.value = now
    }

    /**
     * Each USB audio device, once, in the log: its ids, its names and its
     * interfaces. It is what tells one DAC from another when a listener asks
     * for theirs to be supported, and what an untested DAC's first report
     * needs. Reads the descriptors Android already holds; asks the device
     * nothing.
     */
    private fun noteSeen(devices: List<UsbDevice>) {
        val audio = devices.filter { isAudio(it) || DacHandle.of(it) != null }
        val seen = audio.joinToString(" | ") { device ->
            val interfaces = (0 until device.interfaceCount).joinToString(", ") { i ->
                val intf = device.getInterface(i)
                val ends = (0 until intf.endpointCount).joinToString(" ") { e ->
                    val end = intf.getEndpoint(e)
                    "%s 0x%02x %d".format(if (end.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", end.address, end.maxPacketSize)
                }
                "if ${intf.id}.${intf.alternateSetting} class ${intf.interfaceClass}/${intf.interfaceSubclass}" + if (ends.isEmpty()) "" else " ($ends)"
            }
            val how = when {
                DacEqUntested.find(device.vendorId, device.productId, device.productName) != null -> "untested, driven as ${DacHandle.of(device)?.target?.caps?.name}"
                DacHandle.of(device) != null -> "known"
                else -> "not known"
            }
            "${usbId(device)} \"${device.productName}\" by \"${device.manufacturerName}\" ($how): $interfaces"
        }
        if (seen == lastSeen) return
        lastSeen = seen
        if (seen.isNotEmpty()) Log.i(TAG, "USB audio: $seen")
    }

    private var lastSeen: String? = null

    private fun usbId(device: UsbDevice) = "%04x:%04x".format(device.vendorId, device.productId)

    override fun requestPermission() {
        val usb = manager ?: return
        val device = (_link.value as? DacLink.NeedsPermission)?.dac?.usb ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val answer = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
        )
        usb.requestPermission(device, answer)
    }

    override suspend fun read(): DacSnapshot = requireClient().readDevice(ready())

    /** A DAC with several slots: moves it to [slot] (nothing is saved) and reads that slot. */
    suspend fun readSlot(slot: Int): DacSnapshot = requireClient().readSlot(ready(), slot)

    override suspend fun send(
        profile: Profile,
        slot: Int?,
        onProgress: (Float) -> Unit,
        onRestarting: () -> Unit,
    ): DacSendResult {
        val dac = ready()
        val written = requireClient().writeProfile(dac, profile, targetSlot = slot, onProgress = onProgress)
        val pending = written.pending
            ?: return DacSendResult(written.verified, written.mismatches, written.readBack, previousIndex = written.previousIndex)
        onRestarting()
        return awaitSave(dac, pending)
    }

    /**
     * A write that ended at the DAC's save, on a DAC that leaves the bus to
     * do it: waits for it to come back, reads it and compares. The rules are
     * Contour's (its DeviceController): 45 s, not counting the time
     * Android's permission dialog is open (some phones ask again after every
     * save); a DAC never seen leaving is read after 8 s; a DAC that is back
     * and answers five reads with silence has to be plugged in again.
     */
    private suspend fun awaitSave(sent: DacHandle, save: PendingSave): DacSendResult {
        UsbLog.line("save: waiting for the DAC to come back (up to ${VERIFY_TIMEOUT_MS / 1000} s)")
        val firstId = sent.usb?.deviceId
        val start = SystemClock.elapsedRealtime()
        var last = start
        var counted = 0L
        var asked = 0L
        var silent = 0
        var left = false
        var afterDone = save.afterReturn == null
        var askedFor: Int? = null
        while (true) {
            delay(VERIFY_POLL_MS)
            refresh()
            val link = _link.value
            val now = SystemClock.elapsedRealtime()
            if (link is DacLink.NeedsPermission) asked += now - last else counted += now - last
            last = now
            if (counted >= VERIFY_TIMEOUT_MS) return notConfirmed(DacNotConfirmed.TIMEOUT)
            if (asked >= VERIFY_PERMISSION_MAX_MS) return notConfirmed(DacNotConfirmed.PERMISSION)
            if (KtMicroSaveWait.silentVerdict(silent) != null) return notConfirmed(DacNotConfirmed.SILENT)
            when (link) {
                DacLink.None, is DacLink.Unknown -> left = true
                is DacLink.NeedsPermission -> {
                    left = true
                    val id = link.dac.usb?.deviceId
                    if (id != askedFor) { askedFor = id; requestPermission() }
                }
                is DacLink.Ready -> {
                    if (link.dac.target.caps.name != sent.target.caps.name) continue
                    if (link.dac.usb?.deviceId != firstId) left = true
                    if (!left && now - start < SAVE_NO_DETACH_MS) continue
                    try {
                        if (!afterDone) {
                            requireNotNull(save.afterReturn)(link.dac)
                            afterDone = true
                        }
                        val back = requireClient().readDevice(link.dac)
                        val mismatches = save.verify(back)
                        UsbLog.line("save: read back: ${if (mismatches.isEmpty()) "VERIFIED" else mismatches.joinToString("; ")}")
                        return DacSendResult(mismatches.isEmpty(), mismatches, back)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        silent++
                        Log.w(TAG, "The DAC is back but a read failed ($silent): ${e.message}")
                    }
                }
            }
        }
    }

    private fun notConfirmed(why: DacNotConfirmed): DacSendResult {
        Log.w(TAG, "A save was not confirmed: $why")
        return DacSendResult(verified = false, mismatches = emptyList(), readBack = null, notConfirmed = why)
    }

    /**
     * Throws once the plug an operation began on is gone: the DAC was pulled
     * out, another was plugged in, or the permission was taken back. Contour's
     * rule, and its words, which its sessions look for.
     */
    private fun sessionGuard(dac: DacHandle): () -> Unit {
        val begun = epoch
        val device = dac.usb
        return {
            val usb = manager
            val gone = device != null && usb != null &&
                (usb.deviceList.values.none { it.deviceId == device.deviceId } || !usb.hasPermission(device))
            if (begun != epoch || gone) throw IOException("DAC SESSION CHANGED; reconnect and retry")
        }
    }

    private fun ready(): DacHandle =
        (_link.value as? DacLink.Ready)?.dac ?: throw IOException("No DAC is connected")

    private fun requireClient(): DacClient = client ?: throw IOException("This phone has no USB host")

    private fun handleOf(link: DacLink): DacHandle? = when (link) {
        is DacLink.Ready -> link.dac
        is DacLink.NeedsPermission -> link.dac
        else -> null
    }

    private fun isAudio(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    private companion object {
        const val TAG = "DacEq"
        const val ACTION_USB_PERMISSION = "tf.monochrome.android.DAC_EQ_USB_PERMISSION"
        const val VERIFY_TIMEOUT_MS = 45_000L
        const val VERIFY_PERMISSION_MAX_MS = 180_000L
        const val VERIFY_POLL_MS = 1_000L
        const val SAVE_NO_DETACH_MS = 8_000L
    }
}
