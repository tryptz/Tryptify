package tf.monochrome.android.ui.eq.dac

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.usb.DacClient
import io.github.chronosauros.contour.usb.DacHandle
import io.github.chronosauros.contour.usb.DacSnapshot
import io.github.chronosauros.contour.usb.sim.SimDusk
import io.github.chronosauros.contour.usb.sim.SimFiio
import io.github.chronosauros.contour.usb.sim.SimIo
import io.github.chronosauros.contour.usb.sim.SimKtMicro
import io.github.chronosauros.contour.usb.sim.SimMoondrop
import io.github.chronosauros.contour.usb.sim.SimWalkPlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.audio.usb.daceq.DacEqDevice
import tf.monochrome.android.audio.usb.daceq.DacLink
import tf.monochrome.android.audio.usb.daceq.DacSendResult

/**
 * A DAC for the page's tests: one of Contour's simulated DACs behind
 * Contour's real client, so a read and a send run the same code as on a
 * phone from the client down to the frames. Needs Robolectric (the client
 * logs and reads the clock).
 */
class SimulatedDac(val target: DeviceTarget) : DacEqDevice {

    private val dac: NativePort = when {
        target.fiio != null -> SimFiio(target)
        target.moondrop != null -> SimMoondrop()
        target.ktMicro != null -> SimKtMicro()
        target.gen1 != null -> SimDusk()
        else -> SimWalkPlay(requireNotNull(target.walkplayProfile))
    }
    private val handle = DacHandle(null, target, "simulated:${target.caps.name}", simulated = { guard -> SimIo(target, dac, guard) })
    private val client = DacClient(
        ApplicationProvider.getApplicationContext<Context>().getSystemService(UsbManager::class.java),
    )

    private val _link = MutableStateFlow<DacLink>(DacLink.None)
    override val link: StateFlow<DacLink> = _link

    var sends = 0
        private set

    fun plugIn(allowed: Boolean = true) {
        _link.value = if (allowed) DacLink.Ready(handle) else DacLink.NeedsPermission(handle)
    }

    fun unplug() {
        _link.value = DacLink.None
    }

    override fun watch() = Unit
    override fun unwatch() = Unit
    override fun requestPermission() = plugIn(allowed = true)

    override suspend fun read(): DacSnapshot = client.readDevice(handle)

    override suspend fun send(profile: Profile, slot: Int?, onProgress: (Float) -> Unit, onRestarting: () -> Unit): DacSendResult {
        sends++
        val written = client.writeProfile(handle, profile, targetSlot = slot, onProgress = onProgress)
        val pending = written.pending
            ?: return DacSendResult(written.verified, written.mismatches, written.readBack, previousIndex = written.previousIndex)
        // A DAC that restarts on save: it leaves the bus and comes back.
        onRestarting()
        _link.value = DacLink.None
        kotlinx.coroutines.delay(100)
        _link.value = DacLink.Ready(handle)
        pending.afterReturn?.invoke(handle)
        val back = client.readDevice(handle)
        val mismatches = pending.verify(back)
        return DacSendResult(mismatches.isEmpty(), mismatches, back)
    }
}
