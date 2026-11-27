package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replay of a bench log (format of the PC measuring rig's bench tool) through the app's own NativeSession, like
 * NativeMelodyLogTest: every report the session sends must be the log's TX byte for byte (sequence byte and CRC
 * included), and each request gets the first logged RX that the session's own matcher accepts. A rig log from real
 * hardware drops into test resources `bench/` in the same format. The log here is SYNTHETIC (see its header); its
 * first read carries verbatim frames of the upstream KA15 capture.
 */
private fun text(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }

class NativeBenchLogTest {
    private class Frame(val tx: ByteArray, val rx: List<ByteArray>)
    private fun hex(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)) { name }.use { it.readBytes() }.decodeToString()
    private fun log(name: String): List<Frame> {
        val frames = ArrayList<Frame>()
        resource("/bench/$name").lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when (line.substringBefore(' ')) {
                "TX" -> frames += Frame(hex(line.substringAfter(' ')), ArrayList())
                "RX" -> (frames.last().rx as ArrayList) += hex(line.substringAfter(' '))
                else -> error("Not a bench log line: $line")
            }
        }
        return frames
    }

    /** The phone's transport rules (HidTransport.requestNative): report ID 7, the first reply the matcher takes, else the timeout. */
    private class ReplayPort(val frames: List<Frame>) : NativePort {
        override val deviceKey = "bench-log"
        var next = 0
        val pauses = ArrayList<Long>()
        override fun guard() {}
        override fun pause(ms: Long) { pauses += ms }
        private fun take(id: Int, payload: ByteArray): Frame {
            assertEquals(7, id)
            assertTrue(next < frames.size, "the session sends more than the log holds: ${text(payload)}")
            val f = frames[next++]
            assertEquals(text(f.tx), text(byteArrayOf(7) + payload), "frame $next")
            return f
        }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { take(id, payload) }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray =
            take(id, payload).rx.filter { it[0].toInt() == 7 }.map { it.copyOfRange(1, it.size).copyOf(63) }.firstOrNull { matches(it) }
                ?: throw java.io.IOException("Native request timed out or returned invalid state")
    }

    /** The example EQ: bands 0-6 of the upstream KA15 capture (all PK), preamp -12.0 dB = register 0 (the capture wrote register 2 = -11.8 dB; the KA15 keeps whole dB since 26.10.2026, so the log writes 0). */
    private val example = Profile("bench", "", bands = listOf(
        Band("b0", FilterType.PEAK, 20.0, 4.2, 0.60), Band("b1", FilterType.PEAK, 56.0, 1.2, 2.90),
        Band("b2", FilterType.PEAK, 150.0, -3.1, 0.70), Band("b3", FilterType.PEAK, 430.0, 11.8, 3.82),
        Band("b4", FilterType.PEAK, 770.0, -3.9, 0.90), Band("b5", FilterType.PEAK, 2900.0, 1.9, 3.72),
        Band("b6", FilterType.PEAK, 5000.0, 6.7, 1.00)), preampDb = -12.0, createdAt = 0, updatedAt = 0)

    @Test fun `KA15 write to USER1 from USER3 replays byte for byte and verifies`() {
        val frames = log("ka15-user1-write.log")
        val port = ReplayPort(frames)
        val r = NativeSession(DeviceTarget.KA15, port).write(example, explicitHold = true, targetSlot = 7)
        assertEquals(frames.size, port.next, "every frame of the log was sent, in order")
        assertTrue(r.verified); assertNull(r.reason)
        val back = r.readback as NativeState.Fiio
        assertEquals(7, back.slot)
        assertEquals(-12.0, back.preamp)
        assertEquals(10, back.raw.count)
        assertEquals(FiioRegisters(3, 430, 118, 383, 0), back.raw.bands[3]) // written 382, read 383: inside the app's Q slack
        assertEquals(FiioRegisters(7, 100, 0, 100, 0), back.raw.bands[7]) // the neutral pad after the seven bands
        assertEquals(mapOf(7 to "TEST1", 8 to "NIGHTFA", 9 to "FH3"), back.names) // no name in the profile: no rename
        // Select settle, after COUNT, before and after SAVE: the waits the rig must keep between these frames.
        assertEquals(listOf(300L, 100L, 100L, 2000L), port.pauses) // the KA15 stays busy about 2 s after the save: FiioConfig.afterSaveMs
        // The DAC played USER3, so the session switched (SELECT 0x16 with slot 7) and read USER1 completely before writing.
        val firstWrite = frames.indexOfFirst { (it.tx[1].toInt() and 255) == 0xAA }
        assertEquals("07 aa 0a 00 1d 16 01 07 45 ee", text(frames[firstWrite].tx))
    }

    /** The parameter writes are the capture's own (devicePEQ, KA15 2026-05-03) apart from byte 3 (Contour sends the
     * official app's host sequence; devicePEQ sends 0) and the CRC-8/MAXIM before EE (devicePEQ sends 0). */
    @Test fun `KA15 parameter writes equal the upstream capture apart from sequence and CRC`() {
        val tx = log("ka15-user1-write.log").map { it.tx.copyOfRange(1, it.tx.size) }
        fun masked(b: ByteArray) = b.map { it.toInt() and 255 }.toMutableList().also { it[3] = 0; it[it.size - 2] = 0 }
        val capture = Json.parseToJsonElement(resource("/fiio/fiio_fiio_ka15.json")).jsonObject.getValue("exchanges").jsonArray
            .map { e -> e.jsonObject.getValue("send").jsonObject.getValue("data").jsonArray.map { it.jsonPrimitive.int } }
        val writes = tx.filter { (it[0].toInt() and 255) == 0xAA && (it[4].toInt() and 255) in setOf(FiioCodec.PREAMP, FiioCodec.COUNT, FiioCodec.BAND) }
        assertEquals(12, writes.size) // preamp, count, ten bands
        val captured = capture.filter { it[4] in setOf(FiioCodec.PREAMP, FiioCodec.COUNT, FiioCodec.BAND) }
        for ((ours, theirs) in writes.map(::masked).zip(captured)) {
            if (ours[4] == FiioCodec.BAND && ours[6] == 7) continue // capture band 7 (7200 Hz) is not in the 7-band example: Contour pads it
            if (ours[4] == FiioCodec.PREAMP) { assertEquals(listOf(0, 2), theirs.subList(6, 8)); assertEquals(listOf(0, 0), ours.subList(6, 8)); continue } // the capture wrote -11.8 dB, the example whole -12.0
            assertEquals(theirs, ours)
        }
        val pad = writes.single { (it[4].toInt() and 255) == FiioCodec.BAND && it[6].toInt() == 7 }
        assertEquals(listOf(0xAA, 0x0A, 0, 0, 0x15, 8, 7, 0, 0, 0, 100, 0, 100, 0, 0, 0xEE), masked(pad)) // PK 100 Hz 0 dB Q 1.00
        // Every frame the session sent carries CRC-8/MAXIM (poly 0x31 reflected, init 0) over header..data, as the official app.
        fun crc(body: List<Int>) = body.fold(0) { c0, b -> var c = c0 xor b; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }; c }
        for (frame in tx) {
            val b = frame.map { it.toInt() and 255 }
            assertEquals(crc(b.dropLast(2)), b[b.size - 2], text(frame))
        }
    }
}
