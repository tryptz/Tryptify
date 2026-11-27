package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The DAC probe (round 2): the allow-list, the report lines, the FiiO layer on a fake port, and the USB Audio Class descriptor parser. Nothing here can write. */
class DacProbeTest {
    private fun u(b: Byte) = b.toInt() and 255
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `the FiiO command list is the amended one and holds none of the forbidden commands`() {
        assertEquals(
            listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x1D, 0x1E, 0x20, 0x22, 0x32, 0x33, 0x34),
            ProbePlan.FIIO_COMMANDS,
        )
        assertEquals(listOf(0x00, 0x19, 0x1A, 0x1B, 0x1C, 0x21, 0x30, 0x4F), ProbePlan.FIIO_FORBIDDEN.sorted())
        assertTrue(ProbePlan.FIIO_COMMANDS.none { it in ProbePlan.FIIO_FORBIDDEN })
        assertEquals(ProbePlan.FIIO_COMMANDS.size + 2, ProbePlan.FIIO_SEQUENCE.size)
        assertEquals(0x16, ProbePlan.FIIO_SEQUENCE.first()); assertEquals(0x16, ProbePlan.FIIO_SEQUENCE.last())
        val codec = FiioCodec(requireNotNull(DeviceTarget.TINY_A.fiio))
        for (bad in ProbePlan.FIIO_FORBIDDEN + 0x15 + 0x17 + 0x18 + 0x40) assertFailsWith<IllegalArgumentException>("0x%02x".format(bad)) { ProbePlan.fiioFrame(codec, bad) }
        for (cmd in ProbePlan.FIIO_SEQUENCE) {
            val f = ProbePlan.fiioFrame(codec, cmd)
            assertEquals(FiioEffect.READ_ONLY, f.effect)
            assertEquals(listOf(0xBB, 0x0B), f.bytes.take(2))
            assertEquals(cmd, f.bytes[4]); assertEquals(0, f.bytes[5]); assertEquals(0xEE, f.bytes.last())
        }
    }

    @Test fun `the WalkPlay reads are 4B 80 cmd 00 and only the listed commands`() {
        assertEquals(listOf(0x11, 0x16, 0x19, 0x1B, 0x1D, 0x02), ProbePlan.WALKPLAY_COMMANDS)
        for (cmd in ProbePlan.WALKPLAY_COMMANDS) {
            val r = ProbePlan.walkPlayRead(cmd)
            assertEquals(64, r.size)
            assertEquals(listOf(0x4B, 0x80, cmd, 0x00), r.take(4).map(::u))
            assertTrue(r.drop(4).all { it == 0.toByte() })
        }
        assertFailsWith<IllegalArgumentException> { ProbePlan.walkPlayRead(0x03) }
        assertFailsWith<IllegalArgumentException> { ProbePlan.walkPlayRead(0x01) } // a write opcode is never built
    }

    @Test fun `the pause between GETs is FiiO's own per model and the K13 R2R has no FiiO frame layer`() {
        assertEquals(50, ProbePlan.fiioGapMs(requireNotNull(DeviceTarget.TINY_A.fiio)))
        assertEquals(100, ProbePlan.fiioGapMs(requireNotNull(DeviceTarget.MELODY.fiio)))
        assertEquals(200, ProbePlan.fiioGapMs(requireNotNull(DeviceTarget.KA15.fiio)))
        assertEquals(null, ProbePlan.fiioGapMs(requireNotNull(DeviceTarget.K13.fiio)))
    }

    @Test fun `the report writes TX then RX, no reply or err lines and ends with done answered over sent`() {
        val lines = ArrayList<String>()
        var now = 1000L
        val r = ProbeReport("tinya r2/x", "SNOWSKY TINY A", lines::add) { now }
        assertEquals("tinya_r2_x", r.label)
        r.tx("fiio", "0x02", "bb 0b"); r.rx("fiio", "0x02", "bb 0b 00")
        r.tx("fiio", "0x03", "bb 0c"); r.noReply("fiio", "0x03")
        r.tx("uac", "FU10-ch1-vol-cur", "a1 81"); r.error("uac", "FU10-ch1-vol-cur", "controlTransfer returned -1")
        now = 1450
        r.done()
        assertEquals(
            listOf(
                "PROBE tinya_r2_x SNOWSKY-TINY-A fiio 0x02 TX bb 0b",
                "PROBE tinya_r2_x SNOWSKY-TINY-A fiio 0x02 RX bb 0b 00",
                "PROBE tinya_r2_x SNOWSKY-TINY-A fiio 0x03 TX bb 0c",
                "PROBE tinya_r2_x SNOWSKY-TINY-A fiio 0x03 no reply",
                "PROBE tinya_r2_x SNOWSKY-TINY-A uac FU10-ch1-vol-cur TX a1 81",
                "PROBE tinya_r2_x SNOWSKY-TINY-A uac FU10-ch1-vol-cur err controlTransfer returned -1",
                "PROBE tinya_r2_x summary fiio 1/2",
                "PROBE tinya_r2_x summary uac 0/1",
                "PROBE tinya_r2_x done 1/3 450",
            ),
            lines,
        )
    }

    /** A Tiny A that answers a few commands, stays silent on the rest, and shows byte 6 of the last reply in its (stale) slot register. Any write fails the test. */
    private class FakePort(val answers: Map<Int, List<Int>>) : NativePort {
        override val deviceKey = "fake"
        val sent = ArrayList<ByteArray>()
        val pauses = ArrayList<Long>()
        private var stale = 7
        override fun guard() {}
        override fun pause(ms: Long) { pauses += ms }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a probe must not write")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            sent += payload
            val cmd = payload[4].toInt() and 255
            val data = if (cmd == 0x16) listOf(stale) else answers[cmd] ?: throw java.io.IOException("Native request timed out or returned invalid state; Tiny A active slot unknown")
            val r = (listOf(0xBB, 0x0B, 0, 0, cmd, data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray().copyOf(63)
            require(matches(r))
            stale = data.first()
            return r
        }
    }

    @Test fun `probeFiio sends the slot, the 24 GETs and the slot again, paced, reads only, and logs a silent command as no reply`() {
        val port = FakePort(mapOf(0x02 to listOf(60), 0x07 to listOf(0, 0), 0x0C to listOf(0), 0x12 to listOf(0), 0x06 to listOf(1), 0x20 to listOf(1)))
        val lines = ArrayList<String>()
        val report = ProbeReport("t", "SNOWSKY TINY A", lines::add)
        NativeSession(DeviceTarget.TINY_A, port).probeFiio(report, 50)
        assertEquals(ProbePlan.FIIO_SEQUENCE, port.sent.map { it[4].toInt() and 255 })
        assertEquals(26, port.sent.size)
        assertTrue(port.sent.all { (it[0].toInt() and 255) == 0xBB && (it[1].toInt() and 255) == 0x0B && it[5] == 0.toByte() })
        assertEquals(List(25) { 50L }, port.pauses)
        assertEquals(26, report.sent); assertEquals(8, report.answered) // slot x2 + six answers
        assertTrue(lines.any { it.startsWith("PROBE t SNOWSKY-TINY-A fiio 0x0c RX bb 0b 00 00 0c 01 00 00 ee") })
        assertEquals(18, lines.count { it.endsWith("no reply") })
        assertTrue(lines.any { it == "PROBE t SNOWSKY-TINY-A fiio slot before 7" })
        assertTrue(lines.any { it.startsWith("PROBE t SNOWSKY-TINY-A fiio slot after 1") }) // the stale register shows the last reply: noted, not an error
    }

    // ---- USB Audio Class descriptors ---------------------------------------------------------------------------------

    private val config = bytes(0x09, 0x02, 0, 0, 2, 1, 0, 0x80, 0x32)

    /** The Protocol Micro's shape: UAC 2.0, IT 0x0E (USB streaming, 2 ch) -> FU 0x10 (volume on channels 1 and 2) -> mixer 0x24 -> OT 0x0F (speaker); a mic path next to it. */
    private val uac2 = config + bytes(0x09, 0x04, 0, 0, 0, 1, 1, 0x20, 0) +
        bytes(0x09, 0x24, 0x01, 0x00, 0x02, 0x08, 0x3C, 0, 0) +
        bytes(0x08, 0x24, 0x0A, 0x29, 0x01, 0x07, 0, 0) +
        bytes(0x11, 0x24, 0x02, 0x0E, 0x01, 0x01, 0, 0x29, 0x02, 0x03, 0, 0, 0, 0, 0, 0, 0) +
        bytes(0x12, 0x24, 0x06, 0x10, 0x0E, 0, 0, 0, 0, 0x0C, 0, 0, 0, 0x0C, 0, 0, 0, 0) +
        bytes(0x0C, 0x24, 0x04, 0x24, 0x01, 0x10, 0x02, 0x03, 0, 0, 0, 0) +
        bytes(0x0C, 0x24, 0x03, 0x0F, 0x01, 0x03, 0, 0x24, 0x29, 0, 0, 0) +
        // capture: IT 0x20 (microphone 0x0201) -> FU 0x21 (mute on the master) -> OT 0x22 (USB streaming)
        bytes(0x11, 0x24, 0x02, 0x20, 0x01, 0x02, 0, 0x29, 0x01, 0x04, 0, 0, 0, 0, 0, 0, 0) +
        bytes(0x0E, 0x24, 0x06, 0x21, 0x20, 0x03, 0, 0, 0, 0, 0, 0, 0, 0) +
        bytes(0x0C, 0x24, 0x03, 0x22, 0x01, 0x01, 0, 0x21, 0x29, 0, 0, 0)

    @Test fun `UAC 2_0 descriptors give the interface, bcdADC, the units and the playback path`() {
        val ac = assertNotNull(UacDescriptor.parse(uac2))
        assertEquals(0, ac.interfaceNumber)
        assertTrue(ac.uac2); assertEquals("UAC 2.0", ac.version); assertEquals(0x0200, ac.bcdAdc)
        val fu = ac.featureUnits.single { it.id == 0x10 }
        assertEquals(listOf(1, 2), fu.channels.map { it.index }) // the master has no control
        assertTrue(fu.channels.all { it.volume == 3 && !it.hasMute })
        assertEquals(listOf(0x0E), ac.units.filter { it.kind == UacDescriptor.Kind.INPUT && it.terminalType == UacDescriptor.USB_STREAMING }.map { it.id })
        assertEquals(listOf(listOf(0x0E, 0x10, 0x24, 0x0F)), ac.playbackPaths())
        assertEquals(listOf(0x10), ac.playbackFeatureUnits().map { it.id })
        assertTrue(ac.featureUnits.single { it.id == 0x21 }.channels.single().hasMute)
    }

    @Test fun `UAC 1_0 descriptors read mute and volume bits per channel`() {
        val uac1 = config + bytes(0x09, 0x04, 0, 0, 0, 1, 1, 0, 0) +
            bytes(0x09, 0x24, 0x01, 0x00, 0x01, 0x1E, 0, 1, 1) +
            bytes(0x0C, 0x24, 0x02, 0x01, 0x01, 0x01, 0, 0x02, 0x03, 0, 0, 0) +
            bytes(0x0A, 0x24, 0x06, 0x02, 0x01, 0x01, 0x01, 0x03, 0x03, 0) +
            bytes(0x09, 0x24, 0x03, 0x03, 0x01, 0x03, 0, 0x02, 0)
        val ac = assertNotNull(UacDescriptor.parse(uac1))
        assertTrue(!ac.uac2); assertEquals("UAC 1.0", ac.version); assertEquals(0x0100, ac.bcdAdc)
        val fu = ac.featureUnits.single()
        assertEquals(listOf(0, 1, 2), fu.channels.map { it.index })
        assertEquals(listOf(true, true, true), fu.channels.map { it.hasMute })
        assertEquals(listOf(false, true, true), fu.channels.map { it.hasVolume })
        assertEquals(listOf(listOf(1, 2, 3)), ac.playbackPaths())
    }

    @Test fun `no AudioControl interface is null and a second configuration is ignored`() {
        assertEquals(null, UacDescriptor.parse(config + bytes(0x09, 0x04, 0, 0, 0, 3, 0, 0, 0)))
        val twice = uac2 + uac2
        assertEquals(1, assertNotNull(UacDescriptor.parse(twice)).featureUnits.count { it.id == 0x10 })
    }

    @Test fun `volume values are signed 1 over 256 dB and 0x8000 is silence`() {
        assertEquals("-1.00 dB (raw -256)", UacDescriptor.volumeText(bytes(0x00, 0xFF), 2))
        assertEquals("silence (raw -32768)", UacDescriptor.volumeText(bytes(0x00, 0x80), 2))
        assertEquals("short reply", UacDescriptor.volumeText(bytes(0), 1))
        assertEquals("1 sub-range(s): -24.00 dB (raw -6144) .. 0.00 dB (raw 0) step 1.00 dB (raw 256)", UacDescriptor.volumeRangeText(bytes(0x01, 0x00, 0x00, 0xE8, 0x00, 0x00, 0x00, 0x01), 8))
    }
}
