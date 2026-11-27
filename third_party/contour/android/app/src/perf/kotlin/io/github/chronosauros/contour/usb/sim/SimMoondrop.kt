package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import java.io.IOException

/**
 * SIMULATED Moondrop Dawn Pro 2 for the review builds (`--es simulate dawn`): banks of eight band replies (the unit's own read log is bank 9, the one it was found on; Contour's bank
 * 7 starts as its copy; bank 3 is one of its presets), the active EQ index, Global Gain and Pre Gain; it keeps what it is sent. Based on the synthetic Dawn of the core's family tests:
 * a band lands in the bank its tag names and the index switch takes; the Pre Gain reply layout is the session's own guess (the real one is unproven). Nothing here touches USB.
 */
internal class SimMoondrop : NativePort {
    override val deviceKey = "simulated:Dawn Pro 2"
    private var index = 9
    private var preGainQ8 = -768
    private var globalQ8 = 0
    private val banks = HashMap<Int, Array<ByteArray>>()
    private val shape = NativeHidReports.parse(SimBench.bytes(SimBench.DAWN_DESCRIPTOR))

    init {
        val seed = SimBench.frames(SimBench.DAWN_READ)
        val bands = Array(8) { i -> seed[1 + i].second.single().copyOfRange(1, 64) }
        banks[9] = bands
        banks[7] = Array(8) { i -> bands[i].copyOf().also { it[35] = 7 } }
        banks[3] = Array(8) { i -> bands[i].copyOf().also { it[35] = 3 } }
    }

    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = shape.payloadSize(id, kind)

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        val p = payload.copyOf(63)
        fun q8() = ((p[3].toInt() and 255) or (p[4].toInt() shl 8)).toShort().toInt()
        when {
            p[0].toInt() == 1 && p[1].toInt() == 0x09 -> {
                val bank = p[35].toInt() and 255
                banks.getOrPut(bank) { Array(8) { ByteArray(63) } }[p[4].toInt()] = p.copyOf().also { it[0] = 0x80.toByte(); it[2] = 0x18 }
            }
            p[0].toInt() == 1 && p[1].toInt() == 0x03 -> globalQ8 = q8()
            p[0].toInt() == 1 && p[1].toInt() == 0x0F -> index = p[3].toInt() and 255
            p[0].toInt() == 1 && p[1].toInt() == 0x23 -> preGainQ8 = q8()
        }
    }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        val out = ByteArray(63)
        when (payload[1].toInt()) {
            0x0F -> { out[0] = 0x80.toByte(); out[1] = 0x0F; out[3] = index.toByte() }
            0x09 -> (banks[index] ?: banks.getValue(9))[payload[4].toInt()].copyInto(out)
            0x03 -> { out[0] = 0x80.toByte(); out[1] = 0x03; out[2] = 2; out[3] = globalQ8.toByte(); out[4] = (globalQ8 shr 8).toByte() }
            0x23 -> { out[0] = 0x80.toByte(); out[1] = 0x23; out[2] = 2; out[3] = preGainQ8.toByte(); out[4] = (preGainQ8 shr 8).toByte() }
            else -> throw IOException("Native request timed out or returned invalid state")
        }
        if (!matches(out)) throw IOException("Native request timed out or returned invalid state")
        return out
    }
}
