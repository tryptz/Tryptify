package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.core.native.WalkPlayProfile
import io.github.chronosauros.contour.core.native.WalkPlaySession
import io.github.chronosauros.contour.core.native.WalkPlaySettings
import java.io.IOException

/**
 * SIMULATED WalkPlay DAC (Protocol Micro, Protocol Max, TRN Black Pearl, Moondrop Echo-A) for the review builds (`--es simulate`): a register store that answers the reads the way the
 * units do (the write frame with the read bit) and keeps what it is sent - the bands, the preamp, the settings rows and the steps of the factory reset. Based on the synthetic DAC of the
 * core's WalkPlay tests: it checks frames and order, not firmware. A command it does not know stays silent, like the real ones. Nothing here touches USB; the state lives as long as the process.
 */
internal class SimWalkPlay(private val profile: WalkPlayProfile) : NativePort {
    override val deviceKey = "simulated:${profile.name}"
    private val bandFrames = HashMap<Int, ByteArray>()
    private var slot = if (profile.protocol == DeviceProtocol.TRN) 0 else 101
    private var preamp = 0
    private var mic = 0
    private var filter = 0
    private var work = 0
    private var gain = 0
    private var balanceR0 = 0
    private var balanceR1 = 0
    private var resetSteps = 0
    private val lenByte = if (profile.protocol == DeviceProtocol.TRN) WalkPlay.TRN_BAND_LEN else WalkPlay.BAND_LEN

    init {
        val flat = profile.protocol.flatPlan()
        flat.bands.forEach { w -> bandFrames[w.index] = WalkPlay.bandWriteReport(w, slot, lenByte).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 } }
        preamp = flat.preampDb
        // What the DAC holds when it is "plugged in": a small EQ, written through the session so every register is the one a real write leaves.
        val demo = Profile("sim", "SIM", bands = listOf(
            Band("d1", FilterType.LOW_SHELF, 105.0, 4.0, 0.7),
            Band("d2", FilterType.PEAK, 1000.0, -3.0, 1.5),
            Band("d3", FilterType.HIGH_SHELF, 8000.0, 2.0, 0.7)), preampDb = -3.0, createdAt = 0, updatedAt = 0)
        runCatching { WalkPlaySession(profile, this).write(demo, explicitHold = true) }
        if (profile.id == "trn") { filter = 1; work = 0; gain = 1 }
    }

    private fun report(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray().copyOf(WalkPlay.REPORT_SIZE)

    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = WalkPlay.REPORT_SIZE - 1

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        val b = byteArrayOf(id.toByte()) + payload.copyOf(WalkPlay.REPORT_SIZE - 1)
        val v = b[5].toInt()           // preamp and mic: `01 03 02 00 g`, `01 02 02 80 e`
        val one = b[4].toInt() and 255 // the one-byte rows: `01 11 01 t`, `01 1D 01 t`, `01 19 01 m`
        when (b[2].toInt() and 0xFF) {
            WalkPlay.CMD_PEQ -> bandFrames[b[5].toInt() and 0xFF] = b.copyOf().also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }
            WalkPlay.CMD_GLOBAL_GAIN -> preamp = v
            WalkPlaySettings.CMD_MIC -> mic = v
            WalkPlaySettings.CMD_DAC_FILTER -> filter = one - 1
            WalkPlaySettings.CMD_WORK_MODE -> work = one
            WalkPlaySettings.CMD_GAIN_MODE -> gain = one
            WalkPlaySettings.CMD_BALANCE -> if (one == 0) balanceR0 = b[6].toInt() and 255 else balanceR1 = b[6].toInt() and 255 // `01 16 04 c 00 a 00`
            // the steps of the reset: the vendor's four `01 05 n` and the closing `01 17 00` clear the settings
            0x05 -> if ((b[3].toInt() and 255) in 1..4) resetSteps++
            0x17 -> if (resetSteps == 4) { filter = 0; work = 0; gain = 0; mic = 0; resetSteps = 0 }
        }
    }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        val b = byteArrayOf(id.toByte()) + payload.copyOf(WalkPlay.REPORT_SIZE - 1)
        val reply = when (b[2].toInt() and 0xFF) {
            WalkPlay.CMD_VERSION -> report("4b 80 0c 00").also { "0.6".toByteArray().copyInto(it, 4) }
            WalkPlay.CMD_GLOBAL_GAIN -> report("4b 80 03 02 00").also { it[5] = preamp.toByte() }
            WalkPlay.CMD_PEQ -> bandFrames[b[5].toInt() and 0xFF]?.copyOf()?.also { it[36] = slot.toByte() } ?: silent()
            WalkPlaySettings.CMD_MIC -> report("4b 80 02 02 00").also { it[5] = mic.toByte() }
            WalkPlaySettings.CMD_DAC_FILTER -> if (profile.id == "trn") report("4b 80 11 01 00").also { it[4] = (filter + 1).toByte() } else silent()
            WalkPlaySettings.CMD_WORK_MODE -> if (profile.id == "trn") report("4b 80 1d 01 00").also { it[4] = work.toByte() } else silent()
            WalkPlaySettings.CMD_GAIN_MODE -> if (profile.id == "trn") report("4b 80 19 01 00").also { it[4] = gain.toByte() } else silent()
            WalkPlaySettings.CMD_BALANCE -> if (profile.id == "trn") report("4b 80 16 02 00 00 00").also { it[4] = b[4]; it[6] = (if (b[4].toInt() == 0) balanceR0 else balanceR1).toByte() } else silent()
            else -> silent()
        }
        val back = reply.copyOfRange(1, reply.size)
        if (!matches(back)) throw IOException("Native request timed out or returned invalid state")
        return back
    }

    /** No answer: the session's own timeout, like a command the real unit ignores. */
    private fun silent(): Nothing = throw IOException("Native request timed out or returned invalid state")
}
