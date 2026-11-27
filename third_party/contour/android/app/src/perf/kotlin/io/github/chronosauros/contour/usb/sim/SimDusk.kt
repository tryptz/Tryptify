package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.native.MoondropGen1Catalog
import io.github.chronosauros.contour.core.native.MoondropGen1Codec
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import java.io.IOException

/**
 * SIMULATED Moondrop DUSK-SP for the review builds (`--es simulate dusk`): the cable as the owner's unit read on 11.11.2026 - on firmware preset 1 (DUSK-Default), the Custom bank holding the nine
 * factory PEAK bands at 0 dB - speaking the real gen-1 frames (`MoondropGen1Codec`): it answers the preset and band reads from the meta records it holds, takes a preset select (RAM only), and keeps the meta
 * record of every band meta write (the coefficient sets are acknowledged and dropped: they cannot be read back on the unit either). Like the unit, the commit restarts it onto preset 1 (the Custom bank is kept);
 * the simulation does not leave the USB bus, so the app's wait for the write's proof ends after its no-detach time. Nothing here touches USB.
 */
internal class SimDusk : NativePort {
    override val deviceKey = "simulated:DUSK-SP"
    private var preset = 1
    private val model = MoondropGen1Catalog.duskSp
    private val meta = Array(model.bandCount) { i ->
        intArrayOf(model.factoryFrequenciesHz[i], Math.round(model.factoryQ * 256).toInt(), MoondropGen1Codec.TYPE_PEAK, 0)
    }

    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = MoondropGen1Codec.PAYLOAD_BYTES
    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {}

    private fun put(out: ByteArray, wire: Int, v: Int) { for (k in 0 until 4) out[wire - 1 + k] = (v ushr (8 * k)).toByte() }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        val c = MoondropGen1Codec
        val cmd = c.at(payload, 4); val flag = c.at(payload, 5)
        val out = payload.copyOf()
        out[4] = (flag or 0x80).toByte()
        when {
            cmd == c.CMD_PRESET && flag == c.FLAG_READ -> put(out, 14, preset)
            cmd == c.CMD_BAND_READ && flag == c.FLAG_READ -> {
                val n = c.dword(payload, 10)
                val m = meta.getOrNull(n - 1) ?: throw IOException("Native request timed out or returned invalid state")
                out[1] = 0x06
                put(out, 10, 5); put(out, 14, n); put(out, 18, m[0]); put(out, 22, m[1]); put(out, 26, m[2]); put(out, 30, m[3] and 0xFFFFFF)
            }
            cmd == c.CMD_PRESET && flag == c.FLAG_WRITE -> preset = c.dword(payload, 14)
            cmd == c.CMD_WRITE && flag == c.FLAG_WRITE && c.dword(payload, 10) == c.COMMIT -> preset = model.powerUpPreset
            cmd == c.CMD_WRITE && flag == c.FLAG_WRITE && c.dword(payload, 10) == 0 -> {
                val g = c.dword(payload, 30).let { if (it and 0x800000 != 0) it - 0x1000000 else it }
                meta[c.dword(payload, 14) - 1] = intArrayOf(c.dword(payload, 18), c.dword(payload, 22), c.dword(payload, 26), g)
            }
        }
        if (!matches(out)) throw IOException("Native request timed out or returned invalid state")
        return out
    }
}
