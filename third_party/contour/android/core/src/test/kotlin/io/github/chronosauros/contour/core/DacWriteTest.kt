package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DAC Settings, round 4: the write path. Frames come from [FiioCodec.setSetting] only, and [DacWrite] refuses before any byte exists.
 * The port is a synthetic DAC that keeps its settings, echoes every SET and answers every GET, so the tests see the exact bytes, the echo,
 * the read-back and the self-test sequence. Nothing here touches a real DAC.
 */
class DacWriteTest {
    private val tiny = DeviceTarget.TINY_A
    private val melody = DeviceTarget.MELODY
    private val ka15 = DeviceTarget.KA15
    private fun u(b: Byte) = b.toInt() and 255
    private fun codec(t: DeviceTarget) = FiioCodec(requireNotNull(t.fiio))
    private fun st(t: DeviceTarget, id: String) = t.settings.single { it.id == id }

    private fun crc(bytes: List<Int>): Int {
        var c = 0
        for (b in bytes) { c = c xor b; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 } }
        return c
    }
    private fun expected(seq: Int, cmd: Int, data: List<Int>): List<Int> {
        val body = listOf(0xAA, 0x0A, 0, seq, cmd, data.size) + data
        return body + listOf(crc(body), 0xEE)
    }

    // ---- frames ----------------------------------------------------------------------------------------------------------------

    @Test fun `one SET per setting kind has the exact bytes - toggle, choice, level, balance both ways, and the sequence counts`() {
        val c = codec(melody)
        val on = c.setSetting(st(melody, "mic"), 1)
        assertEquals(expected(0, 0x12, listOf(1)), on.bytes)
        assertEquals(FiioEffect.WRITE_SETTING, on.effect)
        assertEquals(expected(1, 0x03, listOf(4)), c.setSetting(st(melody, "filter"), 4).bytes)      // choice: NOS
        assertEquals(expected(2, 0x02, listOf(37)), c.setSetting(st(melody, "volume"), 37).bytes)    // level
        assertEquals(expected(3, 0x07, listOf(0, 3)), c.setSetting(st(melody, "balance"), -3).bytes) // L 3 = 00 03
        assertEquals(expected(4, 0x07, listOf(3, 0)), c.setSetting(st(melody, "balance"), 3).bytes)  // R 3 = 03 00
        assertEquals(expected(5, 0x07, listOf(0, 0)), c.setSetting(st(melody, "balance"), 0).bytes)
        assertEquals(expected(0, 0x0E, listOf(5)), codec(ka15).setSetting(st(ka15, "brightness"), 5).bytes)
        assertEquals(expected(0, 0x0F, listOf(6)), codec(ka15).setSetting(st(ka15, "screen-off"), 6).bytes)
        assertEquals(expected(0, 0x0C, listOf(1)), codec(tiny).setSetting(st(tiny, "volume-steps"), 1).bytes)
        // every SET is the set header and never the get header
        assertTrue(listOf(on).all { it.bytes[0] == 0xAA && it.bytes[1] == 0x0A })
    }

    // ---- refusals --------------------------------------------------------------------------------------------------------------

    @Test fun `a setting that is not in the model's list is refused, a reply is not capability and neither is a write`() {
        assertFailsWith<IllegalArgumentException> { codec(ka15).setSetting(st(melody, "gain"), 1) }        // KA15 has no gain
        assertFailsWith<IllegalArgumentException> { codec(melody).setSetting(st(ka15, "screen-off"), 1) }  // Melody has no screen
        assertFailsWith<IllegalArgumentException> { codec(tiny).setSetting(st(melody, "filter"), 4) }      // Tiny A has no filter
        assertFailsWith<IllegalArgumentException> { codec(ka15).setSetting(st(tiny, "mic"), 1) }           // KA15 has no microphone
        // the K13 R2R has no list at all: any setting is refused
        for (t in listOf(melody, ka15, tiny)) for (s in t.settings) assertFailsWith<IllegalArgumentException>("${t.caps.name} ${s.id}") { codec(DeviceTarget.K13).setSetting(s, 0) }
        // a setting with no write command
        assertFailsWith<IllegalArgumentException> { codec(melody).setSetting(st(melody, "filter").copy(writeCommand = null), 1) }
        // a command that is not the list's own for that id
        assertFailsWith<IllegalArgumentException> { codec(melody).setSetting(st(melody, "filter").copy(writeCommand = 0x04), 1) }
    }

    @Test fun `USB AUDIO 0x20 is read-only on every model`() {
        for (t in listOf(melody, ka15, tiny)) {
            val e = assertFailsWith<IllegalArgumentException>(t.caps.name) { codec(t).setSetting(st(t, "uac"), 1) }
            assertTrue("read-only" in e.message.orEmpty(), e.message)
        }
        assertEquals(0x20, (st(melody, "uac").read as SettingRead.FiioGet).command) // still read
    }

    @Test fun `the never-send commands cannot be built at all`() {
        val base = st(melody, "filter")
        for (cmd in ProbePlan.FIIO_FORBIDDEN) {
            // 0x21 is refused already when the setting is made; the others when the frame is asked for
            assertFailsWith<IllegalArgumentException>("0x%02x".format(cmd)) { codec(melody).setSetting(base.copy(writeCommand = cmd), 0) }
        }
        for (cmd in setOf(0x00, 0x19, 0x1A, 0x1B, 0x1C, 0x30, 0x4F)) {
            val e = assertFailsWith<IllegalArgumentException> { codec(melody).setSetting(base.copy(writeCommand = cmd), 0) }
            assertTrue("never-send" in e.message.orEmpty(), e.message)
        }
        assertEquals(setOf(0x00, 0x19, 0x1A, 0x1B, 0x1C, 0x21, 0x30, 0x4F), DacWrite.NEVER_SEND)
        // no list of a model contains one of them as a write command
        for (t in listOf(melody, ka15, tiny)) assertTrue(t.settings.none { it.writeCommand in DacWrite.NEVER_SEND }, t.caps.name)
    }

    @Test fun `a value outside the options or the range is refused`() {
        val c = codec(melody)
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "mic"), 2) }
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "mic"), -1) }
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "filter"), 5) }
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "volume"), -1) }
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "volume"), 121) }
        c.setSetting(st(melody, "volume"), 120)                                         // 120B mode has this top
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "balance"), 13) }
        assertFailsWith<IllegalArgumentException> { c.setSetting(st(melody, "balance"), -13) }
        c.setSetting(st(melody, "balance"), 12); c.setSetting(st(melody, "balance"), -12)
        assertFailsWith<IllegalArgumentException> { codec(ka15).setSetting(st(ka15, "brightness"), 11) }
        assertFailsWith<IllegalArgumentException> { codec(ka15).setSetting(st(ka15, "screen-off"), 7) }
    }

    // ---- the session: echo, read-back, self-test -------------------------------------------------------------------------------

    /** A synthetic DAC: keeps the values of its readable commands, echoes each SET, answers each GET. [applyPlan] says per SET whether it takes effect. */
    private class WritablePort(
        val values: MutableMap<Int, List<Int>>,
        var echo: Boolean = true,
        val applyPlan: MutableList<Boolean> = ArrayList(),
        val silentReads: MutableSet<Int> = HashSet(),
        val silentAfterSet: Boolean = false,
        /** The first reads after a SET time out, then the DAC answers: a late read-back (the KA15 after a filter change). */
        var lateReads: Int = 0,
    ) : NativePort {
        val pauses = ArrayList<Long>()
        override val deviceKey = "writable-fake"
        val frames = ArrayList<ByteArray>()
        val sets get() = frames.filter { it[0] == 0xAA.toByte() }
        private val setCmds = HashSet<Int>()
        override fun guard() {}
        override fun pause(ms: Long) { pauses += ms }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a settings write is a request: its echo is read")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            frames += payload
            val cmd = payload[4].toInt() and 255
            val r: ByteArray
            if (payload[0] == 0xAA.toByte()) {
                val len = payload[5].toInt() and 255
                val take = if (applyPlan.isEmpty()) true else applyPlan.removeAt(0)
                if (take) values[cmd] = (0 until len).map { payload[6 + it].toInt() and 255 }
                setCmds += cmd
                if (!echo) throw java.io.IOException("Native request timed out or returned invalid state")
                r = payload.copyOf(63)
            } else {
                if (cmd in silentReads || (silentAfterSet && cmd in setCmds)) throw java.io.IOException("Native request timed out or returned invalid state")
                if (cmd in setCmds && lateReads > 0) { lateReads--; throw java.io.IOException("Native request timed out or returned invalid state") }
                val data = values.getValue(cmd)
                r = (listOf(0xBB, 0x0B, 0, 0, cmd, data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray().copyOf(63)
            }
            require(matches(r))
            return r
        }
    }
    private fun melodyValues() = mutableMapOf(
        0x02 to listOf(38), 0x0C to listOf(0), 0x07 to listOf(0, 0), 0x03 to listOf(4), 0x04 to listOf(0), 0x05 to listOf(1), 0x32 to listOf(1),
        0x12 to listOf(0), 0x06 to listOf(1), 0x0D to listOf(0), 0x20 to listOf(1),
    )
    private fun cmdsOf(p: WritablePort) = p.frames.map { (if (it[0] == 0xAA.toByte()) "S" else "G") + "%02x".format(it[4].toInt() and 255) }

    @Test fun `writeSetting sends the SET, reads its echo and reads the same command back - verified, mismatch, no reply, no echo`() {
        val ok = WritablePort(melodyValues())
        val log = ArrayList<String>()
        assertEquals(SettingWriteOutcome.Verified, NativeSession(melody, ok).writeSetting("mic", 1, log::add))
        assertEquals(listOf("S12", "G12"), cmdsOf(ok))
        assertTrue(log.any { it.startsWith("settings mic SET TX aa 0a 00 00 12 01 01 ") } && log.any { it.startsWith("settings mic SET RX aa 0a") })

        val ignored = WritablePort(melodyValues(), applyPlan = mutableListOf(false))
        assertEquals(SettingWriteOutcome.Mismatch(0), NativeSession(melody, ignored).writeSetting("mic", 1)) // the DAC reads what it had

        val silent = WritablePort(melodyValues(), silentAfterSet = true)
        assertEquals(SettingWriteOutcome.NoReply, NativeSession(melody, silent).writeSetting("mic", 1))
        // two reads, then the same again after each late wait (NativeSession.LATE_READBACKS of them): the write itself is never repeated
        assertEquals(listOf("S12") + List(2 * (1 + NativeSession.LATE_READBACKS)) { "G12" }, cmdsOf(silent))
        assertEquals(NativeSession.LATE_READBACKS, silent.pauses.count { it == NativeSession.LATE_READBACK_MS })

        val noEcho = WritablePort(melodyValues(), echo = false)
        val log2 = ArrayList<String>()
        assertEquals(SettingWriteOutcome.Verified, NativeSession(melody, noEcho).writeSetting("mic", 1, log2::add)) // the read-back decides
        assertTrue(log2.any { it.endsWith("SET no echo") })
    }

    @Test fun `a read-back that comes late is asked for again after 2_5 s, and the write is not repeated`() {
        // runs/2026-10-21-fiio-ka15-02-phone: the restore wrote the filter, got no read-back, and the filter read as written a moment later
        val late = WritablePort(melodyValues(), lateReads = 2) // both reads of the first round time out
        val log = ArrayList<String>()
        assertEquals(SettingWriteOutcome.Verified, NativeSession(melody, late).writeSetting("mic", 1, log::add))
        assertEquals(listOf("S12", "G12", "G12", "G12"), cmdsOf(late))
        assertEquals(1, late.sets.size)
        assertEquals(1, late.pauses.count { it == NativeSession.LATE_READBACK_MS })
        assertTrue(log.any { it.contains("asking again in 2500 ms (1 of 2)") }, log.toString())
        // a first read that shows the old value, then the new one
        val slow = WritablePort(melodyValues(), applyPlan = mutableListOf(true))
        slow.values[0x12] = listOf(0)
        assertEquals(SettingWriteOutcome.Verified, NativeSession(melody, slow).writeSetting("mic", 0))
        assertEquals(listOf("S12", "G12"), cmdsOf(slow)) // already what was asked: no late read
    }

    @Test fun `writeSetting refuses what DacWrite refuses and sends nothing`() {
        val port = WritablePort(melodyValues())
        val s = NativeSession(melody, port)
        assertFailsWith<IllegalArgumentException> { s.writeSetting("uac", 0) }
        assertFailsWith<IllegalArgumentException> { s.writeSetting("brightness", 3) } // not in the Melody list
        assertFailsWith<IllegalArgumentException> { s.writeSetting("filter", 9) }
        assertTrue(port.frames.isEmpty())
    }

    @Test fun `self-test of a toggle and a choice writes the next option and restores the original`() {
        val port = WritablePort(melodyValues())
        val log = ArrayList<String>()
        val r = NativeSession(melody, port).writeTest("mic", null, log::add)
        assertEquals("WRITETEST mic from=0 to=1 back=1 restored=0 result=OK", r.line())
        assertEquals(listOf("G12", "S12", "G12", "S12", "G12"), cmdsOf(port))
        assertEquals(listOf(listOf(1), listOf(0)), port.sets.map { listOf(it[6].toInt()) })
        assertEquals(log.last(), r.line())
        val filter = WritablePort(melodyValues())
        assertEquals("WRITETEST filter from=4 to=0 back=0 restored=4 result=OK", NativeSession(melody, filter).writeTest("filter", null).line()) // wraps around
        val gain = WritablePort(melodyValues())
        assertEquals("WRITETEST gain from=0 to=1 back=1 restored=0 result=OK", NativeSession(melody, gain).writeTest("gain", null).line())
    }

    @Test fun `self-test of a level writes one less, or one more at 0, and --ei to overrides within the range`() {
        val port = WritablePort(melodyValues())
        assertEquals("WRITETEST volume from=38 to=37 back=37 restored=38 result=OK", NativeSession(melody, port).writeTest("volume", null).line())
        val zero = melodyValues().also { it[0x02] = listOf(0) }
        assertEquals("WRITETEST volume from=0 to=1 back=1 restored=0 result=OK", NativeSession(melody, WritablePort(zero)).writeTest("volume", null).line())
        assertEquals("WRITETEST volume from=38 to=40 back=40 restored=38 result=OK", NativeSession(melody, WritablePort(melodyValues())).writeTest("volume", 40).line())
        // the volume top follows VOLUME STEPS: 61 is above 60 in the 60 mode, and nothing is sent
        val p = WritablePort(melodyValues())
        assertFailsWith<IllegalArgumentException> { NativeSession(melody, p).writeTest("volume", 61) }
        assertTrue(p.sets.isEmpty())
        val p120 = WritablePort(melodyValues().also { it[0x0C] = listOf(1) })
        assertEquals("WRITETEST volume from=38 to=90 back=90 restored=38 result=OK", NativeSession(melody, p120).writeTest("volume", 90).line())
        val ka = mutableMapOf(0x0E to listOf(5))
        assertEquals("WRITETEST brightness from=5 to=4 back=4 restored=5 result=OK", NativeSession(ka15, WritablePort(ka)).writeTest("brightness", null).line())
        assertFailsWith<IllegalArgumentException> { NativeSession(ka15, WritablePort(mutableMapOf(0x0E to listOf(5)))).writeTest("brightness", 11) }
    }

    @Test fun `self-test of the balance steps towards L from the centre and back towards the centre otherwise`() {
        val centred = WritablePort(melodyValues())
        assertEquals("WRITETEST balance from=0 to=-1 back=-1 restored=0 result=OK", NativeSession(melody, centred).writeTest("balance", null).line())
        assertEquals(listOf(listOf(0, 1), listOf(0, 0)), centred.sets.map { listOf(it[6].toInt(), it[7].toInt()) }) // L 1 = 00 01, then centre
        val left = melodyValues().also { it[0x07] = listOf(0, 3) }                                                  // L 3
        assertEquals("WRITETEST balance from=-3 to=-2 back=-2 restored=-3 result=OK", NativeSession(melody, WritablePort(left)).writeTest("balance", null).line())
        val right = melodyValues().also { it[0x07] = listOf(2, 0) }                                                 // R 2
        val rp = WritablePort(right)
        assertEquals("WRITETEST balance from=2 to=1 back=1 restored=2 result=OK", NativeSession(melody, rp).writeTest("balance", null).line())
        assertEquals(listOf(listOf(1, 0), listOf(2, 0)), rp.sets.map { listOf(it[6].toInt(), it[7].toInt()) })
    }

    @Test fun `self-test of VOLUME STEPS also reads the volume before, after the write and after the restore`() {
        val port = WritablePort(melodyValues())
        val log = ArrayList<String>()
        val r = NativeSession(melody, port).writeTest("volume-steps", null, log::add)
        assertEquals("WRITETEST volume-steps from=0 to=1 back=1 restored=0 result=OK", r.line())
        assertEquals(listOf("G0c", "G02", "S0c", "G0c", "G02", "S0c", "G0c", "G02"), cmdsOf(port))
        assertEquals("WRITETEST volume-steps volume before=38 afterWrite=38 afterRestore=38", r.extra)
        assertTrue(log.contains(r.extra))
    }

    @Test fun `when the restore does not read back the original the self-test stops and says RESTORE_FAILED`() {
        val port = WritablePort(melodyValues(), applyPlan = mutableListOf(true, false)) // the restore write does not take
        val log = ArrayList<String>()
        val r = NativeSession(melody, port).writeTest("filter", null, log::add)
        assertEquals("RESTORE_FAILED", r.result)
        assertEquals("WRITETEST filter from=4 to=0 back=0 restored=0 result=RESTORE_FAILED", r.line())
        assertEquals(2, port.sets.size)                       // nothing more is sent
        assertEquals("G03", cmdsOf(port).last())              // the last frame is the read-back of the restore
        assertTrue(log.any { "RESTORE_FAILED" in it && "NOTHING MORE IS SENT" in it })
        // the DAC that goes silent after the write: the restore fails too
        val silent = WritablePort(melodyValues(), silentAfterSet = true)
        assertEquals("RESTORE_FAILED", NativeSession(melody, silent).writeTest("filter", null).result)
    }

    @Test fun `a write that does not take is a MISMATCH and the original is restored`() {
        val port = WritablePort(melodyValues(), applyPlan = mutableListOf(false, true))
        val r = NativeSession(melody, port).writeTest("filter", null)
        assertEquals("WRITETEST filter from=4 to=0 back=4 restored=4 result=MISMATCH", r.line())
        assertEquals(2, port.sets.size)
    }

    @Test fun `the self-test refuses USB AUDIO and a setting that is not in the model's list before any frame goes out`() {
        val port = WritablePort(melodyValues())
        val s = NativeSession(melody, port)
        val e = assertFailsWith<IllegalArgumentException> { s.writeTest("uac", null) }
        assertTrue("read-only" in e.message.orEmpty(), e.message)
        assertFailsWith<IllegalArgumentException> { s.writeTest("screen-off", null) }
        assertFailsWith<IllegalArgumentException> { s.writeTest("nothing", null) }
        assertTrue(port.frames.isEmpty())
    }

    @Test fun `a DAC that does not answer the first read gets no write at all`() {
        val port = WritablePort(melodyValues(), silentReads = mutableSetOf(0x03))
        val r = NativeSession(melody, port).writeTest("filter", null)
        assertEquals("WRITETEST filter from=none to=none back=none restored=none result=NO_REPLY", r.line())
        assertTrue(port.sets.isEmpty())
        assertNull(r.from)
    }
}
