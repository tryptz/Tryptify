package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.FiioCatalog
import io.github.chronosauros.contour.core.native.FiioCodec
import io.github.chronosauros.contour.core.native.FiioRegisters
import io.github.chronosauros.contour.core.native.FiioSnapshot
import io.github.chronosauros.contour.core.native.MoondropCodec
import io.github.chronosauros.contour.core.native.WalkPlayProfiles
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Q conventions and the coefficient rate measured on the UMC202HD rig (24-25.10.2026), applied at the register boundary like the KA15's shelf Q (04.10.2026):
 * the FiiO K13 R2R plays a peak at Q = register / sqrt2, the SnowSky Melody and Tiny A play shelves like that, the TRN does the same for shelves, and the Protocol Micro and
 * the Echo-A run the coefficients Contour sends at 93 750 Hz instead of the 96 000 Hz the vendor tool designs them for. The registers carry the DAC's convention; the editor,
 * the planners and every comparison stay in the intended domain. The TRN's own register and byte checks are in TrnWriteTest, the Micro's vendor-formula parity in ProtocolParityTest.
 */
class QConventionsTest {
    private val root2 = sqrt(2.0)
    private fun codec(name: String) = FiioCodec(FiioCatalog.rules.single { it.productName == name })
    private fun plan(c: FiioCodec, bands: List<Band>, preamp: Double? = 0.0) = c.planOnExplicitSend(bands, preamp, c.config.userSlots.first(), explicitUserAction = true)
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    private fun profile(bands: List<Band>, preamp: Double? = 0.0) = Profile("p", "p", bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)

    // ---- FiiO K13 R2R: peaks ---------------------------------------------------------------------------------------------------------------------------

    @Test fun `K13 R2R writes a peak Q 2 as register 2_83, reads it back as 2_00 and leaves shelves and pass filters alone`() {
        val c = codec("FIIO K13 R2R")
        val pk = plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 2.0))).expected
        assertEquals(283, pk.bands.single().qHundredths)
        val read = c.importExact(FiioSnapshot("FIIO K13 R2R", c.config.userSlots.first(), 1, 0, listOf(FiioRegisters(0, 1000, 30, 283, 0))))
        assertEquals(2.0, read.bands.single().q, 0.002)
        assertEquals(pk.bands, plan(c, read.bands, read.preampDb).expected.bands) // the intended value re-encodes to the same registers
        // shelves and pass filters play the register as Q on the K13 R2R: unchanged
        val others = plan(c, listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 3.0, 2.0), Band("hs", FilterType.HIGH_SHELF, 8000.0, -3.0, 0.71),
            Band("lp", FilterType.LOW_PASS, 8000.0, 0.0, 0.71), Band("hp", FilterType.HIGH_PASS, 100.0, 0.0, 0.71),
            Band("bp", FilterType.BAND_PASS, 1000.0, 0.0, 1.4), Band("ap", FilterType.ALL_PASS, 1000.0, 0.0, 0.71))).expected
        assertEquals(listOf(200, 71, 71, 71, 140, 71), others.bands.map { it.qHundredths })
        // every peak register comes back exactly through the intended domain
        for (q in 10..1000) {
            val native = FiioSnapshot("FIIO K13 R2R", c.config.userSlots.first(), 1, 0, listOf(FiioRegisters(0, 1000, 25, q, 0)))
            assertEquals(native, plan(c, c.importExact(native).bands, 0.0).expected, "register Q $q")
        }
    }

    @Test fun `K13 R2R takes a peak Q up to 7_07 and a shelf Q up to 10, and says which`() {
        val c = codec("FIIO K13 R2R")
        assertEquals(1000, plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 7.07))).expected.bands.single().qHundredths)
        assertTrue(assertFailsWith<IllegalArgumentException> { plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 7.08))) }.message!!.contains("0.07..7.07"))
        assertEquals(1000, plan(c, listOf(Band("s", FilterType.LOW_SHELF, 100.0, 3.0, 10.0))).expected.bands.single().qHundredths)
        val t = DeviceTarget.K13
        assertEquals(0.1 / root2..10.0 / root2, t.qRange(FilterType.PEAK))
        assertEquals(0.1..10.0, t.qRange(FilterType.LOW_SHELF))
        assertEquals(0.1..10.0, t.qRange(FilterType.LOW_PASS))
        assertEquals(emptyList(), t.issues(profile(listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 7.07)))))
        assertTrue(t.issues(profile(listOf(Band("p", FilterType.PEAK, 1000.0, 3.0, 8.0)))).single().startsWith("Band 1: peak Q 8.00 outside 0.07-7.07"))
        assertEquals(emptyList(), t.issues(profile(listOf(Band("s", FilterType.HIGH_SHELF, 8000.0, 3.0, 8.0)))))
        // the other FiiO devices keep the peak Q as it is
        for (other in listOf(DeviceTarget.KA15, DeviceTarget.MELODY, DeviceTarget.TINY_A))
            assertEquals(1.0, other.caps.peakQScale, other.caps.name)
        assertEquals(root2, t.caps.peakQScale)
        assertEquals(1.0, t.caps.shelfQScale)
        // a peak scale is not combinable with the vendor's peak gain compensation
        assertFailsWith<IllegalArgumentException> { FiioCodec(c.config.copy(peakingGainCompensation = true)) }
        assertFailsWith<IllegalArgumentException> { FiioCodec(c.config.copy(peakQScale = 0.0)) }
    }

    // ---- SnowSky Melody and Tiny A: shelves --------------------------------------------------------------------------------------------------------

    @Test fun `Melody and Tiny A write a shelf Q 0_70 as register 0_99 and read it back, peaks are as set`() {
        for ((name, t) in listOf("SNOWSKY Melody" to DeviceTarget.MELODY, "SNOWSKY TINY A" to DeviceTarget.TINY_A)) {
            val c = codec(name)
            assertEquals(root2, c.config.shelfQScale, name)
            assertEquals(1.0, c.config.peakQScale, name)
            val p = plan(c, listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 5.0, 0.70), Band("hs", FilterType.HIGH_SHELF, 8000.0, -6.0, 1.0),
                Band("pk", FilterType.PEAK, 1000.0, 3.0, 0.70))).expected
            assertEquals(listOf(99, 141, 70), p.bands.map { it.qHundredths }, name)
            val read = c.importExact(FiioSnapshot(c.config.productName, c.config.userSlots.first(), 3, 0, p.bands))
            assertEquals(listOf(0.70, 1.0, 0.70), read.bands.map { it.q }.map { Math.round(it * 100) / 100.0 }, name)
            assertEquals(p.bands, plan(c, read.bands, read.preampDb).expected.bands, name)
            // the editor's intended shelf Q range is the register range / sqrt2, peaks keep 0.1..10
            assertEquals(0.1 / root2..10.0 / root2, t.qRange(FilterType.HIGH_SHELF), name)
            assertEquals(0.1..10.0, t.qRange(FilterType.PEAK), name)
        }
        // the KA15 keeps its own, unchanged: shelves x sqrt2, peaks as set
        val ka = plan(codec("FIIO KA15"), listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 5.0, 0.70), Band("pk", FilterType.PEAK, 1000.0, 3.0, 0.70))).expected
        assertEquals(listOf(99, 70), ka.bands.map { it.qHundredths })
    }

    // ---- SnowSky Melody and Tiny A: the preamp in whole dB ------------------------------------------------------------------------------------------

    @Test fun `Melody and Tiny A keep whole dB of the preamp - the plan rounds down and the read-back verifies - KA15 rounds to whole dB too - K13 keeps 0_1 dB`() {
        // -3.5 dB went out as -35 and read back -30 (NOT VERIFIED) in the 22.10.2026 runs; now it is sent as the whole dB the DAC keeps: down, the quieter side (the WalkPlay family's floor(preamp + 1e-6)).
        // The Tiny A writes the preamp as shown (offset 0); the Melody plays USER presets 12 dB below Close EQ (13.11.2026), so its register = whole-dB preamp + 12 dB, range -24..0 dB.
        val cases = listOf(
            Triple("SNOWSKY Melody", DeviceTarget.MELODY, listOf(-3.5 to 80, -3.0 to 90, -3.4 to 80, -0.1 to 110, 0.0 to 120, -12.0 to 0, -24.0 to -120)),
            Triple("SNOWSKY TINY A", DeviceTarget.TINY_A, listOf(-3.5 to -40, -3.0 to -30, -3.4 to -40, -0.1 to -10, 0.0 to 0, 2.9 to 20, 12.0 to 120, -12.0 to -120)),
        )
        for ((name, t, table) in cases) {
            val c = codec(name)
            assertTrue(c.config.preampWholeDb && t.caps.preampWholeDb, name)
            for ((db, tenths) in table) {
                val plan = plan(c, listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), db)
                assertEquals(tenths, plan.expected.preampTenths, "$name $db")
                // what the DAC keeps is exactly what the plan expects, so the read-back compares equal and imports as the whole dB (the register minus the offset)
                assertEquals(tenths / 10.0 - c.config.preampOffsetDb, c.importExact(plan.expected).preampDb, "$name $db import")
                assertTrue(c.matches(plan.expected, plan.expected), "$name $db")
            }
            assertEquals(emptyList(), t.issues(profile(listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), -3.5)), name)
        }
        assertEquals(12.0, codec("SNOWSKY Melody").config.preampOffsetDb)
        assertEquals(0.0, codec("SNOWSKY TINY A").config.preampOffsetDb)
        // KA15 (register = preamp + 12 dB): a fractional preamp reset the DAC during the send (Pixel 26.10.2026: -4.5, -3.5, -2.5 dB), so -3.5 goes out as -4 (register 80)
        assertTrue(DeviceTarget.KA15.caps.preampWholeDb)
        assertEquals(80, plan(codec("FIIO KA15"), listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), -3.5).expected.preampTenths)
        assertEquals(1.0, DeviceTarget.KA15.preampStep)
        // K13 holds 0.1 dB (no fractional failure seen)
        assertEquals(-35, plan(codec("FIIO K13 R2R"), listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 1.0)), -3.5).expected.preampTenths)
        assertFalse(DeviceTarget.K13.caps.preampWholeDb)
    }

    // ---- WalkPlay: coefficient rate ------------------------------------------------------------------------------------------------------------------

    private fun bytes(w: WalkPlay.BandWrite, slot: Int = 0, len: Int = WalkPlay.BAND_LEN) = WalkPlay.bandWriteReport(w, slot, len).copyOfRange(8, 28)

    @Test fun `Micro and Echo-A design their coefficients for 93750 Hz, the Max, TRN and Dawn Pro 2 keep 96000`() {
        val band = Band("p", FilterType.PEAK, 1000.0, 5.0, 2.0)
        val hs = Band("h", FilterType.HIGH_SHELF, 3000.0, -2.0, 0.71)
        val lp = Band("l", FilterType.LOW_PASS, 8000.0, 0.0, 0.71)
        val micro = DeviceProtocol.MICRO.caps
        val echo = WalkPlayProfiles.echoA.caps
        assertEquals(93750.0, micro.coefficientRateHz)
        assertEquals(93750.0, echo.coefficientRateHz)
        assertEquals(96000.0, DeviceProtocol.MAX.caps.coefficientRateHz)
        assertEquals(96000.0, DeviceProtocol.TRN.caps.coefficientRateHz)
        // Micro (HIGH SHELF emulated as a LOW SHELF of the opposite gain, with the preamp moved) and Echo-A through the Micro's planner
        for ((caps, name) in listOf(micro to "Micro", echo to "Echo-A")) {
            val plan = ProtocolMicro.plan(profile(listOf(band, hs, lp)), caps.copy(types = caps.types + FilterType.LOW_PASS)) as DevicePlan.Ready
            assertTrue(plan.bands.take(3).all { it.rateHz == 93750.0 }, name)
            assertEquals(hex(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK, 93750.0)), hex(bytes(plan.bands[0])), "$name PK")
            assertEquals(hex(WalkPlay.computeIir(3000.0, 2.0, 0.71, WalkPlay.TYPE_LSQ, 93750.0)), hex(bytes(plan.bands[1])), "$name emulated HS")
            assertEquals(hex(WalkPlay.computeIir(8000.0, 0.0, 0.71, WalkPlay.TYPE_LP, 93750.0)), hex(bytes(plan.bands[2])), "$name LP")
            assertNotEquals(hex(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK)), hex(bytes(plan.bands[0])), "$name differs from the 96 kHz bytes")
            // the spare slots stay the factory flat, byte for byte (the identity filter of the unit's own 96 kHz bytes)
            assertEquals(hex(bytes(WalkPlay.factoryFlat(3))), hex(bytes(plan.bands[3])), "$name spare slot")
        }
        // Max and TRN: the coefficients as before (96 000 Hz); the TRN's PK is the register as Q
        for ((p, len) in listOf(DeviceProtocol.MAX to WalkPlay.BAND_LEN, DeviceProtocol.TRN to WalkPlay.TRN_BAND_LEN)) {
            val plan = p.plan(profile(listOf(band, hs))) as DevicePlan.Ready
            assertTrue(plan.bands.all { it.rateHz == 96000.0 }, p.name)
            assertEquals(hex(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK)), hex(bytes(plan.bands[0], len = len)), "${p.name} PK")
            // a shelf's coefficients are designed for the intended Q on the TRN as well as on the Max
            assertEquals(hex(WalkPlay.computeIir(3000.0, -2.0, 0.71, WalkPlay.TYPE_HSQ)), hex(bytes(plan.bands[1], len = len)), "${p.name} HS")
        }
        // Dawn Pro 2: MoondropCodec calls computeIir with its default rate
        val dawn = MoondropCodec.bandWrite(0, band)
        assertContentEquals(WalkPlay.computeIir(1000.0, 5.0, 2.0, WalkPlay.TYPE_PK), dawn.coefficients)
        assertEquals(96000.0, WalkPlay.DESIGN_FS)
    }

    @Test fun `the Q30 check of a band follows the rate it is designed for`() {
        // a shelf boost at a high corner is on the edge of signed Q30: the check asks the write's own rate, as the bytes do
        val w96 = WalkPlay.BandWrite(0, 12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ)
        val w93 = w96.copy(rateHz = 93750.0)
        assertEquals(WalkPlay.q30Violation(12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ, 96000.0), WalkPlay.q30ViolationQuantized(w96))
        assertEquals(WalkPlay.q30Violation(12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ, 93750.0), WalkPlay.q30ViolationQuantized(w93))
        assertEquals(WalkPlay.q30Violation(12000.0, 10.0, 0.71, WalkPlay.TYPE_LSQ), WalkPlay.q30ViolationQuantized(w96))
    }

    @Test fun `a WalkPlay record scale reaches the caps and a read-back divides by it before it compares`() {
        // TRN and Max (the Max plays a shelf at register / sqrt2 too, dac-lab run 2026-11-10-crinear-protocol-max-13): shelves x sqrt2 in the register, peaks as set; Micro: no scale
        val trn = DeviceProtocol.TRN.caps
        assertEquals(root2, trn.shelfQScale); assertEquals(1.0, trn.peakQScale)
        assertEquals(1.0, DeviceProtocol.MICRO.caps.shelfQScale); assertEquals(root2, DeviceProtocol.MAX.caps.shelfQScale); assertEquals(1.0, DeviceProtocol.MAX.caps.peakQScale)
        assertEquals(root2, WalkPlay.bandWrite(0, Band("s", FilterType.LOW_SHELF, 100.0, 3.0, 1.0), trn).qScale)
        assertEquals(1.0, WalkPlay.bandWrite(0, Band("p", FilterType.PEAK, 100.0, 3.0, 1.0), trn).qScale)
        assertEquals(1.0, WalkPlay.bandWrite(0, Band("l", FilterType.LOW_PASS, 100.0, 0.0, 1.0), trn).qScale)
        assertEquals(0.71, WalkPlay.intendedQ(257, FilterType.HIGH_SHELF, trn), 0.003)
        assertEquals(257 / 256.0, WalkPlay.intendedQ(257, FilterType.PEAK, trn))
        // the register Q of a shelf Q 0.71 is round(0.71 x sqrt2 x 256), the bytes are those of Q 0.71
        val w = WalkPlay.bandWrite(0, Band("s", FilterType.HIGH_SHELF, 3000.0, -2.0, 0.71), trn)
        assertEquals(Math.round(0.71 * root2 * 256).toInt(), w.registers().q256)
        assertEquals(hex(WalkPlay.computeIir(3000.0, -2.0, 0.71, WalkPlay.TYPE_HSQ)), hex(bytes(w, len = WalkPlay.TRN_BAND_LEN)))
    }

    // ---- the shelf form (record peq.shelfForm): what the graph draws and what AUTO computes follows the DAC; registers and bytes never do -----------------

    /**
     * The RBJ shelf written out in the test, independent of [Dsp]: magnitude in dB at [evalHz] of a LOW or HIGH shelf at [f0] with [gainDb], designed at [Dsp.FS], in the Q form
     * (alpha = sin(w0) / (2 Q)) or the slope form (alpha = sin(w0) / 2 x sqrt((A + 1/A)(1/S - 1) + 2), S = Q), evaluated on the unit circle with plain complex arithmetic.
     */
    private fun rbjShelfDb(low: Boolean, f0: Double, gainDb: Double, q: Double, qForm: Boolean, evalHz: Double): Double {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2 * Math.PI * f0 / Dsp.FS
        val cs = Math.cos(w0)
        val sn = Math.sin(w0)
        val alpha = if (qForm) sn / (2 * q) else sn / 2 * sqrt((a + 1 / a) * (1 / q - 1) + 2)
        val k = 2 * sqrt(a) * alpha
        val b: DoubleArray
        val d: DoubleArray
        if (low) {
            b = doubleArrayOf(a * ((a + 1) - (a - 1) * cs + k), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - k))
            d = doubleArrayOf((a + 1) + (a - 1) * cs + k, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - k)
        } else {
            b = doubleArrayOf(a * ((a + 1) + (a - 1) * cs + k), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - k))
            d = doubleArrayOf((a + 1) - (a - 1) * cs + k, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - k)
        }
        val w = 2 * Math.PI * evalHz / Dsp.FS
        fun power(c: DoubleArray): Double {
            val re = c[0] + c[1] * Math.cos(w) + c[2] * Math.cos(2 * w)
            val im = -(c[1] * Math.sin(w) + c[2] * Math.sin(2 * w))
            return re * re + im * im
        }
        return 10 * Math.log10(power(b) / power(d))
    }

    /** The peak of a single shelf over a dense log grid, and the whole-dB AUTO preamp that follows from it (-ceil(peak), as [Preamp.auto] rounds). */
    private fun rbjPeakDb(low: Boolean, f0: Double, gainDb: Double, q: Double, qForm: Boolean): Double =
        (0 until 4001).maxOf { rbjShelfDb(low, f0, gainDb, q, qForm, 20.0 * Math.pow(1000.0, it / 4000.0)) }
    private fun rbjAuto(peakDb: Double): Int = if (peakDb > 0) -Math.ceil(peakDb - 1e-6).toInt() else 0

    private val ls100 = Band("ls", FilterType.LOW_SHELF, 100.0, 6.0, 3.0)

    @Test fun `the Q form shelf equals the written-out RBJ biquad, the slope form keeps today's maths, and they meet at Q 0_7071`() {
        val cases = listOf(
            Band("a", FilterType.LOW_SHELF, 100.0, 6.0, 3.0), Band("b", FilterType.HIGH_SHELF, 6000.0, 6.0, 2.0), Band("c", FilterType.LOW_SHELF, 1000.0, -4.0, 0.5),
            Band("d", FilterType.HIGH_SHELF, 12000.0, -9.0, 1.4), Band("e", FilterType.LOW_SHELF, 40.0, 10.0, 5.0),
        )
        // 1e-3 dB: the display maths (expanded in cos w) and the complex evaluation here round differently far below the corner at a 96 kHz design (up to 3e-4 dB at 20 Hz); the forms differ by 4 dB
        val freqs = doubleArrayOf(20.0, 55.0, 100.0, 180.0, 700.0, 1000.0, 2500.0, 6000.0, 9000.0, 15000.0, 20000.0)
        for (b in cases) for (f in freqs) {
            val low = b.type == FilterType.LOW_SHELF
            val grid = Dsp.FreqGrid(doubleArrayOf(f))
            assertEquals(rbjShelfDb(low, b.freqHz, b.gainDb, b.q, qForm = true, evalHz = f), Dsp.bandDb(b, ShelfForm.Q, grid)[0], 1e-3, "Q form ${b.type} ${b.freqHz} Hz Q ${b.q} at $f Hz")
            // the slope form of the cases with a real response (none of these Q values has a negative radicand)
            assertEquals(rbjShelfDb(low, b.freqHz, b.gainDb, b.q, qForm = false, evalHz = f), Dsp.bandDb(b, ShelfForm.SLOPE, grid)[0], 1e-3, "slope form ${b.type} ${b.freqHz} Hz Q ${b.q} at $f Hz")
        }
        // LS 100 Hz +6 dB Q 3: the Q form peaks at +11.09 dB, the slope form at +7.16 dB (the spec's reference numbers, from the formula written out above)
        assertEquals(11.09, rbjPeakDb(true, 100.0, 6.0, 3.0, qForm = true), 0.01)
        assertEquals(7.16, rbjPeakDb(true, 100.0, 6.0, 3.0, qForm = false), 0.01)
        assertEquals(-12, rbjAuto(rbjPeakDb(true, 100.0, 6.0, 3.0, qForm = true)))
        assertEquals(-8, rbjAuto(rbjPeakDb(true, 100.0, 6.0, 3.0, qForm = false)))
        // peaks and pass filters are the same in both forms; the two shelf forms are the same filter at S = 1 <-> Q = 1/sqrt2
        val pk = Band("p", FilterType.PEAK, 1000.0, 5.0, 2.0); val lp = Band("l", FilterType.LOW_PASS, 4000.0, 0.0, 1.3)
        for (b in listOf(pk, lp)) assertContentEquals(Dsp.bandDb(b, ShelfForm.SLOPE), Dsp.bandDb(b, ShelfForm.Q))
        val bq = Dsp.biquad(Band("s", FilterType.LOW_SHELF, 300.0, 6.0, 1 / root2), ShelfForm.Q); val bs = Dsp.biquad(Band("s", FilterType.LOW_SHELF, 300.0, 6.0, 1.0), ShelfForm.SLOPE)
        assertEquals(bs.b0, bq.b0, 1e-12); assertEquals(bs.b1, bq.b1, 1e-12); assertEquals(bs.b2, bq.b2, 1e-12); assertEquals(bs.a1, bq.a1, 1e-12); assertEquals(bs.a2, bq.a2, 1e-12)
        // the slope form refuses a shelf without a real response, the Q form has one for every positive finite Q
        val steep = Band("x", FilterType.LOW_SHELF, 1000.0, 12.0, 10.0)
        assertFailsWith<IllegalArgumentException> { Dsp.responseDb(listOf(steep), ShelfForm.SLOPE) }
        assertTrue(Dsp.responseDb(listOf(steep), ShelfForm.Q).all { it.isFinite() })
    }

    @Test fun `the AUTO answer cache is keyed by the form as well as the bands`() {
        val bands = listOf(ls100)
        repeat(2) {
            assertEquals(-8, Preamp.auto(bands, ShelfForm.SLOPE))
            assertEquals(-12, Preamp.auto(bands, ShelfForm.Q))
        }
        assertEquals(-8.0, Profile("p", "p", bands = bands, createdAt = 0, updatedAt = 0).effectivePreampDb(ShelfForm.SLOPE))
        assertEquals(-12.0, Profile("p", "p", bands = bands, createdAt = 0, updatedAt = 0).effectivePreampDb(ShelfForm.Q))
    }

    @Test fun `every DeviceTarget has the shelf form of its record, and the Micro and the Echo-A the form WalkPlay computes the coefficient bytes in`() {
        val table = mapOf(
            DeviceTarget.KA15 to ShelfForm.Q, DeviceTarget.FIIO_JA11 to ShelfForm.Q, DeviceTarget.K13 to ShelfForm.Q, DeviceTarget.MELODY to ShelfForm.Q, DeviceTarget.TINY_A to ShelfForm.Q,
            DeviceTarget.MAX to ShelfForm.Q, DeviceTarget.TRN to ShelfForm.Q, DeviceTarget.JCALLY_JM12 to ShelfForm.Q, DeviceTarget.DAWN_PRO_2 to ShelfForm.Q,
            DeviceTarget.MICRO to ShelfForm.SLOPE, DeviceTarget.ECHO_A to ShelfForm.SLOPE, DeviceTarget.MOONDROP_DUSK to ShelfForm.SLOPE,
            DeviceTarget.ALLEGRO_MINI to ShelfForm.SLOPE, // its record says "unknown" (no shelf offered): the code uses the slope form
        )
        for ((t, form) in table) { assertEquals(form, t.shelfForm, t.caps.name); assertEquals(form, t.caps.shelfForm, t.caps.name) }
        assertEquals(13, table.size)
        assertEquals(ProtocolMicro.COEFFICIENT_SHELF_FORM, DeviceTarget.MICRO.shelfForm)
        assertEquals(ProtocolMicro.COEFFICIENT_SHELF_FORM, DeviceTarget.ECHO_A.shelfForm)
        assertEquals(ShelfForm.SLOPE, ProtocolMicro.COEFFICIENT_SHELF_FORM) // WalkPlay.scaledCoefficients is the slope form
        // the family forms reach the target from their own catalogs (the Dawn Pro 2 and the JM12 play the registers, the Allegro Mini offers no shelf)
        assertEquals(ShelfForm.Q, io.github.chronosauros.contour.core.native.MoondropCatalog.dawnPro2.shelfForm)
        assertEquals(ShelfForm.Q, io.github.chronosauros.contour.core.native.KtMicroCatalog.jm12Models.first().shelfForm)
        assertEquals(ShelfForm.SLOPE, io.github.chronosauros.contour.core.native.KtMicroCatalog.models.first().shelfForm)
    }

    @Test fun `WalkPlay AUTO follows the DAC - Max minus 12, TRN minus 6 for a cut shelf, Micro stays minus 8 - and the registers are the ones the slope maths always wrote`() {
        val max = DeviceProtocol.MAX.plan(listOf(ls100), null) as DevicePlan.Ready
        assertEquals(-12, max.preampDb)
        assertEquals(-12, DeviceProtocol.MAX.devicePreamp(listOf(ls100), null)); assertEquals(-12.0, DeviceTarget.MAX.shownPreamp(Profile("p", "p", bands = listOf(ls100), createdAt = 0, updatedAt = 0)))
        assertEquals(-12.0, DeviceProtocol.MAX.manualForAuto(listOf(ls100))); assertEquals(null, DeviceProtocol.MAX.autoRefusal(listOf(ls100)))
        val micro = DeviceProtocol.MICRO.plan(listOf(ls100), null) as DevicePlan.Ready
        assertEquals(-8, micro.preampDb)
        // the TRN: a cut shelf the codec accepts - the Q form bump is +5.09 dB (AUTO -6), the slope form +1.16 dB (AUTO -2)
        val cut = Band("cut", FilterType.LOW_SHELF, 100.0, -6.0, 3.0)
        assertEquals(5.09, rbjPeakDb(true, 100.0, -6.0, 3.0, qForm = true), 0.01)
        assertEquals(1.16, rbjPeakDb(true, 100.0, -6.0, 3.0, qForm = false), 0.01)
        assertEquals(-6, rbjAuto(rbjPeakDb(true, 100.0, -6.0, 3.0, qForm = true))); assertEquals(-2, rbjAuto(rbjPeakDb(true, 100.0, -6.0, 3.0, qForm = false)))
        val trn = DeviceProtocol.TRN.plan(listOf(cut), null) as DevicePlan.Ready
        assertEquals(-6, trn.preampDb)
        // nothing that is a band register or a coefficient byte follows the form: the same band writes whatever AUTO says
        val manual = DeviceProtocol.TRN.plan(listOf(cut), -3.0) as DevicePlan.Ready
        assertEquals(trn.bands.map { hex(WalkPlay.bandWriteReport(it, 0, WalkPlay.TRN_BAND_LEN)) }, manual.bands.map { hex(WalkPlay.bandWriteReport(it, 0, WalkPlay.TRN_BAND_LEN)) })
        val maxManual = DeviceProtocol.MAX.plan(listOf(ls100), -3.0) as DevicePlan.Ready
        assertEquals(max.bands.map { it.registers() }, maxManual.bands.map { it.registers() })
    }

    @Test fun `a KT Micro JM12 draws a shelf inside its limits in the Q form and the Echo-A and the DUSK-SP stay on the slope form`() {
        val t = DeviceTarget.JCALLY_JM12
        assertEquals(ShelfForm.Q, t.shelfForm)
        val shelf = Band("s", FilterType.LOW_SHELF, 200.0, 6.0, 1.0)
        assertTrue(shelf.q in t.qRange(FilterType.LOW_SHELF) && shelf.gainDb in t.caps.gainRange(FilterType.LOW_SHELF), "the shelf is inside the JM12's own shelf limits")
        val freqs = doubleArrayOf(20.0, 100.0, 200.0, 400.0, 1000.0, 5000.0, 20000.0)
        val drawn = Dsp.responseDb(listOf(shelf), t.shelfForm, Dsp.FreqGrid(freqs))
        freqs.forEachIndexed { i, f -> assertEquals(rbjShelfDb(true, 200.0, 6.0, 1.0, qForm = true, evalHz = f), drawn[i], 1e-3, "at $f Hz") }
        assertEquals(ShelfForm.SLOPE, DeviceTarget.ECHO_A.shelfForm); assertEquals(ShelfForm.SLOPE, DeviceTarget.MOONDROP_DUSK.shelfForm)
        // a steep strong shelf on a Q-form DAC draws and gets an AUTO value; where AUTO needs more than the preamp takes, the range message says so, never an exception
        val steep = Band("x", FilterType.LOW_SHELF, 1000.0, 12.0, 10.0)
        val two = Profile("p", "p", bands = listOf(steep, steep.copy(id = "y")), createdAt = 0, updatedAt = 0)
        assertTrue(runCatching { Dsp.responseDb(two.bands, DeviceTarget.FIIO_JA11.shelfForm) }.isSuccess)
        val issues = DeviceTarget.FIIO_JA11.issues(two)
        assertTrue(issues.any { it.startsWith("Pregain") && it.endsWith("outside -24.0..0.0 dB") }, issues.toString())
        assertTrue(DeviceTarget.FIIO_JA11.invalidEqHeadline(two).contains("preamp"), DeviceTarget.FIIO_JA11.invalidEqHeadline(two))
        assertTrue(DeviceTarget.MICRO.issues(two.copy(bands = listOf(steep))).isNotEmpty()) // the slope form has no curve for it: refused by a reason, as before
    }
}
