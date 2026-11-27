package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LOW PASS, HIGH PASS, BAND PASS and ALL PASS on the FiiO K13 R2R and SnowSky Tiny A (since 1.6.0; the Melody and the KA15 have them behind their own flags, proven on the phone on 25.10.2026). The type bytes
 * (PK 0, LS 1, HS 2, BP 3, LP 4, HP 5, AP 6) are from FiiO's web app on the Tiny A and K13 R2R, 14.10.2026
 * (research/snowsky-names-types-2026-10-14/FINDINGS.md). Frame framing around captured data bytes is synthetic.
 */
class PassFilterTest {
    private val pass = listOf(FilterType.LOW_PASS, FilterType.HIGH_PASS, FilterType.BAND_PASS, FilterType.ALL_PASS)
    private val codes = mapOf(FilterType.PEAK to 0, FilterType.LOW_SHELF to 1, FilterType.HIGH_SHELF to 2,
        FilterType.BAND_PASS to 3, FilterType.LOW_PASS to 4, FilterType.HIGH_PASS to 5, FilterType.ALL_PASS to 6)
    private val passDevices = listOf(FiioCatalog.K13, FiioCatalog.TINY_A)
    private fun band(type: FilterType, gain: Double = 0.0, f: Double = 100.0, q: Double = 1.0) = Band("t-${type.name}", type, f, gain, q)
    private fun plan(c: FiioCodec, bands: List<Band>) =
        c.planOnExplicitSend(c.padToDeviceCount(bands), 0.0, c.config.userSlots.min(), explicitUserAction = true)

    // ---- DSP -------------------------------------------------------------------------------------------------

    private fun magDb(b: Band, f: Double): Double = Dsp.bandDb(b, ShelfForm.SLOPE, Dsp.FreqGrid(doubleArrayOf(f)))[0]

    @Test fun `band pass has 0 dB at its centre and falls away on both sides`() {
        for (q in listOf(0.5, 1.0, 4.0)) {
            val b = band(FilterType.BAND_PASS, f = 1000.0, q = q)
            assertEquals(0.0, magDb(b, 1000.0), 1e-9, "Q $q")
            assertTrue(magDb(b, 250.0) < -3.0 && magDb(b, 4000.0) < -3.0, "Q $q")
        }
        // Higher Q is narrower.
        assertTrue(magDb(band(FilterType.BAND_PASS, f = 1000.0, q = 4.0), 1500.0) < magDb(band(FilterType.BAND_PASS, f = 1000.0, q = 0.5), 1500.0))
    }

    @Test fun `all pass magnitude is flat within 1e-9 dB at any Q and gain`() {
        // Probed from 1 kHz up: below that Dsp's num/den form (the display evaluation, shared by every filter) loses digits to
        // cancellation near DC (2e-6 dB at 20 Hz), which is invisible on the graph and has nothing to do with the filter.
        for (q in listOf(0.1, 0.5, 0.707, 3.0)) for (f in listOf(40.0, 250.0, 1000.0, 9000.0, 19000.0)) {
            val b = band(FilterType.ALL_PASS, gain = 7.0, f = f, q = q)
            for (probe in listOf(1000.0, 1234.0, 3000.0, 8000.0, 15000.0, 20000.0)) assertEquals(0.0, magDb(b, probe), 1e-9, "f $f Q $q at $probe")
        }
        // Everywhere (20 Hz upward) it is flat within the display evaluation's own precision.
        for (probe in Dsp.logFreqs(64)) assertEquals(0.0, magDb(band(FilterType.ALL_PASS, f = 800.0, q = 2.0), probe), 1e-4, "at $probe")
    }

    @Test fun `all pass turns the phase and band pass biquad matches the RBJ cookbook`() {
        val w0 = 2 * Math.PI * 1000.0 / Dsp.FS
        val alpha = sin(w0) / (2 * 2.0)
        val bp = Dsp.biquad(band(FilterType.BAND_PASS, f = 1000.0, q = 2.0), ShelfForm.SLOPE)
        assertEquals(alpha / (1 + alpha), bp.b0, 1e-12); assertEquals(0.0, bp.b1); assertEquals(-alpha / (1 + alpha), bp.b2, 1e-12)
        val ap = Dsp.biquad(band(FilterType.ALL_PASS, f = 1000.0, q = 2.0), ShelfForm.SLOPE)
        assertEquals((1 - alpha) / (1 + alpha), ap.b0, 1e-12); assertEquals(-2 * cos(w0) / (1 + alpha), ap.b1, 1e-12)
        assertEquals(ap.b0, ap.a2, 1e-12); assertEquals(ap.b1, ap.a1, 1e-12) // numerator is the mirrored denominator
    }

    @Test fun `gain does not change any pass filter`() {
        for (t in pass) for (f in listOf(40.0, 1000.0, 12000.0)) {
            val flat = Dsp.bandDb(band(t, 0.0, f, 1.3), ShelfForm.SLOPE); val loud = Dsp.bandDb(band(t, 9.0, f, 1.3), ShelfForm.SLOPE)
            for (i in flat.indices) assertEquals(flat[i], loud[i], 1e-12, "$t $f")
        }
    }

    @Test fun `low pass and high pass are -3 dB at the corner for Q 0707 and unity in the pass band`() {
        val lp = band(FilterType.LOW_PASS, f = 1000.0, q = 0.7071); val hp = band(FilterType.HIGH_PASS, f = 1000.0, q = 0.7071)
        assertEquals(-3.0, magDb(lp, 1000.0), 0.02); assertEquals(-3.0, magDb(hp, 1000.0), 0.02)
        assertTrue(abs(magDb(lp, 30.0)) < 0.01 && abs(magDb(hp, 18000.0)) < 0.2)
    }

    // ---- Model helpers and text ------------------------------------------------------------------------------

    @Test fun `isPass and isShelf name exactly the pass and the shelf types`() {
        assertEquals(pass.toSet(), FilterType.entries.filter { it.isPass }.toSet())
        assertEquals(setOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF), FilterType.entries.filter { it.isShelf }.toSet())
        assertEquals(listOf("PEAK", "LOW_SHELF", "HIGH_SHELF", "LOW_PASS", "HIGH_PASS", "BAND_PASS", "ALL_PASS"), FilterType.entries.map { it.name })
    }

    @Test fun `APO text writes BP and AP without gain and reads them back`() {
        val bands = listOf(band(FilterType.BAND_PASS, f = 800.0, q = 2.5), band(FilterType.ALL_PASS, f = 4000.0, q = 0.707),
            band(FilterType.LOW_PASS, f = 9000.0, q = 0.5), band(FilterType.HIGH_PASS, f = 30.0, q = 0.7), band(FilterType.PEAK, 2.0, 500.0, 1.0))
        val text = ApoText.format(bands, -1.0)
        assertTrue("ON BP Fc 800 Hz Q 2.50" in text && "ON AP Fc 4000 Hz Q 0.707" in text, text)
        assertTrue("ON LPQ Fc 9000 Hz Q 0.50" in text && "ON HPQ Fc 30 Hz Q 0.70" in text, text)
        assertEquals(4, text.lines().count { " BP " in it || " AP " in it || " LPQ " in it || " HPQ " in it })
        assertFalse(text.lines().filter { " BP " in it || " AP " in it || " LPQ " in it || " HPQ " in it }.any { "Gain" in it }, text)
        val back = ApoText.parse(text)
        assertEquals(bands.map { it.type }, back.bands.map { it.type })
        assertEquals(bands.map { it.freqHz }, back.bands.map { it.freqHz })
        assertEquals(bands.map { it.q }, back.bands.map { it.q })
        assertEquals(bands.map { it.gainDb }, back.bands.map { it.gainDb })
        assertEquals(text, ApoText.format(back.bands, back.preampDb!!))
        // Both spellings APO accepts.
        assertEquals(FilterType.BAND_PASS, ApoText.typeOf("bp")); assertEquals(FilterType.ALL_PASS, ApoText.typeOf("AP"))
    }

    @Test fun `a pass line that carries a Gain comes in with gain 0`() {
        val eq = ApoText.parse("Filter 1: ON HP Fc 80 Hz Gain 3 dB Q 0.7\nFilter 2: ON BP Fc 800 Hz Gain -4 dB Q 2\nFilter 3: ON AP Fc 900 Hz Gain 2 dB Q 1\nFilter 4: ON LP Fc 9000 Hz Gain 5 dB Q 1\n")
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), eq.bands.map { it.gainDb })
        val json = EqByEarJson.parse("""{"bands":[{"type":"BP","fc":800,"gain":6,"q":2},{"type":"PK","fc":100,"gain":6,"q":1}]}""")
        assertEquals(listOf(0.0, 6.0), json.bands.map { it.gainDb })
    }

    // ---- FiiO ------------------------------------------------------------------------------------------------

    @Test fun `K13, Tiny A, Melody and KA15 offer all seven shapes, the KA15's record keeps three`() {
        for (c in passDevices) {
            assertEquals(codes, c.typeCodes, c.productName)
            FiioCodec(c) // configuration invariants
        }
        assertEquals(codes.keys, DeviceTarget.K13.caps.types)
        assertEquals(codes.keys, DeviceTarget.MELODY.caps.types) // since the phone run 25.10.2026 (SnowSkyMelody.PASS_FILTERS_VERIFIED)
        assertEquals(codes.keys, DeviceTarget.TINY_A.caps.types)
        assertEquals(codes.keys, DeviceTarget.KA15.caps.types) // since the phone run 25.10.2026 (FiioKa15.PASS_FILTERS_VERIFIED)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), FiioCatalog.KA15.typeCodes.keys) // the record itself
    }

    @Test fun `the Melody's four pass types are proven by SnowSkyMelody's flag, use the Tiny A's type bytes, and melody(true) is the same target`() {
        assertTrue(SnowSkyMelody.PASS_FILTERS_VERIFIED) // Nothing Phone 25.10.2026: LP, HP, BP, AP written as 4, 5, 3, 6 and read back VERIFIED
        assertEquals(pass.toSet(), SnowSkyMelody.PASS_TYPE_CODES.keys)
        assertEquals(codes.filterKeys { it in pass }, SnowSkyMelody.PASS_TYPE_CODES)
        assertEquals(FiioCatalog.TINY_A.typeCodes.filterKeys { it in pass }, SnowSkyMelody.PASS_TYPE_CODES)
        val profile = Profile("p", "x", bands = listOf(band(FilterType.LOW_PASS, f = 1000.0, q = 0.71)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        // proven: every build, release and beta included, offers the seven; the pass types are written as FiiO's type bytes with gain 0
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertTrue(SnowSkyMelody.passFiltersOn, "$ch")
            assertEquals(codes.keys, DeviceTarget.MELODY.caps.types, "$ch"); assertEquals(codes, DeviceTarget.MELODY.fiio!!.typeCodes, "$ch")
            assertEquals(emptyList(), DeviceTarget.MELODY.issues(profile), "$ch")
            val c = FiioCodec(DeviceTarget.MELODY.fiio!!)
            for (t in pass) {
                val r = plan(c, listOf(band(FilterType.PEAK, 1.0, 50.0, 1.0), band(t, 3.5, 250.0, 0.7))).expected.bands[1]
                assertEquals(codes.getValue(t), r.typeCode, "$ch $t"); assertEquals(0, r.gainTenths, "$ch $t")
            }
            // the other FiiO DACs: the KA15 has its own flag (proven the same day, ProvenTest), the Tiny A has the seven from its record
            assertEquals(codes.keys, DeviceTarget.KA15.caps.types, "$ch")
            assertEquals(codes.keys, DeviceTarget.TINY_A.caps.types)
        }
        // melody(true) (the bench's `--pass-filters`) is the same seven whatever the flag says
        Proven.asChannel(BuildChannel.RELEASE) {
            assertEquals(codes.keys, DeviceTarget.melody(true).caps.types); assertEquals(emptyList(), DeviceTarget.melody(true).issues(profile))
        }
    }

    @Test fun `every pass type compiles to its type byte and gain 0 on K13 and Tiny A`() {
        for (config in passDevices) {
            val c = FiioCodec(config)
            for (t in pass) for (gain in listOf(0.0, 3.5, -7.0)) {
                val p = plan(c, listOf(band(FilterType.PEAK, 1.0, 50.0, 1.0), band(t, gain, 250.0, 0.7)))
                val r = p.expected.bands[1]
                assertEquals(codes.getValue(t), r.typeCode, "${config.productName} $t")
                assertEquals(0, r.gainTenths, "${config.productName} $t gain $gain")
                assertEquals(250, r.frequencyHz); assertEquals(70, r.qHundredths) // Q is the Q, no shelf scale
                val wire = p.writes.single { it.bytes[4] == FiioCodec.BAND && it.bytes[6] == 1 }.bytes
                assertEquals(listOf(0, 0), wire.slice(7..8), "gain bytes on the wire") // gain register 0
                assertEquals(codes.getValue(t), wire[13], "type byte on the wire")
            }
        }
    }

    @Test fun `a stored gain outside the device range never blocks a pass filter`() {
        for (config in passDevices) {
            val c = FiioCodec(config)
            for (t in pass) assertEquals(0, plan(c, listOf(band(t, 99.0))).expected.bands[0].gainTenths)
            // ...while a peak outside the range is still refused.
            assertFailsWith<IllegalArgumentException> { plan(c, listOf(band(FilterType.PEAK, 99.0))) }
        }
        val profile = Profile("p", "hp", bands = listOf(band(FilterType.HIGH_PASS, gain = 40.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), DeviceTarget.K13.issues(profile))
        assertEquals(emptyList(), DeviceTarget.TINY_A.issues(profile))
    }

    @Test fun `a pass filter takes no shelf Q scale or shelf compensation, a shelf still does`() {
        // The KA15 is the device with a shelf Q scale; its Q range for pass types is the plain one.
        assertEquals(DeviceTarget.KA15.caps.qMin..DeviceTarget.KA15.caps.qMax, DeviceTarget.KA15.qRange(FilterType.HIGH_PASS))
        assertEquals(DeviceTarget.KA15.caps.qMin..DeviceTarget.KA15.caps.qMax, DeviceTarget.KA15.qRange(FilterType.ALL_PASS))
        assertTrue(DeviceTarget.KA15.qRange(FilterType.LOW_SHELF).endInclusive < DeviceTarget.KA15.caps.qMax)
        val scaled = FiioCodec(FiioCatalog.K13.copy(shelfQScale = Math.sqrt(2.0), typeCodes = codes)) // a synthetic DAC with both
        val p = scaled.planOnExplicitSend(scaled.padToDeviceCount(listOf(band(FilterType.HIGH_PASS, q = 2.0), band(FilterType.LOW_SHELF, 1.0, q = 2.0))), 0.0, 160, true)
        assertEquals(200, p.expected.bands[0].qHundredths) // pass: Q as is
        assertEquals(283, p.expected.bands[1].qHundredths) // shelf: Q * sqrt(2)
    }

    @Test fun `the KA15's record still holds the four pass types, and the app takes them since its flag turned true`() {
        assertTrue(FiioKa15.PASS_FILTERS_VERIFIED) // Nothing Phone 25.10.2026: LP, HP, BP, AP written as 4, 5, 3, 6 and read back VERIFIED
        val record = FiioCodec(FiioCatalog.KA15)
        for (t in pass) assertFailsWith<IllegalArgumentException> { plan(record, listOf(band(FilterType.PEAK), band(t))) }
        val app = FiioCodec(DeviceTarget.KA15.fiio!!)
        for (t in pass) {
            val profile = Profile("p", "x", bands = listOf(band(t, f = 1000.0, q = 0.71)), preampDb = 0.0, createdAt = 0, updatedAt = 0)
            assertEquals(emptyList(), DeviceTarget.KA15.issues(profile), "$t")
            val r = plan(app, listOf(band(FilterType.PEAK, 1.0, 50.0, 1.0), band(t, 3.5, 250.0, 0.7))).expected.bands[1]
            assertEquals(codes.getValue(t), r.typeCode, "$t"); assertEquals(0, r.gainTenths, "$t")
        }
    }

    @Test fun `Protocol Max and the Micro take LP and HP only since 25 October 2026 and 10 November 2026, and the WalkPlay encoder never maps BP or AP to a peak`() {
        for (t in pass) {
            val plan = DeviceProtocol.MAX.plan(listOf(band(t)), null)
            if (t == FilterType.LOW_PASS || t == FilterType.HIGH_PASS) assertIs<DevicePlan.Ready>(plan, "Max $t") else assertIs<DevicePlan.Rejected>(plan, "Max $t")
        }
        // the Max's LP and HP are ProtocolMax.CANDIDATES_VERIFIED's (the phone run of 10.11.2026): without them the Max refuses all four
        for (t in pass) assertIs<DevicePlan.Rejected>(DeviceProtocol.MAX.plan(listOf(band(t)), null, ProtocolMax.capabilities(false, false)), "Max without the candidates $t")
        for (t in pass) {
            val plan = DeviceProtocol.MICRO.plan(listOf(band(t)), null)
            if (t == FilterType.LOW_PASS || t == FilterType.HIGH_PASS) assertIs<DevicePlan.Ready>(plan, "Micro $t") else assertIs<DevicePlan.Rejected>(plan, "Micro $t")
        }
        assertFailsWith<IllegalArgumentException> { WalkPlay.typeCode(FilterType.BAND_PASS) }
        assertFailsWith<IllegalArgumentException> { WalkPlay.typeCode(FilterType.ALL_PASS) }
        assertEquals(4, WalkPlay.typeCode(FilterType.LOW_PASS)); assertEquals(5, WalkPlay.typeCode(FilterType.HIGH_PASS))
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), DeviceProtocol.MICRO.caps.types)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), DeviceProtocol.MAX.caps.types)
    }

    // ---- reading a pass band back from the DAC ----------------------------------------------------------------

    private fun reply(cmd: Int, data: List<Int>) = (listOf(0xBB, 0x0B, 0, 0, cmd, data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray()
    private fun pair(v: Int) = listOf((v ushr 8) and 255, v and 255)
    private val padBand = { i: Int -> reply(FiioCodec.BAND, listOf(i, 0, 0, 0, 100, 0, 100, 0)) }
    /** K13 R2R USER10, ten bands, [custom] replaces band [at] (a full reply frame around the data bytes). */
    private fun k13Snapshot(c: FiioCodec, at: Int, custom: List<Int>): FiioSnapshot {
        val bands = List(10) { if (it == at) reply(FiioCodec.BAND, custom) else padBand(it) }
        val slot = reply(FiioCodec.SLOT, listOf(169))
        return c.snapshot(slot, reply(FiioCodec.COUNT, listOf(10)), reply(FiioCodec.PREAMP, pair(0)), bands, slot)
    }

    @Test fun `K13 readback after HIGH PASS imports as HIGH PASS 100 Hz Q 1 at 0 dB`() {
        val c = FiioCodec(FiioCatalog.K13)
        // The data bytes are the K13 reply captured with FiiO's web app on 14.10.2026: `15 08 02 00 00 00 64 00 64 05`.
        val snapshot = k13Snapshot(c, 2, listOf(0x02, 0x00, 0x00, 0x00, 0x64, 0x00, 0x64, 0x05))
        assertEquals(FiioRegisters(2, 100, 0, 100, 5), snapshot.bands[2])
        val eq = c.importExact(snapshot)
        assertEquals(FilterType.HIGH_PASS, eq.bands[2].type)
        assertEquals(100.0, eq.bands[2].freqHz); assertEquals(1.0, eq.bands[2].q); assertEquals(0.0, eq.bands[2].gainDb)
        // Exact inverse: the imported EQ compiles back to the registers that were read.
        val again = c.planOnExplicitSend(eq.bands, eq.preampDb, 169, true).expected
        assertTrue(c.matches(again, snapshot))
    }

    @Test fun `a pass band with a nonzero gain register is rejected with its band type and gain, never loaded`() {
        for ((gain, shown) in listOf(30 to "+3.0", -25 to "-2.5")) for ((type, name) in listOf(5 to "HIGH PASS", 4 to "LOW PASS", 3 to "BAND PASS", 6 to "ALL PASS")) {
            val c = FiioCodec(FiioCatalog.K13)
            val snapshot = k13Snapshot(c, 2, listOf(0x02) + pair(gain and 0xFFFF) + pair(100) + pair(100) + listOf(type))
            val e = assertFailsWith<IllegalArgumentException> { c.importExact(snapshot) }
            assertEquals("Band 3: $name has gain $shown dB on the DAC; Contour keeps pass filters at 0 dB", e.message)
            // The session state turns it into a refusal the app shows as a notice (DacSnapshot.importExact catches exactly this).
            val state = NativeState.Fiio(c, snapshot)
            assertEquals(e.message, assertFailsWith<IllegalArgumentException> { state.importExact() }.message)
        }
        // A peak with a gain is of course still imported.
        val c = FiioCodec(FiioCatalog.K13)
        assertEquals(3.0, c.importExact(k13Snapshot(c, 2, listOf(0x02) + pair(30) + pair(100) + pair(100) + listOf(0))).bands[2].gainDb)
    }

    @Test fun `importExact is the exact inverse for every shape on K13 and Tiny A`() {
        for (config in passDevices) {
            val c = FiioCodec(config)
            val bands = codes.keys.mapIndexed { i, t -> band(t, if (t.isPass) 0.0 else -2.0, 100.0 * (i + 1), 0.5 + i) }
            val expected = plan(c, bands).expected
            val eq = c.importExact(expected)
            assertEquals(codes.keys.toList(), eq.bands.take(codes.size).map { it.type }, config.productName)
            assertEquals(expected, c.planOnExplicitSend(eq.bands, eq.preampDb, expected.activeSlot, true).expected, config.productName)
            // Disabled pass band: written as gain 0, type kept.
            assertEquals(5, plan(c, listOf(band(FilterType.HIGH_PASS).copy(enabled = false))).expected.bands[0].typeCode)
        }
    }
}
