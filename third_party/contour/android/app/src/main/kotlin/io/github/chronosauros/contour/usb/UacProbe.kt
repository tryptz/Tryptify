package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.chronosauros.contour.core.UacDescriptor
import io.github.chronosauros.contour.core.native.ProbeReport

/**
 * The DAC probe's USB Audio Class layer: what the DAC's own audio descriptors say about its hardware mute and volume, and what the controls
 * hold now. GET requests only, as control transfers on a connection that claims nothing (the "SHARED" path of advBeta's [UacVolume], which this
 * borrows the unit walk and the 1/256 dB decoding from): music keeps playing. Never `claimInterface`, never SET_CUR. A request the DAC or the
 * kernel refuses is logged and the probe goes on.
 */
object UacProbe {
    private const val LAYER = "uac"
    private const val TIMEOUT_MS = 500
    private const val MUTE = 0x01
    private const val VOLUME = 0x02
    private const val CHUNK = 64

    fun run(manager: UsbManager, device: UsbDevice, report: ProbeReport) {
        val c = manager.openDevice(device)
        if (c == null) { report.error(LAYER, "open", "openDevice failed (permission?)"); return }
        try {
            val raw = c.rawDescriptors
            if (raw == null) { report.error(LAYER, "descriptors", "no raw descriptors"); return }
            report.note(LAYER, "raw descriptors ${raw.size} bytes")
            for (o in raw.indices step CHUNK) report.note(LAYER, "raw+%04x %s".format(o, UsbLog.hex(raw.copyOfRange(o, minOf(raw.size, o + CHUNK)))))
            val ac = UacDescriptor.parse(raw)
            if (ac == null) { report.note(LAYER, "no AudioControl interface"); return }
            report.note(LAYER, "AudioControl interface ${ac.interfaceNumber} bcdADC 0x%04x (${ac.version})".format(ac.bcdAdc))
            ac.units.forEach { report.note(LAYER, "unit ${ac.describe(it)}") }
            val paths = ac.playbackPaths()
            if (paths.isEmpty()) report.note(LAYER, "playback path: none found")
            paths.forEach { p -> report.note(LAYER, "playback path " + p.joinToString(" -> ") { id -> ac.units.first { it.id == id }.let { u -> ac.describe(u).substringBefore(' ') + "(" + "0x%02x".format(id) + ")" } }) }
            val playback = ac.playbackFeatureUnits().map { it.id }.toSet()
            for (fu in ac.featureUnits) {
                report.note(LAYER, "FU 0x%02x is on the ${if (fu.id in playback) "playback path" else "capture / other path"}".format(fu.id))
                for (ch in fu.channels) {
                    if (ch.hasMute) {
                        get(c, report, ac, fu.id, "mute", MUTE, ch.index, if (ac.uac2) 0x01 else 0x81, 1) { b, n -> if (n < 1) "short reply" else "${b[0].toInt() and 255}" }
                    }
                    if (ch.hasVolume) {
                        get(c, report, ac, fu.id, "vol-cur", VOLUME, ch.index, if (ac.uac2) 0x01 else 0x81, 2) { b, n -> UacDescriptor.volumeText(b, n) }
                        if (ac.uac2) {
                            get(c, report, ac, fu.id, "vol-range", VOLUME, ch.index, 0x02, 2 + 6 * 4) { b, n -> UacDescriptor.volumeRangeText(b, n) }
                        } else {
                            get(c, report, ac, fu.id, "vol-min", VOLUME, ch.index, 0x82, 2) { b, n -> UacDescriptor.volumeText(b, n) }
                            get(c, report, ac, fu.id, "vol-max", VOLUME, ch.index, 0x83, 2) { b, n -> UacDescriptor.volumeText(b, n) }
                            get(c, report, ac, fu.id, "vol-res", VOLUME, ch.index, 0x84, 2) { b, n -> UacDescriptor.volumeText(b, n) }
                        }
                    }
                }
            }
        } finally {
            c.close()
        }
    }

    /** One GET (bmRequestType 0xA1: class, interface, device to host) for control [selector] of unit [unit] on [channel]. */
    private fun get(
        c: android.hardware.usb.UsbDeviceConnection, report: ProbeReport, ac: UacDescriptor.AudioControl, unit: Int,
        what: String, selector: Int, channel: Int, request: Int, len: Int, text: (ByteArray, Int) -> String,
    ) {
        val wValue = (selector shl 8) or channel
        val wIndex = (unit shl 8) or ac.interfaceNumber
        val cmd = "FU%02x-ch%d-%s".format(unit, channel, what)
        require(request in setOf(0x01, 0x02, 0x81, 0x82, 0x83, 0x84)) // GET_CUR, GET_RANGE, GET_CUR / MIN / MAX / RES (UAC 1.0): never SET_CUR (0x01 with 0x21)
        report.tx(LAYER, cmd, "a1 %02x %04x %04x %04x".format(request, wValue, wIndex, len))
        try {
            val buf = ByteArray(len)
            val n = c.controlTransfer(0xA1, request, wValue, wIndex, buf, len, TIMEOUT_MS)
            if (n < 0) report.error(LAYER, cmd, "controlTransfer returned $n")
            else if (n == 0) report.noReply(LAYER, cmd)
            else report.rx(LAYER, cmd, "${UsbLog.hex(buf, n)} = ${text(buf, n)}")
        } catch (e: Exception) {
            report.error(LAYER, cmd, e.message ?: e.javaClass.simpleName)
        }
    }
}
