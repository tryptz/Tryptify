package io.github.chronosauros.contour.usb.sim

import io.github.chronosauros.contour.core.native.KtMicroCodec
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import java.io.IOException

/**
 * SIMULATED Kiwi Ears Allegro Mini for the review builds (`--es simulate allegro`): the register store of the owner's read log with the EQ switched on and Global Gain at step 13
 * (0 dB); a write is acknowledged with `03 00 00 00`. Based on the synthetic KT Micro of the core's family tests. Unlike the unit, SAVE does not take it off the USB bus: it keeps
 * answering, so the app's wait for a write to be proven ends with the first read after its no-detach time. Nothing here touches USB.
 */
internal class SimKtMicro : NativePort {
    override val deviceKey = "simulated:Allegro Mini"
    private val regs = HashMap<Int, ByteArray>()

    init {
        SimBench.frames(SimBench.ALLEGRO_READ).forEach { (_, rx) -> rx.forEach { r -> regs[r[1].toInt() and 255] = r.copyOfRange(7, 11) } }
        regs[KtMicroCodec.EQ_ENABLE_REGISTER] = regs.getValue(KtMicroCodec.EQ_ENABLE_REGISTER).copyOf().also { it[0] = KtMicroCodec.EQ_ON.toByte() }
        regs[KtMicroCodec.GLOBAL_GAIN_REGISTER] = byteArrayOf(KtMicroCodec.GLOBAL_GAIN_STEP_DEFAULT.toByte(), 0, 0, 0)
    }

    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = 63

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        // only SAVE is sent without an answer; the registers are already the DAC's own
    }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        val reg = payload[0].toInt() and 255
        val cmd = payload[4].toInt() and 255
        val out = payload.copyOf()
        if (cmd == KtMicroCodec.WRITE) {
            regs[reg] = payload.copyOfRange(6, 10)
            byteArrayOf(3, 0, 0, 0).copyInto(out, 6)
        } else {
            (regs[reg] ?: throw IOException("Native request timed out or returned invalid state")).copyInto(out, 6)
        }
        if (!matches(out)) throw IOException("Native request timed out or returned invalid state")
        return out
    }
}
