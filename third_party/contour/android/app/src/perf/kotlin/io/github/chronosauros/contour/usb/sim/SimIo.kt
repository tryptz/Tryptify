package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.usb.HidIo

/**
 * The connection of a simulated DAC (review builds): the [HidIo] the sessions talk to, answered by [dac] instead of a USB endpoint. A report goes in, the DAC's answer comes straight
 * back, so no queue of input reports builds up. [guard] is the session guard of the operation, as on a real connection.
 */
internal class SimIo(private val target: DeviceTarget, private val dac: NativePort, private val guard: () -> Unit) : HidIo {
    /** The Protocol Micro, Max and TRN are opened the old way (plain reports); everything else is descriptor-proven (native). */
    override val isNative: Boolean get() = target.walkplay == null
    override fun nativePayloadSize(id: Int, kind: NativeHidReports.Kind): Int = dac.payloadSize(id, kind)
    override fun checkSession() = guard()
    override fun receive(timeoutMs: Int): ByteArray? = null

    override fun send(report: ByteArray) = dac.send(report[0].toInt() and 255, NativeHidReports.Kind.OUTPUT, report.copyOfRange(1, report.size))

    override fun requestOnce(report: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        val id = report[0].toInt() and 255
        checkSession()
        val reply = dac.request(id, report.copyOfRange(1, report.size)) { matches(byteArrayOf(id.toByte()) + it) }
        checkSession()
        return byteArrayOf(id.toByte()) + reply
    }

    override fun sendNative(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        checkSession()
        dac.send(id, kind, payload)
        checkSession()
    }

    override fun requestNative(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        checkSession()
        return dac.request(id, payload, matches).also { checkSession() }
    }

    override fun close() {}
}
