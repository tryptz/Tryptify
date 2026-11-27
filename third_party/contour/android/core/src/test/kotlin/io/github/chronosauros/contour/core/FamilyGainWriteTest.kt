package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import io.github.chronosauros.contour.core.native.KtMicroCodec.RegisterValue
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun gHex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
private fun gBytes(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
private fun gResource(name: String) = requireNotNull(FamilyGainWriteTest::class.java.getResourceAsStream("/bench/$name")) { name }.use { it.readBytes() }.decodeToString()

/** The bench log's (TX, RX...) frames, as reports with their ID byte. */
private fun gFrames(text: String): List<Pair<ByteArray, List<ByteArray>>> {
    val out = ArrayList<Pair<ByteArray, ArrayList<ByteArray>>>()
    text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val body = gBytes(line.substringAfter(' '))
        when (line.substringBefore(' ')) { "TX" -> out += body to ArrayList(); "RX" -> out.last().second += body; else -> error("Not a bench log line: $line") }
    }
    return out
}

/**
 * The setting-write paths of the 1.6 families, in :core: Allegro Mini Global Gain (register 0x3B, steps 1-15) and Dawn Pro 2 Pre Gain (0x23) and Global Gain
 * (0x03), 0.1 dB. The frames are byte-exact and guarded; the app keeps every one of these rows read-only (writeVerified false) until the PC bench has proven it
 * on the unit. SYNTHETIC fake DACs: they check the frames and their order, not firmware.
 */
class FamilyGainWriteTest {
    // ---- Allegro Mini: Global Gain 0x3B -----------------------------------------------------------------------------------------------------------------

    private val allegro = requireNotNull(KtMicroCatalog.find(0x31B2, 0x0111, "Kiwi Ears-Allegro Mini"))

    /** The KT register store of the Allegro read log, SAVE drops it off the bus; a write is acknowledged with `03 00 00 00`. */
    private class FakeKt(step: List<Int>? = listOf(13, 0, 0, 0)) : NativePort {
        override val deviceKey = "k"
        val regs = HashMap<Int, ByteArray>()
        val events = ArrayList<String>()
        var gone = false
        var silent3B = false
        var ignoreWrite3B = false
        init {
            gFrames(gResource("allegro-mini-read-232520.log")).forEach { (_, rx) -> rx.forEach { r -> regs[r[1].toInt() and 255] = r.copyOfRange(7, 11) } }
            step?.let { regs[KtMicroCodec.GLOBAL_GAIN_REGISTER] = it.map { b -> b.toByte() }.toByteArray() }
        }
        fun tx() = events.filter { it.startsWith("tx:") }.map { it.removePrefix("tx:") }
        fun writes() = tx().filter { it.split(" ")[4] == "57" }
        override fun guard() { check(!gone) { "device gone" } }
        override fun pause(ms: Long) { events += "pause:$ms" }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            events += "tx:${gHex(payload)}"
            if ((payload[4].toInt() and 255) == KtMicroCodec.SAVE) { gone = true; events += "disconnect" }
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            events += "tx:${gHex(payload)}"
            val reg = payload[0].toInt() and 255
            val cmd = payload[4].toInt() and 255
            val out = payload.copyOf()
            if (cmd == KtMicroCodec.WRITE) {
                if (!(reg == KtMicroCodec.GLOBAL_GAIN_REGISTER && ignoreWrite3B)) regs[reg] = payload.copyOfRange(6, 10)
                byteArrayOf(3, 0, 0, 0).copyInto(out, 6)
            } else {
                if (reg == KtMicroCodec.GLOBAL_GAIN_REGISTER && silent3B) throw IOException("Native request timed out or returned invalid state")
                regs.getValue(reg).copyInto(out, 6)
            }
            return out.also { assertTrue(matches(it)) }
        }
    }

    private fun before(dac: FakeKt = FakeKt()) = KtMicroSession(allegro, dac).read()

    @Test fun `Global Gain steps are 1 to 15, 1_5 dB apart from -18 to +3, step 13 is 0 dB, and step 0 has no dB`() {
        assertEquals(-18.0, KtMicroCodec.globalGainDb(1)); assertEquals(0.0, KtMicroCodec.globalGainDb(13)); assertEquals(3.0, KtMicroCodec.globalGainDb(15))
        assertEquals(-16.5, KtMicroCodec.globalGainDb(2))
        assertEquals(13, KtMicroCodec.GLOBAL_GAIN_STEP_DEFAULT)
        for (bad in listOf(0, -1, 16, 99)) assertFailsWith<IllegalArgumentException> { KtMicroCodec.globalGainDb(bad) }
        assertTrue(assertFailsWith<IllegalArgumentException> { KtMicroCodec.requireGlobalGainStep(0) }.message!!.contains("step 0"))
        val rows = DeviceTarget.ALLEGRO_MINI.settings.single { it.id == FamilySettings.GLOBAL_GAIN }
        assertEquals(KtMicroCodec.GLOBAL_GAIN_STEP_MIN..KtMicroCodec.GLOBAL_GAIN_STEP_MAX, rows.min..rows.max) // the page can never offer step 0
    }

    @Test fun `the Global Gain write is 3b 00 00 00 57 00 STEP 00 00 00, acknowledged, then SAVE, byte for byte, for every step`() {
        val b = before()
        val current = RegisterValue(KtMicroCodec.GLOBAL_GAIN_REGISTER, listOf(13, 0, 0, 0))
        for (step in (1..15) - 13) {
            val plan = KtMicroCodec.compileGlobalGain(b, current, step)
            assertEquals(2, plan.frames.size)
            assertEquals("3b 00 00 00 57 00 %02x 00 00 00".format(step), gHex(plan.frames[0].payload), "step $step")
            assertTrue(plan.frames[0].mutating && plan.frames[0].expectsReply)
            assertEquals("00 00 00 00 53 00 00 00 00 00", gHex(plan.frames[1].payload))
            assertEquals(RegisterValue(0x3B, listOf(step, 0, 0, 0)), plan.expectedGlobalGain)
            assertTrue(plan.reconnectAfterSave)
            // the bands and the EQ switch stay as they were
            assertEquals(b.registers.sortedBy { it.register }, plan.expectedRegisters)
            assertEquals(b.eqControl, plan.expectedEqControl)
            // without SAVE: the one write, nothing that makes the DAC leave the bus
            val ram = KtMicroCodec.compileGlobalGain(b, current, step, save = false)
            assertEquals(listOf("3b 00 00 00 57 00 %02x 00 00 00".format(step)), ram.frames.map { gHex(it.payload) })
            assertFalse(ram.reconnectAfterSave)
        }
    }

    @Test fun `step 0, a step outside 1-15, the step already set and a 0x3B read that is not a plain step are refused before any frame exists`() {
        val b = before()
        val current = RegisterValue(0x3B, listOf(13, 0, 0, 0))
        for (bad in listOf(0, 16, -1, 255)) assertFailsWith<IllegalArgumentException> { KtMicroCodec.compileGlobalGain(b, current, bad) }
        assertTrue(assertFailsWith<IllegalArgumentException> { KtMicroCodec.compileGlobalGain(b, current, 0) }.message!!.contains("step 0"))
        assertTrue(assertFailsWith<IllegalArgumentException> { KtMicroCodec.compileGlobalGain(b, current, 13) }.message!!.contains("already step 13"))
        for (odd in listOf(listOf(0, 0, 0, 0), listOf(16, 0, 0, 0), listOf(13, 1, 0, 0), listOf(0, 13, 0, 0), listOf(255, 255, 255, 255))) {
            val e = assertFailsWith<IllegalArgumentException> { KtMicroCodec.compileGlobalGain(b, RegisterValue(0x3B, odd), 10) }
            assertTrue(e.message!!.contains("not a step 1-15"), e.message)
        }
        assertNull(KtMicroCodec.globalGainStep(RegisterValue(0x24, listOf(3, 0, 0, 0))))
        assertEquals(7, KtMicroCodec.globalGainStep(RegisterValue(0x3B, listOf(7, 0, 0, 0))))
    }

    @Test fun `a RAM write of the Global Gain reads it back in the session, verified or not at once, and the way back is the write of the old step`() {
        val dac = FakeKt()
        val others = dac.regs.filterKeys { it != 0x3B }.mapValues { it.value.toList() }
        val s = KtMicroSession(allegro, dac)
        val r = s.writeGlobalGain(10, explicitHold = true, save = false)
        assertTrue(r.verified, r.reason); assertFalse(r.pending)
        assertEquals(listOf("3b 00 00 00 57 00 0a 00 00 00"), dac.writes())              // the one write; no band, no switch
        assertTrue(dac.tx().none { it.split(" ")[4] == "53" || it.split(" ")[4] == "43" }) // no SAVE, never the flash clear
        assertFalse(dac.gone)
        assertEquals(listOf(13, 0, 0, 0), s.lastGainBefore!!.value)                       // what the restore writes back
        assertEquals(listOf(10, 0, 0, 0), dac.regs.getValue(0x3B).map { it.toInt() })
        // restore
        val back = s.writeGlobalGain(requireNotNull(KtMicroCodec.globalGainStep(s.lastGainBefore!!)), explicitHold = true, save = false)
        assertTrue(back.verified, back.reason)
        assertEquals(listOf(13, 0, 0, 0), dac.regs.getValue(0x3B).map { it.toInt() })
        assertEquals(others, dac.regs.filterKeys { it != 0x3B }.mapValues { it.value.toList() })
        assertTrue(dac.writes().all { it.startsWith("3b ") })
        // a DAC that takes the write but does not hold it is a mismatch that names the register
        val deaf = FakeKt().also { it.ignoreWrite3B = true }
        val bad = KtMicroSession(allegro, deaf).writeGlobalGain(10, explicitHold = true, save = false)
        assertFalse(bad.verified)
        assertTrue(bad.reason!!.startsWith("register 0x3b: wrote 0a 00 00 00, read 0d 00 00 00"), bad.reason)
    }

    @Test fun `a saved Global Gain is pending until the DAC is back, and only a read after it with the written step verifies it`() {
        val dac = FakeKt()
        val s = KtMicroSession(allegro, dac)
        val r = s.writeGlobalGain(9, explicitHold = true, save = true)
        assertTrue(r.pending && !r.verified); assertNull(r.readback)
        assertTrue(dac.gone)
        assertEquals("00 00 00 00 53 00 00 00 00 00", dac.tx().last())
        assertEquals(listOf("3b 00 00 00 57 00 09 00 00 00"), dac.writes())
        val plan = s.lastPlan!!
        // back on the bus with the step saved
        val back = FakeKt().also { it.regs.putAll(dac.regs) }
        val sb = KtMicroSession(allegro, back)
        assertEquals(emptyList(), KtMicroCodec.mismatches(plan, sb.read(), sb.readGlobalGain()))
        // back without it (SAVE did not take) or with no 0x3B answer: not verified
        val stale = FakeKt()
        val ss = KtMicroSession(allegro, stale)
        assertTrue(KtMicroCodec.mismatches(plan, ss.read(), ss.readGlobalGain()).single().startsWith("register 0x3b: wrote 09 00 00 00, read 0d 00 00 00"))
        assertEquals("register 0x3b: not read back", KtMicroCodec.mismatches(plan, ss.read(), null).single())
    }

    @Test fun `the Global Gain write refuses without HOLD, step 0, a silent 0x3B or an odd 0x3B, and sends no write in any of those cases`() {
        fun quiet(step: List<Int>? = listOf(13, 0, 0, 0), setup: FakeKt.() -> Unit = {}) = FakeKt(step).also(setup)
        val a = quiet()
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, a).writeGlobalGain(10, explicitHold = false) }
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, a).writeGlobalGain(0, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, a).writeGlobalGain(16, explicitHold = true) }
        assertTrue(a.events.none { it.startsWith("tx:") }, "step 0 and a missing HOLD are refused before anything is read or sent")
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, a).writeGlobalGain(13, explicitHold = true) } // already there
        val silent = quiet { silent3B = true }
        val e = assertFailsWith<IOException> { KtMicroSession(allegro, silent).writeGlobalGain(10, explicitHold = true) }
        assertTrue(e.message!!.contains("nothing written"), e.message)
        val odd = quiet(listOf(0, 7, 0, 0))
        assertFailsWith<IllegalArgumentException> { KtMicroSession(allegro, odd).writeGlobalGain(10, explicitHold = true) }
        for (d in listOf(a, silent, odd)) assertTrue(d.writes().isEmpty() && d.tx().none { it.split(" ")[4] == "53" }, d.writes().toString())
    }

    // ---- Dawn Pro 2: Pre Gain 0x23, Global Gain 0x03 ------------------------------------------------------------------------------------------------------

    private val dawn = requireNotNull(MoondropCatalog.find(0x35D8, 0x011D, "DAWN PRO2"))
    private val G = MoondropCodec.GainRegister.GLOBAL
    private val P = MoondropCodec.GainRegister.PRE

    @Test fun `the Dawn gain frames are 01 23 02 lo hi and 01 03 02 lo hi, signed Q8_8 on the 0_1 dB grid`() {
        fun frame(r: MoondropCodec.GainRegister, db: Double) = gHex(MoondropCodec.gainPayload(dawn, r, db))
        assertEquals("01 03 02 00 00", frame(G, 0.0))
        assertEquals("01 03 02 00 fd", frame(G, -3.0))
        assertEquals("01 03 02 00 f7", frame(G, -9.0))
        assertEquals("01 03 02 00 ee", frame(G, -18.0))
        assertEquals("01 03 02 e6 ff", frame(G, -0.1))
        assertEquals("01 03 02 00 fd", frame(G, -3.04))                 // rounded to 0.1 dB first
        assertEquals("01 23 02 00 00", frame(P, 0.0))
        assertEquals("01 23 02 00 fd", frame(P, -3.0))                  // the pre-gain of the 1.5 EQ write is the same payload
        assertEquals("01 23 02 80 f9", frame(P, -6.5))
        assertEquals("01 23 02 00 80", frame(P, -128.0))
        assertEquals(gHex(MoondropCodec.preGainPayload(dawn, -3.0)), frame(P, -3.0))
        assertEquals(-768, MoondropCodec.gainRawQ8(dawn, G, -3.0)); assertEquals(-1664, MoondropCodec.gainRawQ8(dawn, P, -6.5))
        for (bad in listOf(-18.1, 0.1, Double.NaN, Double.POSITIVE_INFINITY)) assertFailsWith<IllegalArgumentException> { MoondropCodec.gainPayload(dawn, G, bad) }
        for (bad in listOf(-128.1, 0.1, Double.NaN)) assertFailsWith<IllegalArgumentException> { MoondropCodec.gainPayload(dawn, P, bad) }
        // a gain step is the one frame (50 ms); with the EQ save `01 01 00` (200 ms) and never `01 04 00`
        assertEquals(listOf("01 23 02 80 f9"), MoondropCodec.gainSteps(dawn, P, -6.5).map { gHex(it.payload) })
        assertEquals(listOf(MoondropCodec.DELAY_OP_MS), MoondropCodec.gainSteps(dawn, P, -6.5).map { it.delayAfterMs })
        val saved = MoondropCodec.gainSteps(dawn, G, -9.0, persist = true)
        assertEquals(listOf("01 03 02 00 f7", "01 01 00"), saved.map { gHex(it.payload) })
        assertEquals(MoondropCodec.DELAY_FLASH_MS, saved.last().delayAfterMs)
    }

    @Test fun `the Dawn allow-list refuses the offset save 01 04 00, the flash clear, the firmware entry and every gain frame that is not exactly the tool's`() {
        val allowed = listOf("80 0f 00", "80 09 18 00 03 00", "80 03", "80 23 00", "80 0c", "01 03 02 00 fd", "01 23 02 80 f9", "01 01 00", "01 0f 01 07", "01 0a 04 00 00 ff ff")
        allowed.forEach { MoondropCodec.requireSendable(gBytes(it)) }
        val e = assertFailsWith<IllegalArgumentException> { MoondropCodec.requireSendable(gBytes("01 04 00")) }
        assertTrue(e.message!!.contains("never sent"), e.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { MoondropCodec.requireSendable(gBytes("01 05 01 04")) }.message!!.contains("flash clear"))
        assertTrue(assertFailsWith<IllegalArgumentException> { MoondropCodec.requireSendable(gBytes("01 ff")) }.message!!.contains("firmware"))
        for (bad in listOf("01 03 01 00", "01 03 02 00", "01 23 03 00 fd 00", "01 01 01", "01 0a 04 00 00 ff fe", "01 08 00", "00 03 02 00 00", "02 03 02 00 00", "80 04", "80 05", "80 ff", "01")) {
            assertFailsWith<IllegalArgumentException> { MoondropCodec.requireSendable(gBytes(bad)) }
        }
        // no step the gain planner can build is refused by the list, and none is a never-send frame
        for (r in listOf(G, P)) for (db in listOf(0.0, -0.1, -3.0, -17.9)) for (persist in listOf(false, true)) {
            MoondropCodec.gainSteps(dawn, r, db, persist).forEach { MoondropCodec.requireSendable(it.payload); assertTrue(it.payload[1].toInt() != 0x04 && it.payload[1].toInt() != 0x05 && it.payload[1].toInt() != 0xFF) }
        }
    }

    /** SYNTHETIC Dawn Pro 2 with the two gains: the banks come from the real read log, a gain write is kept and read back. */
    private class FakeDawn(var index: Int) : NativePort {
        override val deviceKey = "d"
        val sent = ArrayList<ByteArray>()
        val banks = HashMap<Int, Array<ByteArray>>()
        var preQ8 = -768
        var globalQ8 = 0
        var preSilent = false
        var ignoreGain = false
        private val shape = NativeHidReports.parse(gBytes(gResource("dawn-pro-2-descriptor.hex")))
        init {
            val frames = gFrames(gResource("dawn-pro-2-read-103434.log"))
            val bands = Array(8) { i -> frames[1 + i].second.single().copyOfRange(1, 64) }
            banks[9] = bands
            banks[7] = Array(8) { i -> bands[i].copyOf().also { it[35] = 7 } }
        }
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = shape.payloadSize(id, kind)
        fun writes() = sent.filter { it[0].toInt() == 1 }.map { w -> gHex(w.copyOf(when (w[1].toInt()) { 0x0F -> 4; 0x01 -> 3; 0x0A -> 7; 0x09 -> 5; else -> 5 })) }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            sent += payload
            val p = payload.copyOf(63)
            fun q8() = ((p[3].toInt() and 255) or (p[4].toInt() shl 8)).toShort().toInt()
            when {
                p[0].toInt() == 1 && p[1].toInt() == 0x09 -> {
                    val bank = p[35].toInt() and 255
                    banks.getOrPut(bank) { Array(8) { ByteArray(63) } }[p[4].toInt()] = p.copyOf().also { it[0] = 0x80.toByte(); it[2] = 0x18 }
                }
                p[0].toInt() == 1 && p[1].toInt() == 0x0F -> index = p[3].toInt() and 255
                p[0].toInt() == 1 && p[1].toInt() == 0x23 -> if (!ignoreGain) preQ8 = q8()
                p[0].toInt() == 1 && p[1].toInt() == 0x03 -> if (!ignoreGain) globalQ8 = q8()
            }
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            sent += payload
            val out = ByteArray(63)
            when (payload[1].toInt()) {
                0x0F -> { out[0] = 0x80.toByte(); out[1] = 0x0F; out[3] = index.toByte() }
                0x09 -> banks.getValue(index)[payload[4].toInt()].copyInto(out)
                0x03 -> { out[0] = 0x80.toByte(); out[1] = 0x03; out[2] = 2; out[3] = globalQ8.toByte(); out[4] = (globalQ8 shr 8).toByte() }
                0x23 -> {
                    if (preSilent) throw IOException("Native request timed out or returned invalid state")
                    out[0] = 0x80.toByte(); out[1] = 0x23; out[2] = 2; out[3] = preQ8.toByte(); out[4] = (preQ8 shr 8).toByte() // SYNTHETIC layout (the real reply is unproven)
                }
                else -> error("unexpected request")
            }
            return out.also { assertTrue(matches(it)) }
        }
    }

    private fun neverSent(d: FakeDawn) = d.sent.none { it[0].toInt() == 1 && (it[1].toInt() == 0x05 || it[1].toInt() == 0xFF || it[1].toInt() == 0x04) }

    @Test fun `a Dawn Pre Gain write is the one frame 01 23 02 lo hi, read back with the same index and the same bands`() {
        val dac = FakeDawn(index = 7)
        val s = MoondropSession(dawn, dac)
        val r = s.writeGain(P, -6.5, explicitHold = true)
        assertTrue(r.verified, r.reason); assertFalse(r.pending)
        assertEquals(listOf("01 23 02 80 f9"), dac.writes())                                // nothing else: no band, no commit, no save, no 01 04 00
        assertEquals(-1664, r.readback!!.preGain!!.rawQ8)
        assertEquals(7, r.readback!!.activeIndex)
        assertEquals(s.lastBefore!!.bands, r.readback!!.bands)
        assertEquals(-768, s.lastBefore!!.preGain!!.rawQ8)                                  // what a restore writes back: -3.0 dB
        // the way back: the same call with the old value
        val back = s.writeGain(P, -3.0, explicitHold = true)
        assertTrue(back.verified, back.reason)
        assertEquals(listOf("01 23 02 80 f9", "01 23 02 00 fd"), dac.writes())
        assertTrue(neverSent(dac))
    }

    @Test fun `a Dawn Global Gain write is 01 03 02 lo hi, on any index, and only with persist is it followed by 01 01 00 on the writable bank`() {
        val on9 = FakeDawn(index = 9)
        val r = MoondropSession(dawn, on9).writeGain(G, -9.0, explicitHold = true)
        assertTrue(r.verified, r.reason)
        assertEquals(listOf("01 03 02 00 f7"), on9.writes())
        assertEquals(-2304, r.readback!!.globalGain.rawQ8)
        assertEquals(9, on9.index)                                                         // the EQ index is not switched by a gain write
        // persist belongs to the active bank: refused (nothing sent) on a preset index (3), allowed on Contour's banks (7 and 9)
        val off = FakeDawn(index = 3).also { it.banks[3] = it.banks.getValue(9).map { b -> b.copyOf().also { x -> x[35] = 3 } }.toTypedArray() }
        assertFailsWith<IllegalArgumentException> { MoondropSession(dawn, off).writeGain(G, -9.0, explicitHold = true, persist = true) }
        assertTrue(off.writes().isEmpty())
        val on7 = FakeDawn(index = 7)
        val p = MoondropSession(dawn, on7).writeGain(G, -9.0, explicitHold = true, persist = true)
        assertTrue(p.verified, p.reason)
        assertEquals(listOf("01 03 02 00 f7", "01 01 00"), on7.writes())
        assertTrue(neverSent(on9) && neverSent(off) && neverSent(on7))
    }

    @Test fun `a Dawn gain write refuses without HOLD, out of range, a silent pre-gain read, and a gain that does not hold, and sends no gain frame when it refuses`() {
        val dac = FakeDawn(index = 7)
        val s = MoondropSession(dawn, dac)
        assertFailsWith<IllegalArgumentException> { s.writeGain(P, -6.5, explicitHold = false) }
        assertFailsWith<IllegalArgumentException> { s.writeGain(G, 0.5, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { s.writeGain(G, -18.5, explicitHold = true) }
        assertFailsWith<IllegalArgumentException> { s.writeGain(P, -129.0, explicitHold = true) }
        assertTrue(dac.sent.isEmpty(), "refused before any I/O")
        val silent = FakeDawn(index = 7).also { it.preSilent = true }
        val e = assertFailsWith<IllegalArgumentException> { MoondropSession(dawn, silent).writeGain(P, -6.5, explicitHold = true) }
        assertTrue(e.message!!.contains("nothing sent"), e.message)
        assertTrue(silent.writes().isEmpty())
        // the Global Gain does not need the pre-gain read
        assertTrue(MoondropSession(dawn, silent).writeGain(G, -9.0, explicitHold = true).verified)
        // a unit that ignores the gain: the read after says so
        val deaf = FakeDawn(index = 7).also { it.ignoreGain = true }
        val bad = MoondropSession(dawn, deaf).writeGain(P, -6.5, explicitHold = true)
        assertFalse(bad.verified)
        assertTrue(bad.reason!!.startsWith("pre-gain -1664 -> -768 (Q8.8)"), bad.reason)
        assertNotNull(bad.readback)
    }

    @Test fun `the DAC Settings gain rows - the Allegro Global Gain is proven on the phone, the Dawn Pre Gain is not, the Dawn Global Gain has no write at all, and enabling one is the one flag`() {
        for (t in listOf(DeviceTarget.DAWN_PRO_2, DeviceTarget.ALLEGRO_MINI)) {
            for (s in t.settings.filter { it.id == FamilySettings.GLOBAL_GAIN || it.id == FamilySettings.PRE_GAIN }) {
                // the Allegro Mini Global Gain passed the PC bench (runs/2026-10-21-kiwi-ears-allegro-mini-02 T14) and the phone (Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone: steps 1, 15, 13 VERIFIED); the Dawn Pre Gain is unproven
                assertEquals(t === DeviceTarget.ALLEGRO_MINI, s.writeVerified, "${t.caps.name} ${s.id}")
                // the Dawn Global Gain is read only in every build: the unit ignores 01 03 02 (RUN-DP3), so the row has no write command at all
                assertEquals(t === DeviceTarget.DAWN_PRO_2 && s.id == FamilySettings.GLOBAL_GAIN, s.writeCommand == null, "${t.caps.name} ${s.id}")
                assertEquals(s.copy(writeVerified = !s.writeVerified).copy(writeVerified = s.writeVerified), s)
            }
        }
        val global = DeviceTarget.DAWN_PRO_2.settings.single { it.id == FamilySettings.GLOBAL_GAIN }
        for (b in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(b) {
            assertFalse(global.writeEnabled, "$b"); assertFalse(global.copy(writeVerified = true).writeEnabled, "$b")
        }
    }
}
