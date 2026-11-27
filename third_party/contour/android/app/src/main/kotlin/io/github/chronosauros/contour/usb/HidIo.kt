package io.github.chronosauros.contour.usb

import io.github.chronosauros.contour.core.native.NativeHidReports
import java.io.Closeable

/**
 * What the sessions need of an open HID connection to a DAC: [HidTransport] on a real one; in a review build (`--es simulate`) a simulated DAC
 * ([DacHandle.simulated]) answers in its place, so the code above this interface runs unchanged on both. Blocking: the single USB thread only.
 */
interface HidIo : Closeable {
    /** Opened through its HID report descriptor (FiiO, native Moondrop, KT Micro, Echo-A): the report sizes are proven. */
    val isNative: Boolean
    fun nativePayloadSize(id: Int, kind: NativeHidReports.Kind): Int
    /** Throws once the connection this operation started on has ended. */
    fun checkSession()
    fun send(report: ByteArray)
    /** One input report, or null after [timeoutMs]. */
    fun receive(timeoutMs: Int): ByteArray?
    fun requestOnce(report: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
    fun sendNative(id: Int, kind: NativeHidReports.Kind, payload: ByteArray)
    fun requestNative(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
}
