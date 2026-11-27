package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The TRN Black Pearl write against the frames its unit accepted and kept: the TX lines of the PC write of 19.10.2026
 * (write matrix 10:49 and factory restore 10:52, FreeDSP-Studio `write_bank` sequence, slot byte 0). The hex below is
 * copied from those lines (bytes 0..36 of each band frame; the rest of the 64-byte report is zero).
 * LOW PASS and HIGH PASS (matrix bands 6 and 7) are left out: they are not part of the stable build.
 */
class TrnWriteTest {
    private val trn = DeviceProtocol.TRN

    private fun report(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray().copyOf(WalkPlay.REPORT_SIZE)

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    private fun w(index: Int, freq: Double, gain: Double, q: Double, code: Int) = WalkPlay.BandWrite(index, freq, gain, q, code)

    /** Matrix band, its values as the PC tool wrote them, and the frame the unit took. */
    private class Case(val write: WalkPlay.BandWrite, val tx: String)

    private val matrix = listOf(
        Case(w(0, 105.0, 6.0, 0.707, WalkPlay.TYPE_LSQ),
            "4b 01 09 21 00 00 00 00 12 21 21 40 41 c5 a0 80 1e 30 3f 3f 31 80 5f 7f 42 f4 9f c0 69 00 b5 00 00 06 01 00 00"),
        Case(w(1, 250.0, 10.0, 1.0, WalkPlay.TYPE_PK),
            "4b 01 09 21 00 01 00 00 78 3b a2 40 54 6c 9a 80 fa b5 c7 3e ac 93 65 7f 8e 0e 96 c0 fa 00 00 01 00 0a 02 00 00"),
        // -10.4 dB, Q 0.1: past the +-10 dB the editor offers, stored as sent (the TRN's own tool stores -10.4 too)
        Case(w(2, 500.0, -10.4, 0.1, WalkPlay.TYPE_PK),
            "4b 01 09 21 00 02 00 00 5c 86 c0 35 a5 96 6a 9d 08 68 e2 2c 5b 69 95 62 9c 11 5d dd f4 01 1a 00 9a f5 02 00 00"),
        Case(w(3, 1000.0, -10.0, 10.0, WalkPlay.TYPE_PK),
            "4b 01 09 21 00 03 00 00 95 3a bf 3f a4 34 03 81 97 51 83 3f 5c cb fc 7e d4 73 bd c0 e8 03 00 0a 00 f6 02 00 00"),
        Case(w(4, 8000.0, 4.0, 0.707, WalkPlay.TYPE_HSQ),
            "4b 01 09 21 00 04 00 00 ae 57 b0 5c 6f 7a 4a 88 93 05 80 29 88 06 dc 48 c8 21 a9 e8 40 1f b5 00 00 04 03 00 00"),
        Case(w(7, 3000.0, 2.3, 2.0, WalkPlay.TYPE_PK),
            "4b 01 09 21 00 07 00 00 33 85 cb 40 85 75 9a 87 af d9 f5 39 7b 8a 65 78 1e a1 3e c5 b8 0b 00 02 4d 02 02 00 00"),
        Case(w(8, 6000.0, -3.7, 4.0, WalkPlay.TYPE_PK),
            "4b 01 09 21 00 08 00 00 ea 69 c2 3e 92 15 5a 90 ce 6d 16 3a 6e ea a5 6f 47 28 27 c7 70 17 00 04 4d fc 02 00 00"),
        Case(w(9, 12000.0, 0.0, 0.75, WalkPlay.TYPE_PK),
            "4b 01 09 21 00 09 00 00 00 00 00 40 5f d1 7c c2 ea e0 fd 16 a1 2e 83 3d 16 1f 02 e9 e0 2e c0 00 00 00 02 00 00"),
    )

    // The commit, the preamp (-12 dB) and the two flash frames of that write.
    private val commit = "4b 01 0a 04 00 00 ff ff"
    private val preampMinus12 = "4b 01 03 02 00 f4"
    private val eqFlash = "4b 01 01 00"
    private val offsetFlash = "4b 01 04 00"

    /** The factory restore: PK, 0 dB, register Q 182/256, on the unit's ten factory frequencies. */
    private val factoryTx = listOf(
        "4b 01 09 21 00 00 00 00 00 00 00 40 4e c2 2e 80 f0 4e d1 3f b2 3d d1 7f 10 b1 2e c0 1f 00 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 01 00 00 00 00 00 40 ef 84 5d 80 f0 bf a2 3f 11 7b a2 7f 10 40 5d c0 3e 00 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 02 00 00 00 00 00 40 1d 8d bc 80 04 8a 44 3f e3 72 43 7f fc 75 bb c0 7d 00 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 03 00 00 00 00 00 40 fe 1c 79 81 27 39 8b 3e 02 e3 86 7e d9 c6 74 c1 fa 00 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 04 00 00 00 00 00 40 7a 38 f2 82 dc ed 1e 3d 86 c7 0d 7d 24 12 e1 c2 f4 01 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 05 00 00 00 00 00 40 49 0c e4 85 8c 06 5f 3a b7 f3 1b 7a 74 f9 a0 c5 e8 03 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 06 00 00 00 00 00 40 69 e7 c3 8b 38 dc 3c 35 97 18 3c 74 c8 23 c3 ca d0 07 b6 00 00 00 02 00 00",
        "4b 01 09 21 00 07 00 00 00 00 00 40 29 b8 66 97 7d e1 49 2c d7 47 99 68 83 1e b6 d3 a0 0f b6 00 00 00 02 00 00",
        "4b 01 09 21 00 08 00 00 00 00 00 40 03 f4 fc ad eb fd b2 1e fd 0b 03 52 15 02 4d e1 40 1f b6 00 00 00 02 00 00",
        "4b 01 09 21 00 09 00 00 00 00 00 40 14 bd 39 d8 d8 85 8c 0f ec 42 c6 27 28 7a 73 f0 80 3e b6 00 00 00 02 00 00",
    )

    @Test
    fun `band frames are byte for byte the ones the unit accepted (length byte 0x21)`() {
        for (c in matrix) {
            val frame = WalkPlay.bandWriteReport(c.write, slot = 0, lenByte = WalkPlay.TRN_BAND_LEN)
            assertEquals(hex(report(c.tx)), hex(frame), "band ${c.write.index + 1}")
            assertEquals(WalkPlay.REPORT_SIZE, frame.size)
        }
    }

    @Test
    fun `the TRN sequence is band frames, commit, preamp, EQ flash and preamp flash with the proven pacing`() {
        val bands = matrix.map { it.write }
        val steps = trn.writeSequence(DevicePlan.Ready(bands, -12), slot = 0)
        assertEquals(bands.size + 4, steps.size)
        bands.indices.forEach { i ->
            assertEquals(hex(report(matrix[i].tx)), hex(steps[i].report), "band frame $i")
            assertEquals(25L, steps[i].delayAfterMs)
        }
        val tail = steps.drop(bands.size)
        assertEquals(listOf(commit, preampMinus12, eqFlash, offsetFlash).map { hex(report(it)) }, tail.map { hex(it.report) })
        assertEquals(listOf(50L, 50L, 200L, 200L), tail.map { it.delayAfterMs })
    }

    /** TRN shelves: register Q = intended Q x sqrt2, the coefficients, the Q30 check and the editor's Q range stay in the intended domain, a read-back divides by the scale. */
    @Test
    fun `a TRN shelf stores Q times sqrt2 in the register, designs its bytes for the intended Q and reads back as the intended Q`() {
        val q = 0.71
        for (type in listOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF)) {
            // a high shelf boost is refused on the TRN (no exact emulation, see below), so the high shelf is a cut
            val band = Band("s", type, if (type == FilterType.LOW_SHELF) 100.0 else 3000.0, if (type == FilterType.LOW_SHELF) 2.0 else -2.0, q)
            val plan = trn.plan(listOf(band), -3.0) as DevicePlan.Ready
            val w = plan.bands.first()
            assertEquals(Math.round(q * Math.sqrt(2.0) * 256).toInt(), w.registers().q256, "register Q of ${type.name}")
            assertEquals(257, w.registers().q256)
            val code = if (type == FilterType.LOW_SHELF) WalkPlay.TYPE_LSQ else WalkPlay.TYPE_HSQ
            assertEquals(hex(WalkPlay.computeIir(band.freqHz, band.gainDb, q, code)), hex(WalkPlay.bandWriteReport(w, 0, WalkPlay.TRN_BAND_LEN).copyOfRange(8, 28)), "coefficients of Q 0.71")
            // read back: the registers as a frame holds them, divided by the scale; the plan re-encodes to the same registers
            val back = WalkPlay.parseBand(WalkPlay.bandWriteReport(w, 0, WalkPlay.TRN_BAND_LEN))
            assertEquals(257, back.registers.q256)
            val bands = List(10) { i -> if (i == 0) back else WalkPlay.parseBand(WalkPlay.bandWriteReport(trn.flatPlan().bands[i], 0, WalkPlay.TRN_BAND_LEN)) }
            val imported = trn.importExact(bands, -3) as DacImport.Ready
            assertEquals(q, imported.eq.bands.single().q, 0.003)
            assertEquals(type, imported.eq.bands.single().type)
        }
        // a peak is not scaled
        val pk = trn.plan(listOf(Band("p", FilterType.PEAK, 1000.0, 2.0, 2.0)), -3.0) as DevicePlan.Ready
        assertEquals(512, pk.bands.first().registers().q256)
        assertEquals(1.0, trn.caps.peakQScale)
        // the editor's intended shelf Q range is the register range / sqrt2; a peak keeps 0.1..10
        assertEquals(Math.sqrt(2.0), trn.caps.shelfQScale)
        assertEquals(0.1 / Math.sqrt(2.0)..10.0 / Math.sqrt(2.0), DeviceTarget.TRN.qRange(FilterType.HIGH_SHELF))
        assertEquals(0.1..10.0, DeviceTarget.TRN.qRange(FilterType.PEAK))
        assertTrue(trn.plan(listOf(Band("s", FilterType.LOW_SHELF, 100.0, 1.0, 7.07)), -3.0) is DevicePlan.Ready)
        assertTrue(trn.plan(listOf(Band("s", FilterType.LOW_SHELF, 100.0, 1.0, 7.1)), -3.0) is DevicePlan.Rejected)
        assertTrue(trn.plan(listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 9.0)), -3.0) is DevicePlan.Ready)
    }

    @Test
    fun `the slot byte is the one that was read, nothing else in the frame changes`() {
        val c = matrix[3]
        val at0 = WalkPlay.bandWriteReport(c.write, 0, WalkPlay.TRN_BAND_LEN)
        val at101 = WalkPlay.bandWriteReport(c.write, 101, WalkPlay.TRN_BAND_LEN)
        assertEquals(101, at101[36].toInt())
        assertEquals(hex(at0.copyOf().also { it[36] = 101 }), hex(at101))
        assertTrue(trn.writeSequence(DevicePlan.Ready(listOf(c.write), -3), 101).first().report[36].toInt() == 101)
    }

    @Test
    fun `unused slots are the unit's factory flat, frame for frame`() {
        val flat = trn.flatPlan()
        assertEquals(0, flat.preampDb)
        assertEquals(listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000), flat.bands.map { it.registers().freq })
        assertTrue(flat.bands.all { it.registers().let { r -> r.q256 == 182 && r.gain256 == 0 && r.typeCode == WalkPlay.TYPE_PK } })
        flat.bands.forEachIndexed { i, b ->
            assertEquals(hex(report(factoryTx[i])), hex(WalkPlay.bandWriteReport(b, 0, WalkPlay.TRN_BAND_LEN)), "factory slot $i")
        }
        // a short profile is padded with exactly those slots
        val one = trn.plan(listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)), -4.0) as DevicePlan.Ready
        assertEquals(10, one.bands.size)
        assertEquals(flat.bands.drop(1).map { it.registers() }, one.bands.drop(1).map { it.registers() })
        assertEquals(1000, one.bands[0].registers().freq)
        // the Max's own factory flat, read on a unit (2026-11-10), is the same: PK, 0 dB, register Q 182/256 on 31 - 16000 Hz
        assertEquals(DeviceProtocol.MAX.flatPlan().bands.map { it.registers() }, flat.bands.map { it.registers() })
        // the factory slot is also what a disabled band leaves behind
        val withDisabled = trn.plan(listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0), Band("b", FilterType.PEAK, 2000.0, 3.0, 1.0, enabled = false)), -4.0) as DevicePlan.Ready
        assertEquals(flat.bands[1].registers(), withDisabled.bands[1].registers())
    }

    @Test
    fun `the planner maps native shelves and peaks to codes 1 2 3, keeps the stable caps and refuses pass filters`() {
        val profile = listOf(
            Band("ls", FilterType.LOW_SHELF, 105.0, 6.0, 0.707),
            Band("pk", FilterType.PEAK, 250.0, 10.0, 1.0),
            Band("hs", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.707),
        )
        val plan = trn.plan(profile, -12.0) as DevicePlan.Ready
        assertEquals(listOf(WalkPlay.TYPE_LSQ, WalkPlay.TYPE_PK, WalkPlay.TYPE_HSQ), plan.bands.take(3).map { it.typeCode })
        assertEquals(-12, plan.preampDb)
        // bands 1 and 2 are the matrix's LSQ and PK +10 bands at the same indices: the planner's frames are the ones the unit took, except the shelf's Q register: the TRN plays a shelf at
        // Q = register / sqrt2 (acoustic run 25.10.2026), so the register carries intended Q x sqrt2 (0.707 x sqrt2 x 256 = 256) while the coefficient bytes stay those of Q 0.707
        val steps = trn.writeSequence(plan, 0)
        assertEquals(hex(report(matrix[0].tx).also { it[30] = 0x00; it[31] = 0x01 }), hex(steps[0].report))
        assertEquals(hex(report(matrix[1].tx)), hex(steps[1].report))
        assertEquals(10, trn.caps.bands)
        assertEquals(0x43E8, trn.caps.productId)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), trn.caps.types)
        // beyond the editor caps, pass filters and a preamp above 0 stay refused
        assertTrue(trn.plan(listOf(Band("x", FilterType.PEAK, 500.0, -10.4, 0.1)), -4.0) is DevicePlan.Rejected)
        assertTrue(trn.plan(listOf(Band("x", FilterType.LOW_PASS, 18000.0, 0.0, 0.707)), -4.0) is DevicePlan.Rejected)
        assertTrue(trn.plan(listOf(Band("x", FilterType.HIGH_PASS, 30.0, 0.0, 0.707)), -4.0) is DevicePlan.Rejected)
        assertTrue(trn.plan(profile, 1.0) is DevicePlan.Rejected)
        assertTrue(trn.plan(profile + List(8) { Band("n$it", FilterType.PEAK, 1000.0, 1.0, 1.0) }, -4.0) is DevicePlan.Rejected) // 11 bands
        assertTrue(trn.supportsAb.not() && trn.experimental)
    }

    @Test
    fun `a TRN high shelf boost at a low corner is refused before anything is sent - there is no exact emulation, the preamp stops at 0 dB - and a higher corner or a cut is sent`() {
        val trn = DeviceProtocol.TRN
        assertEquals(0, trn.preampMax)                                       // a boost cannot be moved into a positive overall gain, as the Micro's emulation does
        fun hs(freq: Double, gain: Double, q: Double = 0.707) = trn.plan(listOf(Band("hs", FilterType.HIGH_SHELF, freq, gain, q)), -4.0)
        val refused = hs(1000.0, 3.0) as DevicePlan.Rejected
        assertEquals(listOf(SendBlocked.band(trn.caps.name, 1, FilterType.HIGH_SHELF)), refused.issues)
        assertTrue(refused.issues.all { SendBlocked.isBandRefusal(it) })
        // the number is the editor's row: a disabled band before it counts
        val second = trn.plan(listOf(Band("off", FilterType.PEAK, 500.0, 1.0, 1.0, enabled = false), Band("hs", FilterType.HIGH_SHELF, 1000.0, 3.0, 0.707)), -4.0) as DevicePlan.Rejected
        assertTrue(second.issues.single().contains("band 2 "), second.issues.toString())
        assertTrue(hs(10000.0, 3.0) is DevicePlan.Ready)                     // the same shelf at a high corner is native
        assertTrue(hs(1000.0, -3.0) is DevicePlan.Ready)                     // a cut at the low corner is native too
    }

    @Test
    fun `Micro and Max keep length byte 0x18 and the devicePEQ sequence`() {
        val band = w(0, 1000.0, 3.0, 1.0, WalkPlay.TYPE_PK)
        assertEquals(0x18, WalkPlay.bandWriteReport(band, 0)[3].toInt())
        assertEquals(0x21, WalkPlay.bandWriteReport(band, 0, WalkPlay.TRN_BAND_LEN)[3].toInt())
        for (p in listOf(DeviceProtocol.MICRO, DeviceProtocol.MAX)) {
            val plan = p.plan(listOf(Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)), -4.0) as DevicePlan.Ready
            val old = WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), 101, commit = true)
            val now = p.writeSequence(plan, 101)
            assertEquals(old.map { hex(it.report) }, now.map { hex(it.report) }, p.name)
            assertEquals(old.map { it.delayAfterMs }, now.map { it.delayAfterMs }, p.name)
            assertTrue(now.filter { it.report[2].toInt() == WalkPlay.CMD_PEQ }.all { it.report[3].toInt() == 0x18 })
        }
        assertEquals(DeviceTarget.TRN, DeviceTarget.of(DeviceProtocol.TRN))
        assertEquals(DeviceTarget.MAX, DeviceTarget.of(DeviceProtocol.MAX))
        assertEquals(DeviceTarget.MICRO, DeviceTarget.of(DeviceProtocol.MICRO))
    }
}
