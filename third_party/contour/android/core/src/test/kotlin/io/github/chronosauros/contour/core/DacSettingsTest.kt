package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DAC Settings, round 1 (reads only). The port is a synthetic Tiny A: it answers the settings reads, and its 0x16 slot register
 * goes stale after any other query (PROTOCOL.md, commit 1a6f96b) and shows byte 6 of the last reply, as the cold-plug log does. Checks the frames, the decoders, NO REPLY, that nothing is written, and that a
 * normal read after the settings reads still gets through.
 */
class DacSettingsTest {
    private val tiny = DeviceTarget.TINY_A
    private fun u(b: Byte) = b.toInt() and 255

    private fun crc(bytes: List<Int>): Int {
        var c = 0
        for (b in bytes) { c = c xor b; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 } }
        return c
    }

    @Test fun `settings read frames are get frames with sequence and CRC and never a set or 0x21`() {
        val c = FiioCodec(requireNotNull(tiny.fiio))
        val f = c.querySetting(0x12)
        val body = listOf(0xBB, 0x0B, 0, 0, 0x12, 0)
        assertEquals(body + listOf(crc(body), 0xEE), f.bytes)
        assertEquals(FiioEffect.READ_ONLY, f.effect)
        assertEquals(1, c.querySetting(0x06).bytes[3]) // the sequence counts
        assertFailsWith<IllegalArgumentException> { c.querySetting(0x21) }
        for (s in tiny.settings) assertTrue(s.writeCommand != 0x21, s.id)
    }

    @Test fun `FiioCodec still refuses 0x21 as a save command`() {
        assertFailsWith<IllegalArgumentException> { FiioCodec(requireNotNull(tiny.fiio).copy(saveCommand = 0x21)) }
        FiioCodec(requireNotNull(tiny.fiio)) // 0x19 is accepted
    }

    private fun cmds(t: DeviceTarget) = t.settings.map { (it.read as SettingRead.FiioGet).command }

    @Test fun `Tiny A, Melody and KA15 have FiiO Control's lists in screen order, K13 and the WalkPlay DACs have none`() {
        assertEquals(listOf("volume", "volume-steps", "balance", "mic", "light", "uac"), tiny.settings.map { it.id })
        assertEquals(listOf(0x02, 0x0C, 0x07, 0x12, 0x06, 0x20), cmds(tiny))
        assertEquals(
            listOf("volume", "volume-steps", "balance", "filter", "gain", "output", "dre", "mic", "light", "buttons", "uac"),
            DeviceTarget.MELODY.settings.map { it.id },
        )
        assertEquals(listOf(0x02, 0x0C, 0x07, 0x03, 0x04, 0x05, 0x32, 0x12, 0x06, 0x0D, 0x20), cmds(DeviceTarget.MELODY))
        assertEquals(
            listOf("volume", "volume-steps", "balance", "filter", "output", "dre", "uac", "brightness", "screen-off", "rotation", "language"),
            DeviceTarget.KA15.settings.map { it.id },
        )
        assertEquals(listOf(0x02, 0x0C, 0x07, 0x03, 0x05, 0x32, 0x20, 0x0E, 0x0F, 0x10, 0x11), cmds(DeviceTarget.KA15))
        assertTrue(DeviceProtocol.MICRO.settings.isEmpty() && DeviceProtocol.MAX.settings.isEmpty() && DeviceTarget.MICRO.settings.isEmpty())
        assertEquals(listOf("dac-filter", "work-mode", "balance", "mic"), DeviceTarget.MAX.settings.map { it.id })   // the Max's four verified rows (a release); the TRN's are four
        assertTrue(DeviceTarget.K13.settings.isEmpty() && DeviceTarget.K13.info.isEmpty())
        // FiiO Control's config: VOLUME STEPS is 0x0C, USB AUDIO is 0x20, the balance range is 12; a KA15 has no LED, microphone, gain or buttons mode
        assertEquals(12, tiny.settings.single { it.id == "balance" }.max)
        assertTrue(DeviceTarget.KA15.settings.none { it.id in setOf("light", "mic", "gain", "buttons") })
    }

    @Test fun `no list reads a command the probe forbids or the probe does not know, and only self-tested settings are verified for writing`() {
        // the settings that passed the write self-test on that model's real DAC (CAPTURES.md, 17.10.2026); USB AUDIO never
        val verified = mapOf(
            tiny to setOf("volume", "volume-steps", "balance", "mic", "light"),
            DeviceTarget.MELODY to setOf("volume", "volume-steps", "balance", "filter", "gain", "output", "dre", "mic", "light", "buttons"),
            DeviceTarget.KA15 to setOf("volume", "volume-steps", "balance", "filter", "output", "dre", "brightness", "screen-off", "rotation", "language"), // runs/2026-10-21-fiio-ka15-02-phone; USB AUDIO never
        )
        for ((t, ids) in verified) {
            assertEquals(ids, t.settings.filter { it.writeVerified }.map { it.id }.toSet(), t.caps.name)
            for (s in t.settings) {
                val cmd = (s.read as SettingRead.FiioGet).command
                assertTrue(cmd !in ProbePlan.FIIO_FORBIDDEN && cmd in ProbePlan.FIIO_COMMANDS, "${t.caps.name} ${s.id} 0x%02x".format(cmd))
                assertTrue(s.writeCommand !in ProbePlan.FIIO_FORBIDDEN, s.id)
            }
            for (i in t.info) assertTrue(i.command !in ProbePlan.FIIO_FORBIDDEN && i.command in ProbePlan.FIIO_COMMANDS, i.id)
            assertEquals(t.settings.map { it.id }.toSet() + t.info.map { it.id }, t.settingsPreview.keys, "the preview covers every row and info id of ${t.caps.name}")
        }
    }

    @Test fun `sections head SOUND, DEVICE and SCREEN and the Tiny A has none`() {
        assertTrue(tiny.settings.all { it.section == null })
        assertEquals(listOf("SOUND" to "volume", "DEVICE" to "mic"), DeviceTarget.MELODY.settings.filter { it.section != null }.map { it.section!! to it.id })
        assertEquals(listOf("SOUND" to "volume", "DEVICE" to "uac", "SCREEN" to "brightness"), DeviceTarget.KA15.settings.filter { it.section != null }.map { it.section!! to it.id })
        // a section starts a new group, so the gap above its header is the group gap
        for (t in listOf(DeviceTarget.MELODY, DeviceTarget.KA15)) t.settings.forEachIndexed { i, s ->
            if (s.section != null && i > 0) assertTrue(s.group != t.settings[i - 1].group, "${t.caps.name} ${s.id}")
        }
    }

    @Test fun `FILTER and SCREEN OFF are long choices with their option lists, 2-3 options stay inline`() {
        val filter = DeviceTarget.MELODY.settings.single { it.id == "filter" }
        assertTrue(filter.longChoice)
        assertEquals(listOf("MINIMUM PHASE FAST", "LINEAR PHASE FAST", "MINIMUM PHASE SLOW", "LINEAR PHASE SLOW", "NON OVER-SAMPLING"), filter.options)
        assertEquals("NOS", filter.shortOption(4)); assertEquals("MIN F", filter.shortOption(0)); assertEquals("--", filter.shortOption(9))
        val off = DeviceTarget.KA15.settings.single { it.id == "screen-off" }
        assertTrue(off.longChoice)
        assertEquals(listOf("15 S", "30 S", "1 MIN", "2 MIN", "5 MIN", "10 MIN", "30 MIN"), off.options)
        assertEquals("1 MIN", off.shortOption(2)) // no short forms: the option itself
        for (t in listOf(tiny, DeviceTarget.MELODY, DeviceTarget.KA15)) for (s in t.settings) {
            if (s.kind == SettingKind.TOGGLE || s.id in setOf("uac", "gain", "output", "buttons", "language", "volume-steps")) assertTrue(!s.longChoice, s.id)
        }
        assertEquals(listOf("HIGH", "LOW"), DeviceTarget.MELODY.settings.single { it.id == "gain" }.options)
        assertEquals(listOf("CLASS AB", "CLASS H"), DeviceTarget.KA15.settings.single { it.id == "output" }.options)
        assertEquals(listOf("MODE A", "MODE B"), DeviceTarget.MELODY.settings.single { it.id == "buttons" }.options)
        assertEquals(listOf("CHINESE", "ENGLISH"), DeviceTarget.KA15.settings.single { it.id == "language" }.options)
        val brightness = DeviceTarget.KA15.settings.single { it.id == "brightness" }
        assertEquals(0 to 10, brightness.min to brightness.max)
        // FILTER 0..3 are inferred from FiiO Control's list order: the rank stays below hardware
        assertTrue(filter.evidence != EvidenceRank.HW)
    }

    @Test fun `the filter, screen-off and the two-way choices decode their option range and refuse bytes outside it`() {
        fun dec(t: DeviceTarget, id: String, v: Int): Int? {
            val s = t.settings.single { it.id == id }; val r = s.read as SettingRead.FiioGet
            val c = FiioCodec(requireNotNull(t.fiio))
            val rx = reply(v); rx[4] = r.command.toByte()
            return runCatching { r.decode(c.settingReply(rx, r.command, r.replyData)) }.getOrNull()
        }
        assertEquals(4, dec(DeviceTarget.MELODY, "filter", 4)); assertNull(dec(DeviceTarget.MELODY, "filter", 5))
        assertEquals(6, dec(DeviceTarget.KA15, "screen-off", 6)); assertNull(dec(DeviceTarget.KA15, "screen-off", 7))
        assertEquals(1, dec(DeviceTarget.MELODY, "output", 1)); assertNull(dec(DeviceTarget.MELODY, "output", 2))
        assertEquals(1, dec(DeviceTarget.KA15, "language", 1)); assertNull(dec(DeviceTarget.KA15, "language", 2))
        assertEquals(5, dec(DeviceTarget.KA15, "brightness", 5))
    }

    @Test fun `the header info reads 0x09 and 0x0B and says 48 kHz and FW 1_2_0, a missing part is left out of the line`() {
        assertEquals(listOf(0x09, 0x0B), DeviceTarget.MELODY.info.map { it.command })
        assertEquals(DeviceTarget.MELODY.info, DeviceTarget.KA15.info)
        val (rate, fw) = DeviceTarget.MELODY.info
        assertEquals("48 kHz", rate.format(2)); assertEquals("44.1 kHz", rate.format(1)); assertEquals("DSD64", rate.format(11)); assertEquals("99", rate.format(99))
        assertEquals("FW 1.2.0", fw.format(10_200)); assertEquals("FW 1.1.6", fw.format(10_106))
        val c = FiioCodec(requireNotNull(DeviceTarget.MELODY.fiio))
        // probe capture: 0x0B answers `01 02 00` with no CRC byte before the terminator
        val rx = listOf(0xBB, 0x0B, 0, 7, 0x0B, 3, 1, 2, 0, 0xEE).map { it.toByte() }.toByteArray().copyOf(63)
        assertEquals(10_200, fw.decode(c.settingReply(rx, 0x0B, fw.replyData, crcOptional = true)))
        assertEquals(10, c.settingReply(rx, 0x0B, fw.replyData, crcOptional = true).size)
        assertFailsWith<IllegalArgumentException> { c.settingReply(rx, 0x0B, fw.replyData) } // a settings read still wants its CRC byte
        assertEquals(2, rate.decode(listOf(0xBB, 0x0B, 0, 7, 0x09, 1, 2, 0x55, 0xEE).map { it.toByte() }.toByteArray()))
    }

    @Test fun `JA11 settings are volume mic and the read only USB audio, its firmware has two data bytes and a CRC (FW 2_20), and its volume tops at 60 with no VOLUME STEPS row`() {
        val ja = DeviceTarget.FIIO_JA11
        assertEquals(listOf("volume", "mic", "uac"), ja.settings.map { it.id })
        assertEquals(listOf(0x02, 0x12, 0x20), ja.settings.map { it.writeCommand })
        // volume and the in-line mic were written, read back and (mic) re-enumerated on the Mac: vendor parity; USB audio is never written
        assertEquals(setOf("volume", "mic"), ja.settings.filter { it.writeVerified }.map { it.id }.toSet())
        assertEquals(listOf(0x09, 0x0B), ja.info.map { it.command }); assertEquals(listOf("rate", "fw2"), ja.info.map { it.id })
        val (rate, fw2) = ja.info
        assertEquals("352.8 kHz", rate.format(7)) // the unit reads 7 on the Mac
        // dac-lab run 2026-11-11-fiio-ja11-01-mac: `bb 0b 00 00 0b 02 02 14 b6 ee` (major, minor, CRC), stale bytes after the terminator
        val c = FiioCodec(requireNotNull(ja.fiio))
        val rx = listOf(0xBB, 0x0B, 0, 0, 0x0B, 2, 2, 0x14, 0xB6, 0xEE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }.toByteArray()
        val logical = c.settingReply(rx, 0x0B, fw2.replyData, crcOptional = true)
        assertEquals(10, logical.size); assertEquals(220, fw2.decode(logical)); assertEquals("FW 2.20", fw2.format(220)); assertEquals("FW 2.05", fw2.format(205))
        // the three-byte firmware of the other FiiO DACs is a different row and stays as it was
        assertEquals("FW 1.2.0", DacSettings.FIRMWARE.format(10_200))
        val volume = ja.settings.first { it.id == "volume" }
        assertNull(volume.maxRule); assertEquals(60, DacWrite.hardMax(volume))
        DacWrite.checkValue(volume, 60); assertFailsWith<IllegalArgumentException> { DacWrite.checkValue(volume, 61) }
        // a model with a VOLUME STEPS row keeps its two tops (60 or 120)
        assertEquals(120, DacWrite.hardMax(DeviceTarget.KA15.settings.first { it.id == "volume" }))
        // USB AUDIO (0x20) is never written; the mic toggle and the volume are, behind the review gate
        assertTrue(ja.settings.first { it.id == "uac" }.readOnly)
        for (s in ja.settings) assertTrue(s.writeCommand !in DacWrite.NEVER_SEND, s.id)
        // the in-line mic write re-enumerates the unit (measured 11.11.2026): its row says so, no other row of any model does
        assertEquals(listOf("mic"), ja.settings.filter { it.reconnectsAfterWrite }.map { it.id })
        for (t in listOf(DeviceTarget.KA15, DeviceTarget.MELODY, DeviceTarget.TINY_A)) assertTrue(t.settings.none { it.reconnectsAfterWrite }, t.caps.name)
    }

    @Test fun `FiiO pauses between reads are 50 ms Tiny A, 100 ms Melody, 200 ms KA15, and the K13 has no FiiO layer`() {
        assertEquals(50, ProbePlan.fiioGapMs(requireNotNull(tiny.fiio)))
        assertEquals(100, ProbePlan.fiioGapMs(requireNotNull(DeviceTarget.MELODY.fiio)))
        assertEquals(200, ProbePlan.fiioGapMs(requireNotNull(DeviceTarget.KA15.fiio)))
        assertNull(DeviceTarget.K13.fiio?.let { ProbePlan.fiioGapMs(it) })
    }

    @Test fun `balance shows the side the sound moves to, as FiiO Control does`() {
        val bal = tiny.settings.single { it.id == "balance" }
        assertEquals(12, bal.max)
        assertEquals(-3,decode("balance", 0x07, reply(0, 3)))  // FiiO Control L3 reads 00 03: right attenuated, sound to the left
        assertEquals(3, decode("balance", 0x07, reply(3, 0)))   // R3
    }

    @Test fun `the volume top follows VOLUME STEPS and is 60 without a reply`() {
        val volume = tiny.settings.single { it.id == "volume" }
        assertEquals(60, volume.maxFor { 0 })
        assertEquals(120, volume.maxFor { if (it == "volume-steps") 1 else null })
        assertEquals(60, volume.maxFor { null })
        assertEquals(60, volume.maxFor { 7 }) // an option index the rule does not know
    }

    private fun reply(a6: Int, a7: Int? = null, len: Int = if (a7 == null) 1 else 2): ByteArray {
        val data = listOfNotNull(a6, a7)
        return (listOf(0xBB, 0x0B, 0, 9, 0x00, len) + data + listOf(0x55, 0xEE, 0xAB, 0xCD)).map { it.toByte() }.toByteArray().copyOf(63)
    }
    private fun decode(id: String, cmd: Int, rx: ByteArray): Int? {
        val s = tiny.settings.single { it.id == id }
        val c = FiioCodec(requireNotNull(tiny.fiio))
        rx[4] = cmd.toByte()
        return runCatching { (s.read as SettingRead.FiioGet).let { it.decode(c.settingReply(rx, cmd, it.replyData)) } }.getOrNull()
    }

    @Test fun `decoders - volume, balance sides, toggles and bytes that make no sense`() {
        assertEquals(37, decode("volume", 0x02, reply(37)))
        assertEquals(0, decode("balance", 0x07, reply(0, 0)))
        assertEquals(3, decode("balance", 0x07, reply(3, 0)))   // left attenuated: the sound moves right = R 3
        assertEquals(-2, decode("balance", 0x07, reply(0, 2)))  // right attenuated: the sound moves left = L 2
        assertNull(decode("balance", 0x07, reply(1, 1)))        // both sides attenuated: not a balance
        assertNull(decode("balance", 0x07, reply(4)))           // LEN 1: the right byte is missing
        assertEquals(1, decode("mic", 0x12, reply(1)))
        assertEquals(0, decode("light", 0x06, reply(0)))
        assertNull(decode("mic", 0x12, reply(7)))
        assertNull(decode("volume", 0x02, reply(5, len = 0)))   // no data byte
        val bad = reply(1); bad[7 + 1] = 0
        assertNull(decode("mic", 0x12, bad))                    // terminator missing
    }

    /** A Tiny A playing USER2 (161) with a stale slot register; [staleEchoesReply]: the register shows byte 6 of the last reply (what the log shows: 09 after band 9), else the last command. */
    private class TinyPort(val staleEchoesReply: Boolean, val answer: Set<Int> = setOf(0x02, 0x07, 0x0C, 0x20, 0x12, 0x06, 0x09, 0x0B)) : NativePort {
        override val deviceKey = "tiny-a-fake"
        val sent = ArrayList<Int>()
        val values = mapOf(
            0x02 to listOf(48), 0x07 to listOf(0, 2), 0x0C to listOf(0), 0x20 to listOf(1), 0x12 to listOf(1), 0x06 to listOf(1),
            0x09 to listOf(2), 0x0B to listOf(1, 2, 0),
        )
        private var stale = 0
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a read must not write: ${payload[4]}")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(0xBB, u0(payload[0]))
            val cmd = u0(payload[4]); sent += cmd
            val data: List<Int> = when (cmd) {
                0x16 -> listOf(stale)
                0x18 -> listOf(10)
                0x17 -> listOf(0, 0)
                0x15 -> listOf(u0(payload[6]), 0, 0, 0, 100, 0, 100, 0)
                0x30 -> listOf(u0(payload[6])) + "NAME".map { it.code } + List(5) { 0 }
                else -> if (cmd in answer) values.getValue(cmd) else throw java.io.IOException("Native request timed out or returned invalid state; Tiny A active slot unknown")
            }
            val len = if (cmd == 0x30) 9 else data.size
            // the firmware reply (0x0B) has no CRC byte before the terminator
            val r = (listOf(0xBB, 0x0B, 0, 0, cmd, len) + data + (if (cmd == 0x0B) listOf(0xEE) else listOf(0, 0xEE))).map { it.toByte() }.toByteArray().copyOf(63)
            require(matches(r))
            stale = if (staleEchoesReply) data.first() else cmd
            return r
        }
        private fun u0(b: Byte) = b.toInt() and 255
    }

    @Test fun `readSettings reads the six settings, levels first, the 0-1 settings after them, then the sample rate and firmware, and writes nothing`() {
        val port = TinyPort(staleEchoesReply = true)
        val log = ArrayList<String>()
        val r = NativeSession(tiny, port).readSettings(log::add)
        assertEquals(listOf(0x02, 0x07, 0x0C, 0x12, 0x06, 0x20, 0x09, 0x0B), port.sent)
        assertEquals(
            mapOf("volume" to 48, "balance" to -2, "volume-steps" to 0, "mic" to 1, "light" to 1, "uac" to 1, "rate" to 2, "fw" to 10_200),
            r.associate { it.id to it.value },
        )
        assertEquals(16, log.count { it.contains(" TX ") || it.contains(" RX ") })
        assertTrue(log.first { it.contains("volume TX") }.startsWith("settings volume TX bb 0b 00 00 02 00 "))
    }

    @Test fun `a silent setting is NO REPLY and the others are still read`() {
        val port = TinyPort(staleEchoesReply = true, answer = setOf(0x02, 0x06))
        val r = NativeSession(tiny, port).readSettings().associateBy { it.id }
        assertEquals(48, r.getValue("volume").value)
        assertNull(r.getValue("balance").value); assertEquals("no reply", r.getValue("balance").note)
        assertNull(r.getValue("mic").value)
        assertEquals(1, r.getValue("light").value)
    }

    @Test fun `a normal read after the settings reads still gets through when the slot register holds the last reply byte`() {
        // The device log (NativeMelodyLogTest): opening reply a boot value, closing reply 09 = the last band index = byte 6 of the last reply.
        val port = TinyPort(staleEchoesReply = true)
        val session = NativeSession(tiny, port)
        session.readSettings()
        port.sent.clear()
        val state = session.read() as NativeState.Fiio
        // opening = the last setting's value (0 or 1, like a stock preset), closing = 9: "USER slot, number unknown" as after a cold plug
        assertEquals(9, state.slot)
        assertEquals(10, state.raw.count)
        assertTrue(port.sent.none { it == 0x21 || it == 0x02 || it == 0x07 || it == 0x12 || it == 0x0C || it == 0x20 || it == 0x09 || it == 0x0B })
    }
}
