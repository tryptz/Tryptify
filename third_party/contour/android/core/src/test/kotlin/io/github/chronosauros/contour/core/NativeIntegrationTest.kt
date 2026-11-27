package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.*

/** Explicitly SYNTHETIC fake USB state machines exercise the SAME session Android invokes.
 * They verify sequencing and fail-closed behaviour, not physical firmware or audio support. */
class NativeIntegrationTest {
    private fun profile(gain: Double = -1.0, preamp: Double? = 0.0, count: Int = 1) =
        Profile("id", "test", bands = List(count) { Band("b$it", FilterType.PEAK, 1000.0, gain, 1.0) },
            preampDb = preamp, createdAt = 0, updatedAt = 0)
    private fun fiio(): DeviceTarget = requireNotNull(DeviceTarget.find(0x2972, 0x0104))
    private abstract class FakePort : NativePort {
        override var deviceKey = "generation-1"
        val events = ArrayList<String>(); var live = true; var requests = 0
        var detachAfterRequest: Int? = null
        var detachAfterMutation = false; var detachOnPause = false
        fun mutationFinished() { if (detachAfterMutation) live = false }
        override fun guard() { check(live) { "SESSION CHANGED" } }
        override fun pause(ms: Long) { events += "pause:$ms"; if (detachOnPause) live = false }
        fun finishedRequest(p: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            requests++; require(matches(p)) { "No correlated reply" }
            if (requests == detachAfterRequest) live = false
            return p
        }
    }
    private class FiioPort(val target: DeviceTarget) : FakePort() {
        private val c = FiioCodec(requireNotNull(target.fiio))
        var state = c.planOnExplicitSend(profileStatic(), target.destinationSlot!!, true).expected
        var selectionWorks = true; var brokenBackup = false; var savedMismatch = false; var selected = false; var saved = false; var detachOnSave = false; var throwAfterSave = false
        val names = mutableMapOf(0 to "TEST1", 1 to "FF5", 2 to "FH3")
        private fun word(v: Int) = listOf((v ushr 8) and 255, v and 255)
        // KA15 name replies say LEN 8 but carry IDX + a 9-byte name field (Pixel, 04.10.2026).
        private fun reply(cmd: Int, data: List<Int>) =
            (listOf(0xBB, 0x0B, 0, 0, cmd, if (cmd == FiioCodec.NAME) 8 else data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray().copyOf(63)
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
            assertEquals(c.config.reportId, id); assertEquals(NativeHidReports.Kind.OUTPUT, kind)
            val cmd = payload[4].toInt() and 255; events += "write:$cmd"
            fun u(i: Int) = payload[i].toInt() and 255
            fun v(i: Int) = (u(i) shl 8) or u(i + 1)
            when (cmd) {
                FiioCodec.SLOT -> { selected = true; if (selectionWorks) state = state.copy(activeSlot = u(6)) }
                FiioCodec.COUNT -> state = state.copy(count = u(6), bands = List(u(6)) { i -> state.bands.getOrNull(i) ?: FiioRegisters(i, 1000, 0, 100, 0) })
                FiioCodec.PREAMP -> state = state.copy(preampTenths = v(6).toShort().toInt())
                FiioCodec.BAND -> state = state.copy(bands = state.bands.map { if (it.index == u(6)) FiioRegisters(u(6), v(9), v(7).toShort().toInt(), v(11), u(13)) else it })
                c.config.saveCommand -> { saved = true; if (savedMismatch) state = state.copy(preampTenths = state.preampTenths - 1) }
                FiioCodec.NAME -> names[u(6)] = (7 until 7 + u(5) - 1).map { u(it) }.takeWhile { it != 0 }.map { it.toChar() }.joinToString("")
                else -> error("Unexpected mutation")
            }
            mutationFinished()
            if (saved && detachOnSave) live = false
            if (saved && throwAfterSave) throw java.io.IOException("DAC SESSION CHANGED; reconnect and retry") // HidTransport.sendNative checks the session after the transfer
        }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            if ((payload[0].toInt() and 255) == 0xAA) {
                // write with its AA echo (devices that must have every echo consumed)
                send(id, NativeHidReports.Kind.OUTPUT, payload)
                // the SAVE is reported by the DAC as 00 EE 00 <counter> 00 when the flash write is done; every other write is echoed
                if ((payload[4].toInt() and 255) == c.config.saveCommand) { events += "ack:${c.config.saveCommand}"; return finishedRequest(byteArrayOf(0, 0xEE.toByte(), 0, 1, 0).copyOf(63), matches) }
                return finishedRequest(payload.copyOf(63), matches)
            }
            assertEquals(0xBB, payload[0].toInt() and 255)
            val cmd = payload[4].toInt() and 255; events += "read:$cmd"
            if (brokenBackup && selected && cmd == FiioCodec.BAND) error("Incomplete destination backup")
            val data = when (cmd) {
                FiioCodec.SLOT -> listOf(state.activeSlot)
                FiioCodec.COUNT -> listOf(state.count)
                FiioCodec.PREAMP -> word(state.preampTenths)
                FiioCodec.BAND -> state.bands[payload[6].toInt() and 255].let { listOf(it.index) + word(it.gainTenths) + word(it.frequencyHz) + word(it.qHundredths) + it.typeCode }
                FiioCodec.NAME -> (payload[6].toInt() and 255).let { i -> listOf(i) + names.getValue(i).map { it.code }.plus(List(9) { 0 }).take(9) }
                else -> error("Unexpected read")
            }
            return finishedRequest(reply(cmd, data), matches)
        }
        companion object { fun profileStatic() = Profile("id", "fixture", bands = listOf(Band("b", FilterType.PEAK, 1000.0, 0.0, 1.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0) }
    }
    @Test fun `FiiO selection then full backup precedes parameters and full post save matches`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0)
        val p = profile(preamp = -2.3)
        val result = NativeSession(t, port).write(p, true)
        assertTrue(result.verified); assertTrue(result.readback!!.matches(p))
        assertEquals(-3.0, result.readback!!.preamp) // the KA15 keeps whole dB: -2.3 goes out as -3, and the read-back matches the profile
        val select = port.events.indexOf("write:22"); val pregain = port.events.indexOf("write:23")
        assertTrue(select >= 0 && pregain > select)
        assertEquals(listOf("pause:300", "read:22", "read:48", "read:48", "read:48", "read:22", "read:24", "read:23", "read:21", "read:22"), port.events.subList(select + 1, pregain))
    }
    @Test fun `a FiiO that leaves the bus on SAVE ends the send at the SAVE frame - pending, nothing read after it, no guard - and the first read after it proves the write`() {
        val t = DeviceTarget.FIIO_JA11; val port = FiioPort(t); port.detachOnSave = true
        port.state = port.state.copy(activeSlot = 4, count = 5, bands = List(5) { FiioRegisters(it, 1000, 0, 70, 0) }) // the factory state: Close EQ, five flat peaks
        val r = NativeSession(t, port).write(profile(count = 5, preamp = 0.0), true)
        assertTrue(r.pending); assertFalse(r.verified); assertNull(r.readback); assertNull(r.reason)
        val expected = requireNotNull(r.expected)
        assertEquals(3, expected.activeSlot); assertEquals(5, expected.count)
        assertFalse(port.live) // the DAC left after SAVE and the session did not guard (or throw) after it
        assertEquals("write:25", port.events.last(), port.events.toString())
        assertTrue(port.events.indexOf("write:22") < port.events.indexOf("write:23") && port.events.indexOf("write:23") < port.events.indexOf("write:25"))
        assertEquals(1, port.events.count { it == "write:25" })
        // the DAC is back: a plain read matches what the send planned, as the app's pending save compares it
        port.live = true; port.detachOnSave = false
        val back = NativeSession(t, port).read() as NativeState.Fiio
        assertTrue(back.codec.matches(expected, back.raw))
        // a DAC that came back on the wrong slot or with other bands is a mismatch, never a pass
        port.state = port.state.copy(activeSlot = 4)
        assertFalse((NativeSession(t, port).read() as NativeState.Fiio).let { back.codec.matches(expected, it.raw) })
    }
    @Test fun `a SAVE whose session check fails after the transfer (the DAC detached with the frame) is still pending, never a failed send`() {
        val t = DeviceTarget.FIIO_JA11; val port = FiioPort(t); port.throwAfterSave = true
        port.state = port.state.copy(activeSlot = 4, count = 5, bands = List(5) { FiioRegisters(it, 1000, 0, 70, 0) })
        val r = NativeSession(t, port).write(profile(count = 5, preamp = 0.0), true)
        assertTrue(r.pending); assertFalse(r.verified); assertNotNull(r.expected)
        assertEquals("write:25", port.events.last(), port.events.toString())
    }
    @Test fun `a setting write that makes the DAC leave the bus (the JA11 mic) is the SET alone - no read-back - and a lost echo is not an error`() {
        val t = DeviceTarget.FIIO_JA11
        class LeavingPort : NativePort {
            override val deviceKey = "g1"; val frames = ArrayList<List<Int>>(); var live = true
            override fun guard() { check(live) { "SESSION CHANGED" } }
            override fun pause(ms: Long) {}
            override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { error("a setting SET is a request") }
            override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
                frames += payload.take(9).map { it.toInt() and 255 }; live = false
                throw java.io.IOException("DAC SESSION CHANGED; reconnect and retry") // the DAC left with the frame
            }
        }
        val port = LeavingPort()
        NativeSession(t, port).sendSettingThenLeave("mic", 0)
        assertEquals(1, port.frames.size, port.frames.toString())
        assertEquals(listOf(0xAA, 0x0A, 0), port.frames[0].take(3)); assertEquals(0x12, port.frames[0][4]); assertEquals(0, port.frames[0][6])
        // a row that stays on the bus is refused here: it has its read-back in writeSetting
        assertFailsWith<IllegalArgumentException> { NativeSession(t, LeavingPort()).sendSettingThenLeave("volume", 59) }
    }
    @Test fun `RETRO NANO found on Close EQ answers the preamp query with a 0x18 frame - read as no EQ active, four queries, no band reads - and no other DAC or slot accepts that frame`() {
        val t = DeviceTarget.RETRO_NANO
        // dac-lab run 2026-11-13-snowsky-retro-nano-01-mac: the frames as the unit answered them (report 7, 65 data bytes)
        fun hexFrame(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray().copyOf(65)
        val replies = mapOf(0x16 to "bb 0b 00 00 16 01 0b 00 ee", 0x18 to "bb 0b 00 00 18 01 0a 00 ee", 0x17 to "bb 0b 00 00 18 01 00 00 ee")
        class ScriptedPort : NativePort {
            override val deviceKey = "g1"; val sent = ArrayList<String>()
            override fun guard() {}
            override fun pause(ms: Long) {}
            override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { error("a read only asks") }
            override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
                sent += payload.take(8).joinToString(" ") { "%02x".format(it) }
                val reply = hexFrame(replies.getValue(payload[4].toInt() and 255))
                if (!matches(reply)) throw java.io.IOException("Native request timed out")
                return reply
            }
        }
        val port = ScriptedPort()
        val state = NativeSession(t, port).read() as NativeState.Fiio
        assertEquals(listOf("bb 0b 00 00 16 00 00 ee", "bb 0b 00 00 18 00 00 ee", "bb 0b 00 00 17 00 00 ee", "bb 0b 00 00 16 00 00 ee"), port.sent)
        assertEquals(11, state.slot); assertEquals(10, state.raw.count); assertEquals(0, state.raw.preampTenths); assertEquals(10, state.raw.bands.size)
        assertTrue(state.raw.bands.all { it.frequencyHz == 0 && it.gainTenths == 0 })
        // the frame is accepted for this model on its bypass slot only, and only with data byte 0
        val c = FiioCodec(requireNotNull(t.fiio)); val frame = hexFrame(replies.getValue(0x17)).copyOf(9)
        assertTrue(c.isBypassNoEqReply(frame, 11)); assertFalse(c.isBypassNoEqReply(frame, 160))
        assertFalse(c.isBypassNoEqReply(frame.copyOf().also { it[6] = 1 }, 11))
        for (other in listOf(DeviceTarget.TINY_A, DeviceTarget.KA15, DeviceTarget.FIIO_JIEZI)) assertFalse(FiioCodec(requireNotNull(other.fiio)).isBypassNoEqReply(frame, other.fiio!!.bypassSlot!!), other.caps.name)
    }
    @Test fun `KA15 save to another USER slot switches to it and names it after the profile`() {
        val t = fiio(); val port = FiioPort(t)
        val p = profile().copy(name = "Nightfall Łąka")
        val r = NativeSession(t, port).write(p, true, targetSlot = 8)
        assertTrue(r.verified); assertNull(r.reason)
        assertEquals(8, port.state.activeSlot)
        assertEquals("NIGHTFA", port.names[1]); assertEquals("TEST1", port.names[0])
        assertEquals("NIGHTFA", (r.readback as NativeState.Fiio).names[8])
        assertTrue(port.events.indexOf("write:22") < port.events.indexOf("write:25"))
        assertTrue(port.events.indexOf("write:25") < port.events.indexOf("write:48"))
    }
    @Test fun `KA15 reads the report of its flash save, then waits 2 s before the first read, and a silent DAC does not fail the write`() {
        val t = fiio(); val port = FiioPort(t)
        val r = NativeSession(t, port).write(profile(), true)
        assertTrue(r.verified, r.reason)
        val save = port.events.indexOf("write:25")
        assertEquals(listOf("write:25", "ack:25", "pause:2000"), port.events.subList(save, save + 3), port.events.toString())
        assertTrue(port.events.subList(save + 3, port.events.size).first().startsWith("read:"), "the first poll comes after the 2 s wait")
        assertEquals(1, port.events.count { it == "pause:2000" })
    }
    @Test fun `K13 R2R writes USER10 without any name command and reads no names`() {
        val t = DeviceTarget.K13; val port = FiioPort(t)
        assertEquals(160, port.state.activeSlot)
        val r = NativeSession(t, port).write(profile().copy(name = "Nightfall"), true, targetSlot = 169)
        assertTrue(r.verified); assertNull(r.reason)
        assertEquals(169, port.state.activeSlot)
        assertTrue((r.readback as NativeState.Fiio).names.isEmpty())
        assertTrue(port.events.none { it == "write:48" || it == "read:48" }, port.events.toString())
        assertTrue(port.events.indexOf("write:22") < port.events.indexOf("write:25"))
    }
    @Test fun `K13 R2R sends and verifies a HIGH PASS and a BAND PASS band, written at 0 dB`() {
        val t = DeviceTarget.K13; val port = FiioPort(t)
        val bands = listOf(Band("hp", FilterType.HIGH_PASS, 100.0, 4.0, 1.0), Band("bp", FilterType.BAND_PASS, 2000.0, -3.0, 2.0))
        val p = profile().copy(bands = bands)
        val r = NativeSession(t, port).write(p, true, targetSlot = 169)
        assertTrue(r.verified, r.reason)
        val back = (r.readback as NativeState.Fiio).raw.bands
        assertEquals(listOf(5, 3), back.take(2).map { it.typeCode }) // HP 5, BP 3
        assertEquals(listOf(0, 0), back.take(2).map { it.gainTenths })
        assertEquals(listOf(FilterType.HIGH_PASS, FilterType.BAND_PASS), r.readback!!.importExact().bands.take(2).map { it.type })
    }
    /** The K13 R2R keeps its hardware-proven 300 ms (writeGapMs) AFTER every write; a bench log's pauseBeforeMs of the next report carries it. */
    @Test fun `K13 R2R pauses 300 ms after every preamp, count and band write and the KA15 does not`() {
        fun gaps(t: DeviceTarget): List<Boolean> {
            val port = FiioPort(t); val f = requireNotNull(t.fiio)
            NativeSession(t, port).write(profile(count = 3), true, targetSlot = t.destinationSlot)
            val ev = port.events
            val idx = ev.indices.filter { i -> ev[i].startsWith("write:") && ev[i].removePrefix("write:").toInt().let { it == FiioCodec.PREAMP || it == FiioCodec.COUNT || it == FiioCodec.BAND } }
            assertTrue(idx.size >= 5, ev.toString()) // preamp, count, 3 bands (10 with the padding on the K13)
            return idx.map { i ->
                val next = (i + 1 until ev.size).first { !ev[it].startsWith("pause:") }
                ev.subList(i + 1, next).contains("pause:${f.writeGapMs}")
            }
        }
        assertEquals(300, requireNotNull(DeviceTarget.K13.fiio).writeGapMs)
        assertTrue(gaps(DeviceTarget.K13).all { it })
        assertEquals(0, requireNotNull(fiio().fiio).writeGapMs)
    }
    @Test fun `KA15 name reply from the Pixel parses with its 9-byte name field`() {
        val c = FiioCodec(requireNotNull(fiio().fiio))
        val rx = "bb 0b 00 00 30 08 00 54 45 53 54 31 00 00 00 00 ac ee".split(" ").map { it.toInt(16).toByte() }.toByteArray().copyOf(63)
        assertEquals("TEST1", c.parseName(c.logicalReply(7, rx, 63), 7))
        // Pixel 04.10 00:38:59: the field's last two bytes held the tail of the previous frame
        val stale = "bb 0b 00 00 30 08 01 4e 49 47 48 54 46 41 ab ee c8 ee".split(" ").map { it.toInt(16).toByte() }.toByteArray().copyOf(63)
        assertEquals("NIGHTFA", c.parseName(c.logicalReply(7, stale, 63), 8))
    }
    @Test fun `FiiO failed selection blocks band mutations`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0); port.selectionWorks = false
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(listOf("write:22"), port.events.filter { it.startsWith("write") })
    }
    @Test fun `FiiO incomplete backup blocks all parameter writes`() {
        val t = fiio(); val port = FiioPort(t); port.state = port.state.copy(activeSlot = 0); port.brokenBackup = true
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(listOf("write:22"), port.events.filter { it.startsWith("write") })
    }
    @Test fun `FiiO destination already active is written without a preset switch`() {
        val t = fiio(); val port = FiioPort(t)
        val r = NativeSession(t, port).write(profile(), true)
        assertTrue(r.verified)
        assertTrue(port.events.none { it == "write:22" })
    }
    @Test fun `FiiO save mismatch cannot verify or mark ON DAC`() {
        val t = fiio(); val port = FiioPort(t); port.savedMismatch = true
        val r = NativeSession(t, port).write(profile(), true)
        assertFalse(r.verified); assertFalse(r.readback!!.matches(profile()))
    }
    @Test fun `KA15 routes native by its exact VID PID`() {
        assertNotNull(fiio().fiio); assertNull(fiio().walkplay)
        assertEquals("FIIO KA15", fiio().caps.name)
        assertEquals(setOf(7, 8, 9), fiio().fiio!!.userSlots)
    }
    @Test fun `descriptor separates input and output with exact one ID`() {
        val bytes = listOf(0x06, 0x00, 0xFF, 0x09, 1, 0xA1, 1, 0x85, 7, 0x75, 8, 0x95, 63, 0x81, 2, 0x95, 63, 0x91, 2, 0xC0)
        val s = NativeHidReports.parse(bytes.map { it.toByte() }.toByteArray())
        assertEquals(64, s.rawSize(7, NativeHidReports.Kind.INPUT))
        assertEquals(64, s.rawSize(7, NativeHidReports.Kind.OUTPUT))
        s.requireSameOwner(7, NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)
        val w = s.wire(7, NativeHidReports.Kind.OUTPUT, byteArrayOf(0xBB.toByte(), 0x0B))
        assertEquals(64, w.size); assertEquals(7, w[0].toInt()); assertEquals(0xBB.toByte(), w[1])
        assertFails { s.payload(7, NativeHidReports.Kind.INPUT, w.copyOf(63)) }
        assertFails { s.rawSize(7, NativeHidReports.Kind.FEATURE) }
    }
    /** Real FiiO K13 R2R HID interface 3 (owner's unit, 10.10.2026): report 7 in a Generic Desktop collection with Usage Undefined. */
    @Test fun `K13 R2R real descriptor qualifies only on the FiiO path`() {
        val bytes = listOf(0x05, 0x01, 0x09, 0x00, 0xa1, 0x01, 0x85, 0x07, 0x15, 0x00, 0x25, 0xff, 0x19, 0x01, 0x29, 0x08,
            0x95, 0x20, 0x75, 0x08, 0x81, 0x02, 0x19, 0x01, 0x29, 0x08, 0x91, 0x02, 0xc0, 0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01, 0x85,
            0x02, 0x15, 0x00, 0x25, 0x01, 0x09, 0xcd, 0x09, 0xb5, 0x09, 0xb6, 0x09, 0xe9, 0x09, 0xea, 0x09, 0xe2, 0x75, 0x01, 0x95,
            0x06, 0x81, 0x02, 0x95, 0x02, 0x81, 0x01, 0xc0).map { it.toByte() }.toByteArray()
        val s = NativeHidReports.parse(bytes, undefinedDesktop = true)
        assertEquals(33, s.rawSize(7, NativeHidReports.Kind.INPUT))
        assertEquals(33, s.rawSize(7, NativeHidReports.Kind.OUTPUT))
        s.requireSameOwner(7, NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)
        assertFails { s.rawSize(2, NativeHidReports.Kind.INPUT) } // consumer-control report stays out
        assertTrue(NativeHidReports.parse(bytes).rawSizes.isEmpty()) // other families keep the vendor-page rule
        // a Generic Desktop collection with a defined usage (Pointer) is still not a vendor page
        val pointer = listOf(0x05, 0x01, 0x09, 0x01, 0xA1, 0x01, 0x85, 7, 0x75, 8, 0x95, 63, 0x81, 2, 0x95, 63, 0x91, 2, 0xC0)
        assertTrue(NativeHidReports.parse(pointer.map { it.toByte() }.toByteArray(), undefinedDesktop = true).rawSizes.isEmpty())
    }
    @Test fun `native reads never mutate`() {
        val f = FiioPort(fiio())
        assertIs<NativeState.Fiio>(NativeSession(fiio(), f).read()); assertTrue(f.events.none { it.startsWith("write") })
    }
    @Test fun `invalid last band over capacity and missing HOLD are rejected before mutation`() {
        val t = fiio(); val port = FiioPort(t)
        assertFails { NativeSession(t, port).write(profile(), false) }
        assertFails { NativeSession(t, port).write(profile(count = 11), true) }
        assertFails { NativeSession(t, port).write(profile(preamp = -24.1), true) }
        assertFails { NativeSession(t, port).write(profile(preamp = 0.1), true) }
        assertTrue(port.events.none { it.startsWith("write") })
    }
    @Test fun `generation guard aborts after every read before snapshot or mutation`() {
        val t = fiio(); val port = FiioPort(t); port.detachAfterRequest = 1
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertFalse(port.events.any { it.startsWith("write") })
    }
    @Test fun `native imports return the whole-dB preamp the KA15 holds and reproduce raw matcher`() {
        val t = fiio(); val port = FiioPort(t)
        val p = profile(preamp = -2.3); NativeSession(t, port).write(p, true)
        val state = NativeSession(t, port).read(); val eq = state.importExact()
        assertEquals(-3.0, eq.preampDb); assertTrue(state.matches(p.copy(bands = eq.bands, preampDb = eq.preampDb)))
    }
    @Test fun `generation change after mutation prevents every following report and publication`() {
        val t = fiio(); val port = FiioPort(t); port.detachAfterMutation = true
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertEquals(1, port.events.count { it.startsWith("write:") })
    }
    @Test fun `generation change during pacing cannot return a verified result`() {
        val t = fiio(); val port = FiioPort(t); port.detachOnPause = true
        assertFails { NativeSession(t, port).write(profile(), true) }
        assertFalse(port.live)
    }
    @Test fun `KA15 pregain is whole dB within its own range and A B stays Micro only`() {
        // Intended range: register -12..+12 dB minus the measured 12 dB offset (04.10.2026).
        // Whole dB since 26.10.2026: a fractional preamp reset the KA15 during the send (Pixel, FW 1.1.6).
        val t = fiio(); assertEquals(-24.0, t.preampMin); assertEquals(0.0, t.preampMax); assertEquals(1.0, t.preampStep)
        assertEquals(-11.7, t.shownPreamp(profile(preamp = -11.7)))
        assertFalse(t.supportsAb); assertTrue(t.native); assertFalse(t.stable)
        assertEquals(10, t.caps.bands); assertEquals(-12.0, t.caps.gainMinDb); assertEquals(12.0, t.caps.gainMaxDb)
        assertTrue(t.issues(profile(preamp = -24.1)).isNotEmpty())
        assertTrue(t.issues(profile(preamp = 0.1)).isNotEmpty())
        assertTrue(t.issues(profile(preamp = -24.0)).isEmpty())
        assertTrue(t.issues(profile(preamp = 0.0)).isEmpty())
        assertTrue(t.issues(profile(count = 11)).isNotEmpty())
        assertTrue(t.issues(profile(count = 10)).isEmpty())
    }
}
