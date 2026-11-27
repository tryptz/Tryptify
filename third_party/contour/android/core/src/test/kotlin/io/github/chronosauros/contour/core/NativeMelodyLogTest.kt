package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay of a real SnowSky Tiny A cold plug (Pixel, Contour 1.5.1 fix build, logcat 14.10.2026 16:26, USB log lines RX):
 * the DAC plays a USER slot, the opening slot query answers `00` (a boot value), COUNT, PREAMP and bands 0-9 follow, the
 * closing slot query answers `09` (a USER slot, number unknown), then the three USER names are read. Contour 1.5.0 failed
 * this exact exchange with "Active bank changed during read" (commit 1a6f96b fixed it). The reply frames below are copied
 * from that log; the port only hands them out in order and checks that each request is the one the log shows.
 */
class NativeMelodyLogTest {
    /** The RX frames of the log, after report ID 7, trailing zero padding removed. */
    private val rx = listOf(
        "bb 0b 00 00 16 01 00 92 ee",
        "bb 0b 00 01 18 01 0a 97 ee",
        "bb 0b 00 02 17 02 00 00 45 ee",
        "bb 0b 00 03 15 08 00 ff f6 09 60 00 46 02 ea ee",
        "bb 0b 00 04 15 08 01 ff bf 33 cb 01 18 00 eb ee",
        "bb 0b 00 05 15 08 02 00 00 00 64 00 64 00 77 ee",
        "bb 0b 00 06 15 08 03 00 00 00 64 00 64 00 52 ee",
        "bb 0b 00 07 15 08 04 00 00 00 64 00 64 00 57 ee",
        "bb 0b 00 08 15 08 05 00 00 00 64 00 64 00 f3 ee",
        "bb 0b 00 09 15 08 06 00 00 00 64 00 64 00 e3 ee",
        "bb 0b 00 0a 15 08 07 00 00 00 64 00 64 00 c6 ee",
        "bb 0b 00 0b 15 08 08 00 00 00 64 00 64 00 e9 ee",
        "bb 0b 00 0c 15 08 09 00 00 00 64 00 64 00 b3 ee",
        "bb 0b 00 0d 16 01 09 93 ee 00 64 00 64 00 b3 ee", // stale bytes after the terminator, as on the device
        "bb 0b 00 0e 30 09 a0 4d 45 47 41 35 45 53 54 ee a8 ee",
        "bb 0b 00 0f 30 09 a1 4f 52 49 47 49 4e 00 00 ee d2 ee",
        "bb 0b 00 10 30 09 a2 44 55 53 4b 00 44 00 00 ee 7c ee",
    )
    /** The command (and for bands the index) each request of the log asked for, in order. */
    private val requests = listOf(0x16 to null, 0x18 to null, 0x17 to null) + (0..9).map { 0x15 to it } +
        listOf(0x16 to null, 0x30 to 0xA0, 0x30 to 0xA1, 0x30 to 0xA2)

    private class ReplayPort(val requests: List<Pair<Int, Int?>>, val replies: List<ByteArray>) : NativePort {
        override val deviceKey = "tiny-a-cold-plug"
        var next = 0
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a read must not write: ${payload[4]}")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(7, id)
            val (cmd, arg) = requests[next]
            assertEquals(cmd, payload[4].toInt() and 255, "request ${next + 1}")
            if (arg != null) assertEquals(arg, payload[6].toInt() and 255, "request ${next + 1} argument")
            val reply = replies[next++]
            assertTrue(matches(reply), "reply ${next} is not the answer to its request")
            return reply
        }
    }

    private fun frame(hex: String): ByteArray = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray().copyOf(63)

    @Test fun `Tiny A cold plug with a boot slot reply of 00 and a closing reply of 09 reads as a USER slot of unknown number`() {
        val port = ReplayPort(requests, rx.map(::frame))
        val state = NativeSession(DeviceTarget.TINY_A, port).read() as NativeState.Fiio // 1.5.0: "Active bank changed during read"
        assertEquals(requests.size, port.next, "every request of the log was made, in order")
        assertEquals(9, state.slot)
        assertEquals("USER slot (number unknown; DAC reports 9)", state.slotDescription)
        assertEquals(10, state.raw.count)
        assertEquals(10, state.raw.bands.size)
        assertEquals(FiioRegisters(0, 2400, -10, 70, 2), state.raw.bands[0]) // HS 2400 Hz -1.0 dB Q 0.70
        assertEquals(FiioRegisters(1, 13259, -65, 280, 0), state.raw.bands[1]) // PK 13259 Hz -6.5 dB Q 2.80
        assertEquals(FiioRegisters(9, 100, 0, 100, 0), state.raw.bands[9]) // the neutral pad
        assertEquals(mapOf(160 to "MEGA5EST", 161 to "ORIGIN", 162 to "DUSK"), state.names)
        assertEquals(0.0, state.preamp)
    }
}
