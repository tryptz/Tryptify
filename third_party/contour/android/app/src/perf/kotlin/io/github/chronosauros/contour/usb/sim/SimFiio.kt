package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.native.FiioCodec
import io.github.chronosauros.contour.core.native.FiioRegisters
import io.github.chronosauros.contour.core.native.FiioReset
import io.github.chronosauros.contour.core.native.FiioSnapshot
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import java.io.IOException

/**
 * SIMULATED FiiO or SnowSky DAC (KA15, K13 R2R, SnowSky Melody, Tiny A) for the review builds (`--es simulate`): the USER slots with their names and registers, the settings and
 * the header reads, an echo for every write, the slot register always right. Based on the synthetic DAC of the core's reset test: it checks the frames and their order, not
 * firmware. The KA15 takes its SAVE as a plain send and names its slots by position, the SnowSky answers SAVE with `00 ee` and names by slot id; the K13 R2R has no names.
 * Nothing here touches USB; the state lives as long as the process.
 */
internal class SimFiio(target: DeviceTarget) : NativePort {
    private val c = FiioCodec(requireNotNull(target.fiio))
    private val config = c.config
    /** KA15 and K13: the name frames address the USER slot by its position; the SnowSky DACs by its id. */
    private val byPosition = !config.nameBySlotId
    override val deviceKey = "simulated:${config.productName}"

    private class Slot(var name: String, var snap: FiioSnapshot)
    private val slots = LinkedHashMap<Int, Slot>()
    private var active = target.destinationSlot ?: config.userSlots.min()
    private val settings = LinkedHashMap<Int, List<Int>>()

    init {
        // What the slots hold when the DAC is "plugged in": a flat one and a few with an EQ, so slot switching shows different graphs.
        val demos = listOf(
            "FLAT" to (0.0 to emptyList<Band>()),
            "WARM" to (-3.0 to listOf(Band("w1", FilterType.LOW_SHELF, 105.0, 4.0, 0.7), Band("w2", FilterType.PEAK, 3000.0, -2.0, 1.5))),
            "BRIGHT" to (-2.0 to listOf(Band("b1", FilterType.PEAK, 250.0, -2.5, 1.0), Band("b2", FilterType.HIGH_SHELF, 8000.0, 3.0, 0.7))),
        )
        config.userSlots.sorted().forEachIndexed { i, slot ->
            val (name, eq) = demos.getOrNull(i) ?: ("USER${i + 1}" to (0.0 to emptyList()))
            seed(slot, name.take(config.nameLength), eq.first, eq.second)
        }
        settingsFrom(config.settingsPreview)
    }

    private fun u(p: ByteArray, i: Int) = p[i].toInt() and 255
    private fun word(v: Int) = listOf((v ushr 8) and 255, v and 255)
    private fun cur() = slots.getValue(active)
    private fun nameSlot(i: Int) = if (byPosition) config.userSlots.sorted()[i] else i

    private fun snap(slot: Int, preampDb: Double, bands: List<Band>) =
        c.planOnExplicitSend(c.padToDeviceCount(bands), preampDb, slot, true).expected

    private fun seed(slot: Int, name: String, preampDb: Double, bands: List<Band>) { slots[slot] = Slot(name, snap(slot, preampDb, bands)) }

    /** The settings rows' raw reply bytes ([values] by row id, 0 for a row without one) and the two header reads (sample rate 48 kHz, firmware 1.1.6). */
    private fun settingsFrom(values: Map<String, Int>) {
        settings.clear()
        for (s in config.settings) {
            val cmd = (s.read as? io.github.chronosauros.contour.core.native.SettingRead.FiioGet)?.command ?: continue
            val v = values[s.id] ?: 0
            // the balance reads as (left, right) attenuation steps; every other row is one byte
            settings[cmd] = if (s.id == "balance") (if (v < 0) listOf(0, -v) else listOf(v, 0)) else listOf(v)
        }
        for (i in config.info) settings[i.command] = if (i.id == "fw") listOf(1, 1, 6) else listOf(2)
    }

    private fun factorySettings() = settingsFrom(config.settings.associate { it.id to (if (it.id == "volume") 30 else 0) })

    override fun guard() {}
    override fun pause(ms: Long) {}

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        val p = payload.copyOf(63)
        if (u(p, 0) != 0xAA) throw IOException("a send of a query")
        mutate(u(p, 4), p)
    }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        val p = payload.copyOf(63)
        val reply = if (u(p, 0) == 0xBB) read(u(p, 4), p) else mutate(u(p, 4), p)
        if (!matches(reply)) throw IOException("Native request timed out or returned invalid state")
        return reply
    }

    private fun read(cmd: Int, p: ByteArray): ByteArray {
        val data: List<Int> = when (cmd) {
            0x16 -> listOf(active)
            0x18 -> listOf(cur().snap.count)
            0x17 -> word(cur().snap.preampTenths and 0xFFFF)
            0x15 -> cur().snap.bands[u(p, 6)].let { listOf(it.index) + word(it.gainTenths and 0xFFFF) + word(it.frequencyHz) + word(it.qHundredths) + it.typeCode }
            0x30 -> slots.getValue(nameSlot(u(p, 6))).name.map { it.code }.let { listOf(u(p, 6)) + (it + List(9) { 0 }).take(9) }
            // a command the DAC does not know stays silent, as the real ones do (the row then says NO REPLY)
            else -> settings[cmd] ?: throw IOException("Native request timed out or returned invalid state")
        }
        val len = if (cmd == 0x30) (if (config.nameBySlotId) 9 else 8) else data.size
        return (listOf(0xBB, 0x0B, 0, 0, cmd, len) + data + (if (cmd == 0x0B) listOf(0xEE) else listOf(0, 0xEE))).map { it.toByte() }.toByteArray().copyOf(63)
    }

    /** One write frame (`aa 0a ...`): applies it and returns its echo, the bare `00 ee` answer for SAVE. */
    private fun mutate(cmd: Int, p: ByteArray): ByteArray {
        val echo = p.copyOf(63)
        fun v(i: Int) = (u(p, i) shl 8) or u(p, i + 1)
        when (cmd) {
            0x16 -> active = u(p, 6)
            0x17 -> cur().snap = cur().snap.copy(preampTenths = v(6).toShort().toInt())
            0x18 -> cur().snap = cur().snap.copy(count = u(p, 6), bands = List(u(p, 6)) { cur().snap.bands.getOrNull(it) ?: FiioRegisters(it, 1000, 0, 100, 0) })
            0x15 -> cur().snap = cur().snap.copy(bands = cur().snap.bands.map { if (it.index == u(p, 6)) FiioRegisters(u(p, 6), v(9), v(7).toShort().toInt(), v(11), u(p, 13)) else it })
            0x30 -> {
                val len = u(p, 5)
                slots.getValue(nameSlot(u(p, 6))).name = (7 until 6 + len).map { u(p, it) }.takeWhile { it != 0 }.map { it.toChar() }.joinToString("").uppercase()
                if (byPosition) return echo // the KA15 echoes the frame as sent
                val out = ByteArray(63); p.copyInto(out, 0, 0, 6 + len); out[5] = 9; out[17] = 0xEE.toByte()
                return out
            }
            // SAVE: `00 EE 00 <counter> 00`; the registers are already the DAC's own
            config.saveCommand -> return byteArrayOf(0, 0xEE.toByte(), 0, 1, 0).copyOf(63)
            FiioReset.COMMAND -> {
                // the factory reset: every USER slot back to its stock name and a flat EQ, the settings to their defaults
                slots.keys.forEachIndexed { i, s -> slots.getValue(s).name = "USER${i + 1}"; slots.getValue(s).snap = snap(s, 0.0, emptyList()) }
                factorySettings()
            }
            else -> settings[cmd] = (0 until u(p, 5)).map { u(p, 6 + it) }
        }
        return echo
    }
}
