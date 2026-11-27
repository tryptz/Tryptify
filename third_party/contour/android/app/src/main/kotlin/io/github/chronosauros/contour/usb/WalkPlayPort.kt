package io.github.chronosauros.contour.usb

import android.os.SystemClock
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort

/**
 * The app's transport for report 0x4B: the WalkPlay session ([io.github.chronosauros.contour.core.native.WalkPlaySession], 63 bytes after the
 * report ID) and the Contour 1.6 family sessions (Moondrop Dawn Pro 2, Kiwi Ears Allegro Mini, Moondrop Echo-A), which use the same report ID.
 * Protocol Micro, Max and TRN keep the way they were opened since 1.2 (first HID interface, plain reports); every 1.6 family is opened through
 * its report descriptor and uses the native calls. [tick] is told after every report sent and every reply taken (the progress of a send).
 * The session owns the frames, the pauses and the attempts of a request; this port sends once, drops the stale input first and waits 200 ms.
 */
internal class WalkPlayPort(
    private val t: HidIo,
    device: DacHandle,
    /** The report this port carries: 0x4B (WalkPlay and the 1.6 families), or 1 for the Moondrop gen-1 cable (DUSK-SP). */
    private val reportId: Int = WalkPlay.REPORT_ID,
    private val tick: () -> Unit = {},
) : NativePort {
    override val deviceKey = device.key
    /** When the first report of a write went out and when the last one (and its pause) was done; 0 = none yet. */
    var firstSendMs = 0L
        private set
    var lastSendEndMs = 0L
        private set

    override fun guard() = t.checkSession()

    /** Only a descriptor-proven (native) HID session knows its report sizes; the Moondrop session asks for them. */
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = t.nativePayloadSize(id, kind)

    override fun pause(ms: Long) {
        t.checkSession()
        Thread.sleep(ms)
        t.checkSession()
        lastSendEndMs = SystemClock.elapsedRealtime()
    }

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        require(id == reportId && kind == NativeHidReports.Kind.OUTPUT) { "Only output report 0x%02x".format(reportId) }
        if (firstSendMs == 0L) firstSendMs = SystemClock.elapsedRealtime()
        if (t.isNative) t.sendNative(id, kind, payload) else t.send(byteArrayOf(id.toByte()) + payload)
        lastSendEndMs = SystemClock.elapsedRealtime()
        tick()
    }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        require(id == reportId) { "Only report 0x%02x".format(reportId) }
        val reply = if (t.isNative) t.requestNative(id, payload, matches)
        else t.requestOnce(byteArrayOf(id.toByte()) + payload) { it.size > 1 && it[0] == id.toByte() && matches(it.copyOfRange(1, it.size)) }
            .let { it.copyOfRange(1, it.size) }
        tick()
        return reply
    }

    override fun log(message: String) = UsbLog.line(message)
}
