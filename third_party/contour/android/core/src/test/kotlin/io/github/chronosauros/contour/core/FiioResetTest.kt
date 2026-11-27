package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Factory reset of the SnowSky Melody and Tiny A and the FiiO KA15 (FiiO command 0x21): the frame, the guards that keep 0x21 impossible everywhere else, the flow
 * (backup, consent, send, read after, restore) and the three session calls against a SYNTHETIC SnowSky and a SYNTHETIC KA15 (they check frames and order,
 * not firmware: what the real DAC erases is unknown until the bench has run it).
 */
class FiioResetTest {
    private val tiny = DeviceTarget.TINY_A
    private val melody = DeviceTarget.MELODY
    private val ka = DeviceTarget.KA15
    private val config = requireNotNull(tiny.fiio)
    private val kaConfig = requireNotNull(ka.fiio)

    private fun crc8(bytes: List<Int>): Int {
        var c = 0
        for (b in bytes) { c = c xor b; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 } }
        return c
    }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    private fun ticket(c: FiioConfig) = FiioResetTicket(c.productName, 0)

    // ---- the frame ------------------------------------------------------------------------------------------------------------------------------------

    @Test fun `the reset frame is aa 0a 00 SEQ 21 01 00 CRC ee with the CRC-8 MAXIM of the web app, on the Melody, the Tiny A and the KA15`() {
        for (t in listOf(tiny, melody, ka)) {
            val c = FiioCodec(requireNotNull(t.fiio))
            val f = c.resetFrame(ticket(c.config))
            assertEquals(FiioEffect.FACTORY_RESET, f.effect)
            assertEquals(7, f.reportId)
            assertEquals("aa 0a 00 00 21 01 00 c7 ee", hex(f.payload()), t.caps.name) // sequence 0: nothing built before it on this codec
            val body = listOf(0xAA, 0x0A, 0, 0, 0x21, 1, 0)
            assertEquals(body + listOf(crc8(body), 0xEE), f.bytes)
        }
        // the sequence byte counts with the frames the codec built before it, and the CRC follows it
        val c = FiioCodec(config)
        c.querySetting(0x02); c.querySetting(0x07)
        val f = c.resetFrame(ticket(config))
        assertEquals("aa 0a 00 02 21 01 00 ${"%02x".format(crc8(listOf(0xAA, 0x0A, 0, 2, 0x21, 1, 0)))} ee", hex(f.payload()))
    }

    @Test fun `a ticket buys one frame and only for its own model, and no other DAC has a reset frame`() {
        val c = FiioCodec(config)
        val t = ticket(config)
        c.resetFrame(t)
        assertFailsWith<IllegalArgumentException> { c.resetFrame(t) }                                  // spent
        assertFailsWith<IllegalArgumentException> { FiioCodec(requireNotNull(melody.fiio)).resetFrame(ticket(config)) } // a Tiny A ticket on a Melody
        assertFailsWith<IllegalArgumentException> { FiioCodec(kaConfig).resetFrame(ticket(config)) }   // a Tiny A ticket on the KA15
        val k13 = FiioCodec(requireNotNull(DeviceTarget.K13.fiio))                                      // not offered: refused even with a ticket made for it
        val e = assertFailsWith<IllegalArgumentException> { k13.resetFrame(ticket(k13.config)) }
        assertTrue(e.message!!.contains("no factory reset"), e.message)
        assertTrue(FiioReset.offered(config) && FiioReset.offered(melody.fiio) && FiioReset.offered(kaConfig)) // the KA15: owner decision 21.10.2026
        assertFalse(FiioReset.offered(DeviceTarget.K13.fiio)); assertFalse(FiioReset.offered(null))
        // the app's flag: the KA15 (runs/2026-10-22-fiio-ka15-05-phone) and the Melody (PC runs/2026-10-22-snowsky-melody-03, phone runs/2026-10-22-snowsky-melody-01-phone) have run a reset on hardware;
        // the Tiny A stays closed (it ignores the frame)
        assertEquals(setOf(kaConfig.productName, requireNotNull(melody.fiio).productName), FiioReset.VERIFIED)
        assertFalse(FiioReset.enabled(config)); assertTrue(FiioReset.enabled(melody.fiio)); assertTrue(FiioReset.enabled(kaConfig))
    }

    @Test fun `0x21 stays impossible everywhere else - reads, settings writes, the probe, the save command and every plan frame`() {
        assertTrue(0x21 in ProbePlan.FIIO_FORBIDDEN && 0x21 in DacWrite.NEVER_SEND && 0x21 !in ProbePlan.FIIO_COMMANDS)
        assertFailsWith<IllegalArgumentException> { FiioCodec(config.copy(saveCommand = 0x21)) }
        for (t in listOf(DeviceTarget.KA15, DeviceTarget.K13, melody, tiny)) {
            val f = requireNotNull(t.fiio)
            val c = FiioCodec(f)
            assertFailsWith<IllegalArgumentException> { c.querySetting(0x21) }
            // every settings write of every row and value: none is 0x21, none a FACTORY_RESET frame
            for (s in t.settings) {
                assertTrue(s.writeCommand != 0x21 && (s.read as? SettingRead.FiioGet)?.command != 0x21, s.id)
                val values = when (s.kind) {
                    SettingKind.TOGGLE, SettingKind.CHOICE -> s.options.indices
                    SettingKind.LEVEL -> s.min..DacWrite.hardMax(s)
                    SettingKind.BALANCE -> -s.max..s.max
                }
                for (v in values) {
                    val frame = runCatching { c.setSetting(s, v) }.getOrNull() ?: continue
                    assertTrue(frame.bytes[4] != 0x21 && frame.effect != FiioEffect.FACTORY_RESET, "${t.caps.name} ${s.id} $v")
                }
            }
            // a setting that carries 0x21 cannot exist
            assertFailsWith<IllegalArgumentException> { DacSetting("x", "X", SettingKind.TOGGLE, EvidenceRank.HW, options = listOf("OFF", "ON"), writeCommand = 0x21, writeVerified = true) }
            assertFailsWith<IllegalArgumentException> { DacSetting("x", "X", SettingKind.TOGGLE, EvidenceRank.HW, options = listOf("OFF", "ON"), read = SettingRead.FiioGet(0x21, { it[0].toInt() })) }
            // the plan frames and the slot frames of the codec
            val profile = Profile("p", "n", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 1.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
            val slot = requireNotNull(t.destinationSlot)
            val plan = c.planOnExplicitSend(profile.copy(bands = c.padToDeviceCount(profile.bands)), slot, true)
            val all = listOf(plan.selection, plan.selectionVerification, plan.save) + plan.backupQueries + plan.writes + plan.verificationQueries +
                f.userSlots.map { c.selectUserSlot(it) } + (if (f.userSlotNames) f.userSlots.map { c.queryName(it) } else emptyList())
            assertTrue(all.none { it.bytes[4] == 0x21 || it.effect == FiioEffect.FACTORY_RESET }, t.caps.name)
        }
    }

    // ---- the flow -------------------------------------------------------------------------------------------------------------------------------------

    private val seeds = listOf(
        Triple(160, "MEGA5EST", Pair(-2.0, listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0), Band("b", FilterType.LOW_SHELF, 105.0, 2.5, 0.7), Band("c", FilterType.BAND_PASS, 2000.0, 0.0, 2.0)))),
        Triple(161, "SEPTET", Pair(0.0, listOf(Band("a", FilterType.PEAK, 250.0, -4.0, 2.0)))),
        Triple(162, "DUSK", Pair(-1.5, listOf(Band("a", FilterType.HIGH_SHELF, 8000.0, 3.0, 0.7)))),
    )

    private fun snapshot(slot: Int, preampDb: Double, bands: List<Band>): FiioSnapshot {
        val c = FiioCodec(config)
        return c.planOnExplicitSend(c.padToDeviceCount(bands), preampDb, slot, true).expected
    }

    /** A complete backup of the Tiny A, built the way a read builds it. */
    private fun backup(takenAt: Long = 1L) = FiioResetBackup(config.productName, takenAt, 161,
        seeds.map { (slot, name, e) -> FiioSlotBackup(slot, name, snapshot(slot, e.first, e.second)) },
        mapOf("volume" to 48, "balance" to -2, "volume-steps" to 0, "mic" to 1, "light" to 1, "uac" to 1))

    @Test fun `the backup says what stops a reset - another model, a slot or a name not read, a slot that cannot be written back`() {
        assertEquals(emptyList(), backup().problems(config))
        assertTrue(backup().copy(productName = "SNOWSKY MELODY").problems(config).single().contains("not SNOWSKY TINY A"))
        assertTrue(backup().copy(slots = backup().slots.drop(1)).problems(config).any { it.contains("USER1 was not read") })
        assertTrue(backup().copy(slots = backup().slots.map { if (it.slot == 161) it.copy(name = null) else it }).problems(config).any { it.contains("USER2: the name was not read") })
        val broken = backup().slots.map { s -> if (s.slot == 162) s.copy(snapshot = s.snapshot.copy(bands = s.snapshot.bands.map { it.copy(typeCode = 99) })) else s }
        assertTrue(backup().copy(slots = broken).problems(config).any { it.startsWith("USER3 cannot be written back exactly") })
    }

    @Test fun `the backup is plain text that reads back exactly, and a damaged file is refused with its line`() {
        val b = backup(takenAt = 1_760_000_000_000L).copy(unreadSettings = listOf("light"), settings = backup().settings - "light")
        val text = b.toText()
        assertTrue(text.startsWith("contour-reset-backup 1\nproduct SNOWSKY TINY A\ntaken 1760000000000\nactive 161\nslot 160 name MEGA5EST count 10 preamp -20"), text.take(160))
        assertEquals(b, FiioResetBackup.parse(text))
        assertEquals(b.copy(activeSlot = null), FiioResetBackup.parse(b.copy(activeSlot = null).toText()))
        assertFailsWith<IllegalArgumentException> { FiioResetBackup.parse(text.replace("contour-reset-backup 1", "contour-reset-backup 2")) }
        assertFailsWith<IllegalArgumentException> { FiioResetBackup.parse(text.lines().filterNot { it.startsWith("band 161 3 ") }.joinToString("\n")) } // a band missing
        assertFailsWith<IllegalArgumentException> { FiioResetBackup.parse(text + "setting volume 5\n") }                                                 // twice
        assertFailsWith<IllegalArgumentException> { FiioResetBackup.parse(text + "reset now\n") }
        assertFailsWith<IllegalArgumentException> { FiioResetBackup.parse(text.replace("taken 1760000000000\n", "")) }
    }

    @Test fun `the flow runs in order only - backup, consent, send, compare, restore - and nothing starts without the owner's yes`() {
        val flow = FiioResetFlow(config)
        assertFailsWith<IllegalArgumentException> { flow.confirm(true) }                                   // no backup yet
        assertFailsWith<IllegalArgumentException> { flow.sent() }
        assertFailsWith<IllegalArgumentException> { flow.compared(backup()) }
        assertFailsWith<IllegalArgumentException> { flow.restored() }
        val incomplete = backup().copy(slots = backup().slots.drop(1))
        assertFailsWith<IllegalArgumentException> { flow.begin(incomplete) }                              // a backup with a hole is no backup
        assertEquals(FiioResetFlow.Step.START, flow.step)
        assertFailsWith<IllegalArgumentException> { FiioResetFlow(requireNotNull(DeviceTarget.K13.fiio)).begin(backup()) }
        flow.begin(backup())
        assertEquals(FiioResetFlow.Step.BACKED_UP, flow.step)
        assertFailsWith<IllegalArgumentException> { flow.begin(backup()) }                                 // twice
        assertFailsWith<IllegalArgumentException> { flow.confirm(false) }                                  // no consent, no ticket
        assertEquals(FiioResetFlow.Step.BACKED_UP, flow.step)
        val t = flow.confirm(true)
        assertEquals(FiioResetFlow.Step.CONFIRMED, flow.step)
        assertFailsWith<IllegalArgumentException> { flow.confirm(true) }                                   // one ticket per backup
        assertFailsWith<IllegalArgumentException> { flow.compared(backup()) }                              // nothing was sent
        FiioCodec(config).resetFrame(t)
        flow.sent()
        assertFailsWith<IllegalArgumentException> { flow.restored() }                                      // not compared yet
        val same = flow.compared(backup(takenAt = 2))
        assertEquals(emptyList(), same)                                                                    // nothing changed: the reset may not have run
        assertEquals(FiioResetFlow.Step.COMPARED, flow.step)
        flow.restored()
        assertEquals(FiioResetFlow.Step.RESTORED, flow.step)
    }

    @Test fun `the comparison lists the slot names, the slot EQs and the settings the reset changed, and a setting with no reply`() {
        val before = backup()
        val after = before.copy(
            slots = before.slots.map { s -> when (s.slot) {
                160 -> FiioSlotBackup(160, "USER1", snapshot(160, 7.0, emptyList()))
                161 -> s.copy(name = "SEPTET")                                  // untouched
                else -> s.copy(name = "USER3")                                  // the name only
            } },
            settings = mapOf("volume" to 60, "balance" to 0, "volume-steps" to 0, "mic" to 0, "light" to 1),
        )
        val lines = FiioReset.diff(config, before, after, labelOf = { it.uppercase() }, valueOf = { id, v -> if (id == "balance") "B$v" else "$v" })
        assertTrue("USER1 name: MEGA5EST -> USER1" in lines, lines.toString())
        assertTrue("USER1 EQ: 10 filters, preamp -2.0 dB -> 10 filters, preamp 7.0 dB" in lines, lines.toString()) // a release says no more than that
        val review = Proven.asChannel(BuildChannel.PERF) { FiioReset.diff(config, before, after) }
        assertTrue(review.any { it.startsWith("USER1 EQ: 10 filters, preamp -2.0 dB -> 10 filters, preamp 7.0 dB (") && it.endsWith("filter registers differ)") }, review.toString())
        assertTrue("USER3 name: DUSK -> USER3" in lines, lines.toString())
        assertTrue(lines.none { it.startsWith("USER2") }, lines.toString())
        assertTrue("VOLUME: 48 -> 60" in lines && "BALANCE: B-2 -> B0" in lines && "MIC: 1 -> 0" in lines, lines.toString())
        assertTrue("UAC: 1 -> no reply" in lines, lines.toString())
        assertTrue(lines.none { it.startsWith("LIGHT") || it.startsWith("VOLUME-STEPS") }, lines.toString())
        assertEquals(emptyList(), FiioReset.diff(config, before, before))
    }

    // ---- the three session calls against a synthetic SnowSky ------------------------------------------------------------------------------------------

    /**
     * SYNTHETIC SnowSky Tiny A (or, with the KA15 as [target], a SYNTHETIC KA15): the three USER slots with names, the settings, echoes of every write, the slot
     * register always right. The SnowSky SAVE answers `00 ee` and its names go by slot id; the KA15 takes SAVE as a plain send and its names go by position.
     */
    private class SimSnow(val target: DeviceTarget) : NativePort {
        val c = FiioCodec(requireNotNull(target.fiio))
        private val ka = !c.config.nameBySlotId
        override val deviceKey = "snow-sim"
        class Slot(var name: String, var snap: FiioSnapshot)
        val slots = LinkedHashMap<Int, Slot>()
        var active = if (ka) 8 else 161
        val settings = LinkedHashMap<Int, List<Int>>()
        val tx = ArrayList<ByteArray>()
        var resetMode = "answer" // "answer", "silent" (no reply at all), "ack" (the reset runs and the DAC sends the bare `00 ee` answer of the phone runs, no echo), "gone" (the session ends)
        var resets = 0
        private fun snap(slot: Int, preampDb: Double, bands: List<Band>) = c.planOnExplicitSend(c.padToDeviceCount(bands), preampDb, slot, true).expected
        fun seed(slot: Int, name: String, preampDb: Double, bands: List<Band>) { slots[slot] = Slot(name, snap(slot, preampDb, bands)) }
        /** The KA15 answers only the settings of its list that this test needs: volume, steps, balance and the four SCREEN rows (the rest is "no reply"). */
        fun defaults() {
            if (ka) settings.putAll(mapOf(0x02 to listOf(60), 0x0C to listOf(0), 0x07 to listOf(0, 0), 0x0E to listOf(10), 0x0F to listOf(0), 0x10 to listOf(1), 0x11 to listOf(0)))
            else settings.putAll(mapOf(0x02 to listOf(60), 0x07 to listOf(0, 0), 0x0C to listOf(0), 0x12 to listOf(0), 0x06 to listOf(0), 0x20 to listOf(1), 0x09 to listOf(2), 0x0B to listOf(1, 2, 0)))
        }
        init {
            defaults(); settings[0x02] = listOf(48); settings[0x07] = listOf(0, 2)
            if (ka) { settings[0x0E] = listOf(5); settings[0x0F] = listOf(2); settings[0x10] = listOf(0); settings[0x11] = listOf(1) }
            else { settings[0x12] = listOf(1); settings[0x06] = listOf(1) }
        }
        fun commands() = tx.map { it[4].toInt() and 255 }
        private fun u(p: ByteArray, i: Int) = p[i].toInt() and 255
        private fun word(v: Int) = listOf((v ushr 8) and 255, v and 255)
        private fun cur() = slots.getValue(active)
        /** The slot a name frame addresses: the SnowSky by id, the KA15 by position among its USER slots. */
        private fun nameSlot(i: Int) = if (ka) c.config.userSlots.sorted()[i] else i
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            if (ka && u(payload, 4) == 0x19) { tx += payload.copyOf(); return } // KA15 SAVE: a send, no answer; the registers are already the DAC's
            error("every write is a request with its echo: ${hex(payload)}")
        }
        private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            tx += payload.copyOf()
            val cmd = u(payload, 4)
            val reply = if (u(payload, 0) == 0xBB) read(cmd, payload) else write(cmd, payload)
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
                else -> settings[cmd] ?: throw IOException("Native request timed out or returned invalid state")
            }
            val len = if (cmd == 0x30) (if (ka) 8 else 9) else data.size
            return (listOf(0xBB, 0x0B, 0, 0, cmd, len) + data + (if (cmd == 0x0B) listOf(0xEE) else listOf(0, 0xEE))).map { it.toByte() }.toByteArray().copyOf(63)
        }
        private fun write(cmd: Int, p: ByteArray): ByteArray {
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
                    if (ka) return echo // the KA15 echoes the frame as sent
                    val out = ByteArray(63); p.copyInto(out, 0, 0, 6 + len); out[5] = 9; out[17] = 0xEE.toByte()
                    return out
                }
                0x19 -> return byteArrayOf(0, 0xEE.toByte(), 0, 1, 0).copyOf(63) // SAVE: 00 EE 00 <counter> 00; the registers are the saved ones
                FiioReset.COMMAND -> {
                    resets++
                    when (resetMode) { "silent" -> throw IOException("Native request timed out or returned invalid state"); "gone" -> throw IOException("USB device removed") }
                    val preamps = if (ka) listOf(-6.0, -7.0, -8.0) else listOf(7.0, 6.0, 8.0)
                    slots.keys.forEachIndexed { i, s -> slots.getValue(s).name = "USER${i + 1}"; slots.getValue(s).snap = snap(s, preamps[i], emptyList()) }
                    defaults()
                    if (resetMode == "ack") return byteArrayOf(0, 0xEE.toByte(), 1, 0x60).copyOf(63) // Melody phone run 25.10.2026: RX 07 00 ee 01 60
                }
                else -> settings[cmd] = (0 until u(p, 5)).map { u(p, 6 + it) }
            }
            return echo
        }
    }

    private fun sim(): SimSnow = SimSnow(tiny).also { s -> seeds.forEach { (slot, name, e) -> s.seed(slot, name, e.first, e.second) } }

    @Test fun `the backup reads every USER slot with its name and registers and every setting, writes nothing and leaves the DAC on the slot it was on`() {
        val dac = sim()
        val b = NativeSession(tiny, dac).captureForReset(explicitUserAction = true, takenAtMs = 42L)
        assertEquals(emptyList(), b.problems(config))
        assertEquals(42L, b.takenAtMs); assertEquals(161, b.activeSlot)
        assertEquals(listOf(160, 161, 162), b.slots.map { it.slot })
        assertEquals(listOf("MEGA5EST", "SEPTET", "DUSK"), b.slots.map { it.name })
        assertEquals(dac.slots.mapValues { it.value.snap }, b.slots.associate { it.slot to it.snapshot })
        assertEquals(mapOf("volume" to 48, "balance" to -2, "volume-steps" to 0, "mic" to 1, "light" to 1, "uac" to 1), b.settings)
        assertEquals(emptyList(), b.unreadSettings)
        assertEquals(161, dac.active)
        // the only mutating frames are slot selects (AA 16): the registers and names are the DAC's own
        val writes = dac.tx.filter { (it[0].toInt() and 255) == 0xAA }.map { it[4].toInt() and 255 }
        assertTrue(writes.isNotEmpty() && writes.all { it == FiioCodec.SLOT }, writes.toString())
        assertTrue(0x21 !in dac.commands())
        assertFailsWith<IllegalArgumentException> { NativeSession(tiny, sim()).captureForReset(explicitUserAction = false, takenAtMs = 0) }
        assertFailsWith<IllegalArgumentException> { NativeSession(DeviceTarget.K13, sim()).captureForReset(explicitUserAction = true, takenAtMs = 0) }
    }

    @Test fun `backup, reset, read after, restore - the DAC ends as it began, every step through the one flow, and the reset frame goes out exactly once`() {
        val dac = sim()
        val session = NativeSession(tiny, dac)
        val flow = FiioResetFlow(config)
        val log = ArrayList<String>()
        val before = session.captureForReset(true, 1L, log::add)
        flow.begin(before)
        val ticket = flow.confirm(ownerConsent = true)
        assertTrue(0x21 !in dac.commands(), "nothing is sent before the frame itself")
        val sent = session.factoryReset(ticket, log::add)
        flow.sent()
        assertEquals(FiioResetSent(true, null), sent)
        assertEquals(1, dac.resets)
        assertEquals(listOf(0x21), dac.commands().filter { it == 0x21 })
        assertEquals("aa 0a 00 00 21 01 00 c7 ee", hex(dac.tx.last { (it[4].toInt() and 255) == 0x21 }.let { it.copyOf(9) }))
        assertTrue(log.any { it.startsWith("reset TX aa 0a 00 00 21 01 00 c7 ee") }, log.toString())
        // one ticket, one frame: the same ticket sends nothing
        assertFailsWith<IllegalArgumentException> { session.factoryReset(ticket) }
        assertEquals(1, dac.resets)
        // the read after the reset shows what the DAC erased
        val after = session.captureForReset(true, 2L)
        val changes = flow.compared(after, labelOf = { it })
        assertTrue("USER1 name: MEGA5EST -> USER1" in changes && "USER2 name: SEPTET -> USER2" in changes && "USER3 name: DUSK -> USER3" in changes, changes.toString())
        assertTrue(changes.any { it.startsWith("USER1 EQ:") } && changes.any { it.startsWith("USER2 EQ:") } && changes.any { it.startsWith("USER3 EQ:") }, changes.toString())
        assertTrue("volume: 48 -> 60" in changes && "balance: -2 -> 0" in changes && "mic: 1 -> 0" in changes && "light: 1 -> 0" in changes, changes.toString())
        assertEquals("USER1", dac.slots.getValue(160).name)
        // the restore writes the saved slots and settings back, one action
        val restored = session.restoreAfterReset(requireNotNull(flow.backup), log::add)
        flow.restored()
        assertTrue(restored.ok, restored.lines.toString())
        assertEquals(listOf("USER1: restored", "USER2: restored", "USER3: restored"), restored.lines.take(3))
        assertTrue(restored.lines.any { it == "VOLUME STEPS: unchanged" } && restored.lines.any { it == "VOLUME: restored" }, restored.lines.toString())
        assertEquals("The DAC is back on USER2", restored.lines.last())
        assertEquals(161, dac.active)
        val again = session.captureForReset(true, 3L)
        assertEquals(before.slots, again.slots)                // names and every register, slot by slot
        assertEquals(before.settings, again.settings)
        assertEquals(emptyList(), FiioReset.diff(config, before, again))
        assertEquals(1, dac.commands().count { it == 0x21 })
        // VOLUME STEPS goes first and VOLUME last of the settings
        val setOrder = dac.tx.filter { (it[0].toInt() and 255) == 0xAA && (it[4].toInt() and 255) in setOf(0x02, 0x07, 0x0C, 0x12, 0x06) }.map { it[4].toInt() and 255 }
        assertEquals(0x02, setOrder.last())
    }

    @Test fun `no answer to the reset frame and a session that ends after it are notes, never an exception, and never a verified reset`() {
        for ((mode, note) in listOf("silent" to "no answer to the reset frame", "gone" to "the session ended after the reset frame")) {
            val dac = sim().also { it.resetMode = mode }
            val flow = FiioResetFlow(config)
            flow.begin(NativeSession(tiny, dac).captureForReset(true, 1L))
            val r = NativeSession(tiny, dac).factoryReset(flow.confirm(true))
            assertFalse(r.echoed)
            assertTrue(r.note!!.startsWith(note), r.note)
            assertEquals(1, dac.resets)
            flow.sent()
            // a DAC the reset did not touch compares as "nothing changed": the flow does not call that done
            assertEquals(emptyList(), flow.compared(NativeSession(tiny, dac).captureForReset(true, 2L)))
        }
    }

    @Test fun `the bare 00 ee answer the Melody gives to the reset frame is named in the note, not reported as no answer`() {
        val dac = sim().also { it.resetMode = "ack" }
        val flow = FiioResetFlow(config)
        flow.begin(NativeSession(tiny, dac).captureForReset(true, 1L))
        val lines = ArrayList<String>()
        val r = NativeSession(tiny, dac).factoryReset(flow.confirm(true), lines::add)
        assertFalse(r.echoed) // it is not the echo the other commands get
        assertEquals("the DAC answered the reset frame, but not with an echo of it (it may be restarting)", r.note)
        assertTrue("reset RX (not an echo) 00 ee 01 60" in lines, lines.toString()) // the bytes are in the log
        assertTrue(lines.any { it == "reset: ${r.note}" }, lines.toString())
        assertEquals(1, dac.resets)
    }

    @Test fun `a reset frame is refused for any other DAC and without a flow ticket, before a byte is sent`() {
        val other = SimSnow(tiny)
        assertFailsWith<IllegalArgumentException> { NativeSession(DeviceTarget.K13, other).factoryReset(ticket(requireNotNull(DeviceTarget.K13.fiio))) }
        assertFailsWith<IllegalArgumentException> { NativeSession(ka, other).factoryReset(ticket(config)) } // a Tiny A ticket on the KA15
        assertFailsWith<IllegalArgumentException> { NativeSession(melody, other).factoryReset(ticket(config)) } // a Tiny A ticket on a Melody
        assertTrue(other.tx.isEmpty())
    }

    @Test fun `a restore refuses a backup of another DAC or with a hole, and a lost session stops it with a line per slot not restored`() {
        val dac = sim()
        val session = NativeSession(tiny, dac)
        assertFailsWith<IllegalArgumentException> { session.restoreAfterReset(backup().copy(productName = "SNOWSKY MELODY")) }
        assertFailsWith<IllegalArgumentException> { session.restoreAfterReset(backup().copy(slots = backup().slots.drop(2))) }
        assertTrue(dac.tx.isEmpty())
        // the session ends in the middle of the restore: what was not written is listed, never skipped
        val flaky = object : NativePort by dac {
            var left = 25
            override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
                if (--left < 0) throw IOException("USB device removed")
                return dac.request(id, payload, matches)
            }
        }
        val r = NativeSession(tiny, flaky).restoreAfterReset(backup())
        assertFalse(r.ok)
        assertTrue(r.lines.any { it.contains("NOT restored") } && r.lines.any { it.contains("not restored (the DAC session ended)") }, r.lines.toString())
        assertNotNull(r.lines.firstOrNull { it.startsWith("USER") })
        assertNull(r.lines.firstOrNull { it.startsWith("The DAC is back") })
        assertTrue(0x21 !in dac.commands())
    }

    @Test fun `no normal session call ever sends 0x21 - read, write, settings write and rename - the reset call is the only one`() {
        val dac = sim()
        val session = NativeSession(tiny, dac)
        session.read()
        session.readSettings()
        session.readSlot(160, true)
        session.selectSlot(161, true)
        session.write(Profile("p", "MEGA5EST", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 1.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0), true, targetSlot = 160)
        for (s in tiny.settings.filter { it.writeVerified && it.kind != SettingKind.BALANCE }) session.writeSetting(s.id, if (s.kind == SettingKind.LEVEL) s.min + 1 else 1)
        assertTrue(dac.commands().isNotEmpty() && 0x21 !in dac.commands(), dac.commands().toString())
        assertEquals(0, dac.resets)
    }

    // ---- the KA15: the same flow, USER slots 7-9, names of up to 7 characters addressed by position ---------------------------------------------------

    private val kaSeeds = listOf(
        Triple(7, "MEGA5ES", Pair(-2.0, listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0), Band("b", FilterType.LOW_SHELF, 105.0, 2.5, 0.7)))),
        Triple(8, "SEPTET", Pair(0.0, listOf(Band("a", FilterType.PEAK, 250.0, -4.0, 2.0)))),
        Triple(9, "DUSK", Pair(-1.5, listOf(Band("a", FilterType.HIGH_SHELF, 8000.0, 3.0, 0.7)))),
    )
    private fun simKa(): SimSnow = SimSnow(ka).also { s -> kaSeeds.forEach { (slot, name, e) -> s.seed(slot, name, e.first, e.second) } }
    private fun u(p: ByteArray, i: Int) = p[i].toInt() and 255
    private fun label(slot: Int) = requireNotNull(kaConfig.slotLabels[slot])

    @Test fun `KA15 backup reads USER slots 7-9 with their names by position and every readable setting, writes nothing but slot selects`() {
        val dac = simKa()
        val b = NativeSession(ka, dac).captureForReset(explicitUserAction = true, takenAtMs = 42L)
        assertEquals(emptyList(), b.problems(kaConfig))
        assertEquals(42L, b.takenAtMs); assertEquals(8, b.activeSlot)
        assertEquals(listOf(7, 8, 9), b.slots.map { it.slot })
        assertEquals(listOf("MEGA5ES", "SEPTET", "DUSK"), b.slots.map { it.name })
        assertTrue(b.slots.all { it.name!!.length <= kaConfig.nameLength }, "KA15 names are up to ${kaConfig.nameLength} characters")
        assertEquals(dac.slots.mapValues { it.value.snap }, b.slots.associate { it.slot to it.snapshot })
        assertEquals(mapOf("volume" to 48, "volume-steps" to 0, "balance" to -2, "brightness" to 5, "screen-off" to 2, "rotation" to 0, "language" to 1), b.settings)
        assertEquals(setOf("filter", "output", "dre", "uac"), b.unreadSettings.toSet())
        assertEquals(8, dac.active)
        // names are asked by position (0, 1, 2), never by the slot id
        assertEquals(setOf(0, 1, 2), dac.tx.filter { u(it, 0) == 0xBB && u(it, 4) == FiioCodec.NAME }.map { u(it, 6) }.toSet())
        // the only mutating frames are slot selects (AA 16): the registers and names are the DAC's own
        val writes = dac.tx.filter { u(it, 0) == 0xAA }.map { u(it, 4) }
        assertTrue(writes.isNotEmpty() && writes.all { it == FiioCodec.SLOT }, writes.toString())
        assertTrue(0x21 !in dac.commands())
        assertFailsWith<IllegalArgumentException> { NativeSession(ka, simKa()).captureForReset(explicitUserAction = false, takenAtMs = 0) }
        // the backup is plain text on the phone and reads back exactly
        assertEquals(b.copy(unreadSettings = b.unreadSettings.sorted()), FiioResetBackup.parse(b.toText())) // the file lists the silent settings sorted
        assertTrue(b.toText().contains("\nslot 7 name MEGA5ES count 10 preamp "), b.toText())
    }

    @Test fun `KA15 backup, reset, read after, restore - the DAC ends as it began, slots in order then settings, and the reset frame goes out exactly once`() {
        val dac = simKa()
        val session = NativeSession(ka, dac)
        val flow = FiioResetFlow(kaConfig)
        val log = ArrayList<String>()
        val before = session.captureForReset(true, 1L, log::add)
        flow.begin(before)
        val ticket = flow.confirm(ownerConsent = true)
        assertTrue(0x21 !in dac.commands(), "nothing is sent before the frame itself")
        val sent = session.factoryReset(ticket, log::add)
        flow.sent()
        assertEquals(FiioResetSent(true, null), sent)
        assertEquals(1, dac.resets)
        assertEquals("aa 0a 00 00 21 01 00 c7 ee", hex(dac.tx.last { u(it, 4) == 0x21 }.copyOf(9)))
        assertTrue(log.any { it.startsWith("reset TX aa 0a 00 00 21 01 00 c7 ee") }, log.toString())
        assertFailsWith<IllegalArgumentException> { session.factoryReset(ticket) }                       // one ticket, one frame
        assertEquals(1, dac.resets)
        val resetAt = dac.tx.indexOfLast { u(it, 4) == 0x21 }
        // the read after the reset shows what the DAC erased
        val after = session.captureForReset(true, 2L)
        val changes = flow.compared(after, labelOf = { it })
        for ((i, slot) in listOf(7, 8, 9).withIndex()) {
            assertTrue("${label(slot)} name: ${kaSeeds[i].second} -> USER${i + 1}" in changes, changes.toString())
            assertTrue(changes.any { it.startsWith("${label(slot)} EQ:") }, changes.toString())
        }
        assertTrue("brightness: 5 -> 10" in changes && "screen-off: 2 -> 0" in changes && "rotation: 0 -> 1" in changes && "language: 1 -> 0" in changes, changes.toString())
        // the restore: each USER slot (7, 8, 9) through the ordinary write, then the settings FiiO Control writes, then the slot the DAC was on
        val restoreFrom = dac.tx.size
        val restored = session.restoreAfterReset(requireNotNull(flow.backup), log::add)
        flow.restored()
        assertTrue(restored.ok, restored.lines.toString())
        assertEquals(listOf(7, 8, 9).map { "${label(it)}: restored" }, restored.lines.take(3))
        assertEquals("The DAC is back on ${label(8)}", restored.lines.last())
        assertEquals(8, dac.active)
        val restoreWrites = dac.tx.drop(restoreFrom).filter { u(it, 0) == 0xAA }.map { u(it, 4) }
        val slotWrites = setOf(FiioCodec.BAND, FiioCodec.PREAMP, FiioCodec.COUNT, FiioCodec.NAME, 0x19)
        val settingWrites = setOf(0x02, 0x0C, 0x07, 0x0E, 0x0F, 0x10, 0x11)
        assertTrue(restoreWrites.indexOfFirst { it in settingWrites } > restoreWrites.indexOfLast { it in slotWrites }, restoreWrites.toString())
        assertTrue(restoreWrites.none { it == 0x21 })
        val again = session.captureForReset(true, 3L)
        assertEquals(before.slots, again.slots)                                                          // names and every register, slot by slot
        assertEquals(before.settings, again.settings)                                                    // volume, steps and balance are verified on the KA15 (run 2026-10-21-fiio-ka15-02-phone): they go back too
        assertEquals(1, dac.commands().count { it == 0x21 })
        // the order rule is the same one: VOLUME STEPS first, the volume last
        assertTrue(FiioReset.restoreRank("volume-steps") < FiioReset.restoreRank("brightness") && FiioReset.restoreRank("brightness") < FiioReset.restoreRank("volume"))
        assertTrue(dac.tx.drop(resetAt + 1).none { u(it, 4) == 0x21 }, "the restore and the reads after the frame never send another")
    }

    @Test fun `the DAC is read and written again after a reset only once it was unplugged and plugged back in - a detach alone, or an attach alone, is not a replug`() {
        assertFalse(FiioReset.replugSeen(3, 3, 5, 5, sawMissing = false))               // nothing happened
        assertFalse(FiioReset.replugSeen(3, 4, 5, 5, sawMissing = false))               // a detach report while the DAC stayed plugged in (seen on the KA15)
        assertFalse(FiioReset.replugSeen(3, 3, 5, 6, sawMissing = true))                // an attach without a detach
        assertTrue(FiioReset.replugSeen(3, 4, 5, 6, sawMissing = false))                // detach, then attach
        assertTrue(FiioReset.replugSeen(3, 4, 5, 5, sawMissing = true))                 // detach, and the bus listed no DAC meanwhile (the attach broadcast was missed)
        // the rule holds for every model whose reset is offered, and the flow's own order is unchanged: the reset frame is still one ticket, nothing is proven
        assertTrue(FiioReset.offered(kaConfig) && FiioReset.proven(kaConfig))
        assertTrue(FiioReset.offered(requireNotNull(DeviceTarget.TINY_A.fiio)) && !FiioReset.proven(DeviceTarget.TINY_A.fiio))
        assertTrue(FiioReset.offered(requireNotNull(DeviceTarget.MELODY.fiio)) && FiioReset.proven(DeviceTarget.MELODY.fiio)) // PC runs/2026-10-22-snowsky-melody-03, phone runs/2026-10-22-snowsky-melody-01-phone
    }

    @Test fun `KA15 reset without a ticket, without the owner's yes, with a spent or a foreign ticket sends nothing`() {
        val dac = simKa()
        val session = NativeSession(ka, dac)
        val flow = FiioResetFlow(kaConfig)
        assertFailsWith<IllegalArgumentException> { flow.confirm(true) }                                  // no backup, no ticket
        assertFailsWith<IllegalArgumentException> { flow.begin(backup()) }                                // the Tiny A's backup is not the KA15's
        val b = session.captureForReset(true, 1L)
        val before = dac.tx.size
        flow.begin(b)
        assertFailsWith<IllegalArgumentException> { flow.confirm(false) }                                 // the owner did not say yes
        assertEquals(FiioResetFlow.Step.BACKED_UP, flow.step)
        assertFailsWith<IllegalArgumentException> { flow.begin(b.copy(slots = b.slots.drop(1))) }         // a hole in the backup (also: the flow is already under way)
        assertFailsWith<IllegalArgumentException> { FiioResetFlow(kaConfig).begin(b.copy(slots = b.slots.drop(1))) }
        assertFailsWith<IllegalArgumentException> { FiioResetFlow(kaConfig).begin(b.copy(slots = b.slots.map { if (it.slot == 8) it.copy(name = null) else it })) }
        assertFailsWith<IllegalArgumentException> { session.factoryReset(ticket(config)) }                // a Tiny A ticket
        assertEquals(before, dac.tx.size)
        val t = flow.confirm(true)
        session.factoryReset(t)
        assertEquals(1, dac.resets)
        assertFailsWith<IllegalArgumentException> { session.factoryReset(t) }                             // spent
        assertEquals(1, dac.resets)
        // a restore refuses another DAC's backup and a backup with a hole, before it writes a byte
        val mark = dac.tx.size
        assertFailsWith<IllegalArgumentException> { session.restoreAfterReset(backup()) }
        assertFailsWith<IllegalArgumentException> { session.restoreAfterReset(b.copy(slots = b.slots.drop(2))) }
        assertEquals(mark, dac.tx.size)
    }

    @Test fun `KA15 - no answer to the reset frame and a session that ends after it are notes, never an exception`() {
        for ((mode, note) in listOf("silent" to "no answer to the reset frame", "gone" to "the session ended after the reset frame")) {
            val dac = simKa().also { it.resetMode = mode }
            val flow = FiioResetFlow(kaConfig)
            flow.begin(NativeSession(ka, dac).captureForReset(true, 1L))
            val r = NativeSession(ka, dac).factoryReset(flow.confirm(true))
            assertFalse(r.echoed)
            assertTrue(r.note!!.startsWith(note), r.note)
            assertEquals(1, dac.resets)
            flow.sent()
            assertEquals(emptyList(), flow.compared(NativeSession(ka, dac).captureForReset(true, 2L))) // the read after decides, not the frame
        }
    }

    @Test fun `no normal KA15 session call ever sends 0x21`() {
        val dac = simKa()
        val session = NativeSession(ka, dac)
        session.read()
        session.readSettings()
        session.readSlot(7, true)
        session.selectSlot(8, true)
        session.write(Profile("p", "MEGA5ES", bands = listOf(Band("a", FilterType.PEAK, 1000.0, 1.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0), true, targetSlot = 7)
        assertTrue(dac.commands().isNotEmpty() && 0x21 !in dac.commands(), dac.commands().toString())
        assertEquals(0, dac.resets)
    }
}
