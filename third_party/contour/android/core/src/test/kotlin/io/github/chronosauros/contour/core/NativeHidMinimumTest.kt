package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun bytes(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

/**
 * The report-size check the app's HidTransport makes before it opens a HID interface (`NativeHidReports.Shape.qualify`): the minimum comes from the target's
 * protocol, not from one constant. The Kiwi Ears Allegro Mini (KT Micro) declares 10-byte Input and Output reports 0x4B (11 with the ID); the old fixed floor of 17
 * rejected it on the phone ("HID if 3 rejected: Failed requirement", run 2026-10-22-kiwi-ears-allegro-mini-01-phone). The descriptor below is the unit's, from that run's logcat.
 */
class NativeHidMinimumTest {
    private val allegroDescriptor = bytes(
        "05 0c 09 01 a1 01 85 01 15 00 25 01 75 01 95 02 09 e9 09 ea 81 02 95 02 09 cd 09 cf 81 02 95 04 81 01 06 01 ff 85 4b 75 08 95 0a 09 01 81 03 " +
            "95 0a 09 02 91 02 85 54 75 08 95 0a 09 03 81 03 95 0a 09 04 91 02 c0")
    private val shape = NativeHidReports.parse(allegroDescriptor, undefinedDesktop = true)
    private val allegro = DeviceTarget.ALLEGRO_MINI

    /** The same descriptor with report 0x4B declared [n] bytes long each way (the `95 0a` count items of 0x4B are the first two). */
    private fun shapeWith(n: Int): NativeHidReports.Shape {
        val d = allegroDescriptor.copyOf()
        var seen = 0
        for (i in 0 until d.size - 1) if (d[i] == 0x95.toByte() && d[i + 1] == 0x0a.toByte() && seen++ < 2) d[i + 1] = n.toByte()
        return NativeHidReports.parse(d, undefinedDesktop = true)
    }

    @Test fun `the Allegro Mini descriptor declares 10 data bytes each way for report 0x4B, in one vendor collection`() {
        assertEquals(11, shape.rawSize(0x4B, NativeHidReports.Kind.INPUT))
        assertEquals(11, shape.rawSize(0x4B, NativeHidReports.Kind.OUTPUT))
        assertEquals(10, shape.payloadSize(0x4B, NativeHidReports.Kind.INPUT))
        shape.requireSameOwner(0x4B, NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)
    }

    @Test fun `the session check accepts it for the KT Micro target`() {
        shape.qualify(allegro, 0x4B)
        assertEquals(11, NativeHidReports.minimumRawBytes(allegro, NativeHidReports.Kind.OUTPUT))
        assertEquals(11, NativeHidReports.minimumRawBytes(allegro, NativeHidReports.Kind.INPUT))
        // one byte less in either direction is not a KT report: the frames are 10 bytes
        assertFailsWith<IllegalArgumentException> { shapeWith(9).qualify(allegro, 0x4B) }
    }

    @Test fun `the same small report still fails for FiiO, the Dawn Pro 2 and the Echo-A, FiiO at its 17`() {
        val fiio = assertFailsWith<IllegalArgumentException> { shape.qualify(DeviceTarget.KA15, 0x4B) }
        assertTrue("at least 17" in fiio.message!!, fiio.message)
        assertEquals(17, NativeHidReports.minimumRawBytes(DeviceTarget.KA15, NativeHidReports.Kind.INPUT))
        assertFailsWith<IllegalArgumentException> { shape.qualify(DeviceTarget.DAWN_PRO_2, 0x4B) }
        assertFailsWith<IllegalArgumentException> { shape.qualify(DeviceTarget.ECHO_A, 0x4B) }
        // the edge of FiiO's proof: 16 bytes with the ID fails, 17 passes (and the Allegro still takes both)
        assertFailsWith<IllegalArgumentException> { shapeWith(15).qualify(DeviceTarget.KA15, 0x4B) }
        shapeWith(16).qualify(DeviceTarget.KA15, 0x4B)
        shapeWith(16).qualify(allegro, 0x4B)
    }

    @Test fun `every FiiO target keeps 17, the Dawn Pro 2 its band frames and the Echo-A its 64`() {
        for (t in listOf(DeviceTarget.KA15, DeviceTarget.K13, DeviceTarget.MELODY, DeviceTarget.TINY_A))
            for (k in listOf(NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)) assertEquals(17, NativeHidReports.minimumRawBytes(t, k))
        assertEquals(1 + 36, NativeHidReports.minimumRawBytes(DeviceTarget.DAWN_PRO_2, NativeHidReports.Kind.INPUT))
        assertEquals(1 + 37, NativeHidReports.minimumRawBytes(DeviceTarget.DAWN_PRO_2, NativeHidReports.Kind.OUTPUT))
        for (k in listOf(NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)) assertEquals(64, NativeHidReports.minimumRawBytes(DeviceTarget.ECHO_A, k))
        // the Dawn Pro 2's real descriptor (64 bytes each way, 21.10.2026) is accepted
        val dawnHex = requireNotNull(NativeHidMinimumTest::class.java.getResourceAsStream("/bench/dawn-pro-2-descriptor.hex")).use { it.readBytes() }.decodeToString()
        NativeHidReports.parse(bytes(dawnHex.lines().filterNot { it.startsWith("#") }.joinToString(" ")), undefinedDesktop = true).qualify(DeviceTarget.DAWN_PRO_2, 0x4B)
    }

    @Test fun `the Echo-A's real descriptor from the phone (63 data bytes each way, run 2026-10-22-moondrop-echo-a-01-phone) is accepted at the 64 the session needs`() {
        val echo = bytes("05 0c 09 01 a1 01 85 03 15 00 25 03 75 01 95 02 09 e9 09 ea 81 02 75 01 95 02 09 cd 09 cf 81 02 75 01 95 04 81 01 06 01 ff 85 4b 75 08 95 3f 09 01 81 03 " +
            "95 3f 09 02 91 02 85 54 75 08 95 3f 09 03 81 03 95 3f 09 04 91 02 c0")
        val s = NativeHidReports.parse(echo, undefinedDesktop = true)
        assertEquals(64, s.rawSize(0x4B, NativeHidReports.Kind.INPUT)); assertEquals(64, s.rawSize(0x4B, NativeHidReports.Kind.OUTPUT))
        s.qualify(DeviceTarget.ECHO_A, 0x4B)
        // the Allegro Mini's 11 bytes would not do for the Echo-A, and the Echo-A's 64 is more than the Allegro Mini needs
        s.qualify(allegro, 0x4B)
    }

    @Test fun `a Micro, Max or TRN is not opened through a descriptor`() {
        assertFailsWith<IllegalStateException> { NativeHidReports.minimumRawBytes(DeviceTarget.MICRO, NativeHidReports.Kind.INPUT) }
    }

    @Test fun `every frame the KT session builds fits the 10-byte wire, and a 10-byte reply is a valid input report`() {
        val model = allegro.ktMicro!!
        val out = NativeHidReports.Kind.OUTPUT
        val read = KtMicroCodec.readQueries(model) + KtMicroCodec.readRegister(KtMicroCodec.CHANNEL_FLAGS_REGISTER) + KtMicroCodec.readRegister(KtMicroCodec.GLOBAL_GAIN_REGISTER)
        read.forEach { f ->
            val wire = shape.wire(0x4B, out, f.payload)
            assertEquals(11, wire.size)
            assertEquals(0x4B, wire[0].toInt())
            assertEquals(f.payload.toList(), wire.drop(1))
        }
        // writes: the EQ switch, ten band registers, SAVE, and a Global Gain step
        val snap = replay(readLog()).first
        val eq = Profile("id", "t", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        val writes = KtMicroCodec.compile(eq, snap).frames + KtMicroCodec.compileGlobalGain(snap, KtMicroCodec.RegisterValue(0x3B, listOf(13, 0, 0, 0)), 12).frames
        assertTrue(writes.size >= 12)
        writes.forEach { f -> assertEquals(11, shape.wire(0x4B, out, f.payload).size) }
        // a reply: 4b + 10 bytes
        val reply = bytes("4b 24 00 00 00 52 00 02 00 00 00")
        val payload = shape.payload(0x4B, NativeHidReports.Kind.INPUT, reply)
        assertEquals(2, KtMicroCodec.parseRegister(KtMicroCodec.Reply("k", 0x4B, payload), "k", 0x24).value[0])
        assertFailsWith<IllegalArgumentException> { shape.payload(0x4B, NativeHidReports.Kind.INPUT, reply + 0) } // 12 bytes is not this report
    }

    @Test fun `the real Allegro Mini read log runs through the session when every frame goes through the descriptor's wire`() {
        val (state, frames) = replay(readLog())
        assertEquals(14, frames)
        assertEquals(5, state.bands.size)
        assertEquals(2, state.eqControl)
    }

    // ---- a port that applies the descriptor's sizes to every report, replaying the bench log of 20.10.2026 ----------------------------------------------------------------------

    private fun readLog(): String =
        requireNotNull(NativeHidMinimumTest::class.java.getResourceAsStream("/bench/allegro-mini-read-232520.log")).use { it.readBytes() }.decodeToString()

    private fun replay(text: String): Pair<KtMicroCodec.Snapshot, Int> {
        class Entry(val tx: ByteArray, val rx: MutableList<ByteArray> = ArrayList())
        val entries = ArrayList<Entry>()
        text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val body = bytes(line.substringAfter(' '))
            when (line.substringBefore(' ')) { "TX" -> entries += Entry(body); "RX" -> entries.last().rx += body else -> error(line) }
        }
        var next = 0
        val port = object : NativePort {
            override val deviceKey = "k"
            override fun guard() {}
            override fun pause(ms: Long) {}
            override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = shape.payloadSize(id, kind)
            override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { error("a read sends no output-only report") }
            override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
                val wire = shape.wire(id, NativeHidReports.Kind.OUTPUT, payload) // what HidTransport.sendNative puts on the wire
                val e = entries[next++]
                assertEquals(e.tx.toList(), wire.toList(), "frame $next")
                val replies = e.rx.map { shape.payload(id, NativeHidReports.Kind.INPUT, it) } // what HidTransport.receive accepts
                return replies.firstOrNull(matches) ?: throw IOException("Native request timed out or returned invalid state")
            }
        }
        val state = KtMicroSession(allegro.ktMicro!!, port).read()
        return state to next
    }
}
