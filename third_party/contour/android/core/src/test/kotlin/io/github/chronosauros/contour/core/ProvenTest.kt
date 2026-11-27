package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.DacWrite
import io.github.chronosauros.contour.core.native.FiioReset
import io.github.chronosauros.contour.core.native.SnowSkyMelody
import io.github.chronosauros.contour.core.native.WalkPlayProfiles
import io.github.chronosauros.contour.core.native.WalkPlayReset
import io.github.chronosauros.contour.core.native.WalkPlaySettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate ([Proven]): one decision, per-feature flags as the only source of what is proven. Everything unproven is off in a release and on in a review build; a flag that turns true
 * opens its feature in a release without any other change.
 */
class ProvenTest {
    private fun <T> release(block: () -> T) = Proven.asChannel(BuildChannel.RELEASE, block)
    private fun <T> review(block: () -> T) = Proven.asChannel(BuildChannel.PERF, block)

    private fun profile(vararg b: Band, preamp: Double = 0.0) = Profile("id", "t", bands = b.toList(), preampDb = preamp, createdAt = 0, updatedAt = 0)
    private val pk = Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)

    @Test fun `the decision is proven or a review build, and nothing else`() {
        assertTrue(Proven.allows(true, BuildChannel.RELEASE)); assertTrue(Proven.allows(true, BuildChannel.PERF))
        assertFalse(Proven.allows(false, BuildChannel.RELEASE)); assertTrue(Proven.allows(false, BuildChannel.PERF))
        release { assertTrue(Proven.on(true)); assertFalse(Proven.on(false)) }
        review { assertTrue(Proven.on(true)); assertTrue(Proven.on(false)) }
    }

    @Test fun `only perf and debug are review builds, an unknown build type is a release`() {
        assertEquals(BuildChannel.PERF, Proven.channelFor("perf")); assertEquals(BuildChannel.PERF, Proven.channelFor("debug"))
        for (t in listOf("release", "beta", "", "Perf", "staging")) assertEquals(BuildChannel.RELEASE, Proven.channelFor(t), t)
        assertEquals(BuildChannel.RELEASE, Proven.channel) // the default of every process that never sets it (the bench, the tests)
    }

    @Test fun `the Dawn Pro 2, the Allegro Mini and the Echo-A write in every build since the desk and phone runs of 25 October 2026`() {
        for (t in DeviceTarget.FAMILIES) {
            release { assertFalse(t.readOnly, t.caps.name); assertEquals(emptyList(), t.issues(profile(pk)), t.caps.name) }
            review { assertFalse(t.readOnly, t.caps.name); assertEquals(emptyList(), t.issues(profile(pk)), t.caps.name) }
        }
        // the flag is the single source: an Echo-A without it is read only in a release and writes in a review build
        val unproven = WalkPlayProfiles.echoA.copy(hardwareVerified = false)
        release {
            assertTrue(DeviceTarget.forEcho(unproven).readOnly); assertFalse(unproven.writeOn)
            assertTrue(DeviceTarget.forEcho(unproven).issues(profile(pk)).single().endsWith("is read only in Contour")) // a release names no gate
            assertTrue(WalkPlayProfiles.echoA.writeOn)
        }
        review { assertTrue(unproven.writeOn); assertTrue(DeviceTarget.READ_ONLY_ISSUE.contains("not verified on hardware")) }
    }

    @Test fun `the Micro's pass filters and positive preamp follow their own flags and the channel`() {
        release {
            assertTrue(ProtocolMicro.passFiltersOn); assertFalse(ProtocolMicro.microPositivePreampOn) // LP and HP proven at 48 kHz on 25.10.2026, the positive preamp not (+3 does not raise the level)
            assertTrue(FilterType.LOW_PASS in DeviceProtocol.MICRO.caps.types); assertTrue(FilterType.HIGH_PASS in DeviceTarget.MICRO.caps.types)
            assertEquals(0, ProtocolMicro.PREAMP_MAX_EFFECTIVE_DB); assertEquals(0, DeviceProtocol.MICRO.preampMax); assertEquals(0.0, DeviceTarget.MICRO.preampMax)
            assertEquals(0, WalkPlayProfiles.micro.preampMaxDb); assertTrue(FilterType.LOW_PASS in WalkPlayProfiles.micro.caps.types)
            assertFalse(DeviceTarget.MICRO.preampFits(listOf(pk), 3.0))
        }
        review {
            assertTrue(ProtocolMicro.passFiltersOn); assertTrue(ProtocolMicro.microPositivePreampOn)
            assertTrue(FilterType.LOW_PASS in DeviceProtocol.MICRO.caps.types); assertTrue(FilterType.HIGH_PASS in DeviceTarget.MICRO.caps.types)
            assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, ProtocolMicro.PREAMP_MAX_EFFECTIVE_DB); assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, DeviceProtocol.MICRO.preampMax)
            assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, WalkPlayProfiles.micro.preampMaxDb); assertTrue(FilterType.LOW_PASS in WalkPlayProfiles.micro.caps.types)
            assertTrue(DeviceTarget.MICRO.preampFits(listOf(pk), 3.0))
        }
        // the Echo-A runs the Micro's code but has its own positive-preamp flag (split 25.10.2026): same gate, own evidence
        release {
            assertFalse(ProtocolMicro.echoAPositivePreampOn); assertEquals(0, ProtocolMicro.ECHO_A_PREAMP_MAX_EFFECTIVE_DB); assertEquals(0, WalkPlayProfiles.echoA.preampMaxDb)
        }
        review {
            assertTrue(ProtocolMicro.echoAPositivePreampOn); assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, ProtocolMicro.ECHO_A_PREAMP_MAX_EFFECTIVE_DB)
            assertEquals(ProtocolMicro.VENDOR_PREAMP_MAX_DB, WalkPlayProfiles.echoA.preampMaxDb)
        }
        // the Max and the TRN keep the preamp at 0 dB in every build: it is not the Micro's feature (the TRN has its own pass-filter flag: TrnDawnPassFilterTest; the Max's candidates are ProtocolMax.CANDIDATES_VERIFIED's)
        review {
            assertEquals(0, DeviceProtocol.TRN.preampMax); assertEquals(0, DeviceProtocol.MAX.preampMax)
        }
    }

    @Test fun `the Melody's pass filters are proven on the phone, so every build has them`() {
        assertTrue(io.github.chronosauros.contour.core.native.SnowSkyMelody.PASS_FILTERS_VERIFIED)
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertTrue(SnowSkyMelody.passFiltersOn, "$ch")
            assertTrue(setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS, FilterType.BAND_PASS, FilterType.ALL_PASS).all { it in DeviceTarget.MELODY.caps.types }, "$ch")
        }
        // the Tiny A and the K13 R2R have the seven in every build: not the Melody's feature (the KA15's flag has its own test)
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertEquals(7, DeviceTarget.TINY_A.caps.types.size); assertEquals(7, DeviceTarget.K13.caps.types.size)
        }
    }

    @Test fun `the KA15's pass filters are proven on the phone, so every build has them`() {
        // Nothing Phone 25.10.2026 (run 2026-10-25-contour16-r7-phone-nothing): LP, HP, BP, AP written as 4, 5, 3, 6 and read back VERIFIED
        assertTrue(io.github.chronosauros.contour.core.native.FiioKa15.PASS_FILTERS_VERIFIED)
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertTrue(io.github.chronosauros.contour.core.native.FiioKa15.passFiltersOn, "$ch")
            assertEquals(FilterType.entries.toSet(), DeviceTarget.KA15.caps.types, "$ch")
            assertEquals(FilterType.entries.toSet(), DeviceTarget.find(DeviceTarget.KA15.fiio!!.capture!!.vendorId, DeviceTarget.KA15.fiio!!.capture!!.productId)!!.caps.types, "$ch")
        }
        // the bench target has them whatever the flag and the build say
        assertEquals(FilterType.entries.toSet(), DeviceTarget.ka15(true).caps.types)
        assertEquals(DeviceTarget.KA15, DeviceTarget.ka15(false))
    }

    @Test fun `a settings row is written when it is proven or in a review build, and USB AUDIO never`() {
        val rows = (listOf(DeviceTarget.KA15, DeviceTarget.K13, DeviceTarget.MELODY, DeviceTarget.TINY_A).flatMap { it.settings } +
            DeviceTarget.DAWN_PRO_2.settings + DeviceTarget.ALLEGRO_MINI.settings)
        assertTrue(rows.any { it.writeVerified } && rows.any { !it.writeVerified && it.writeCommand != null })
        for (s in rows) {
            val frame = s.writeCommand != null && s.writeCommand !in DacWrite.READ_ONLY
            release { assertEquals(frame && s.writeVerified, s.writeEnabled, s.id) }
            review { assertEquals(frame, s.writeEnabled, s.id) }
        }
        val uac = rows.first { it.writeCommand in DacWrite.READ_ONLY }
        review { assertFalse(uac.writeEnabled) }
        // no frame, no write, whatever the flag says
        val readOnly = rows.first { it.writeCommand == null }
        review { assertFalse(readOnly.writeEnabled); assertFalse(readOnly.copy(writeVerified = true).writeEnabled) }
        // one flag turning true opens a row in a release
        val unproven = rows.first { !it.writeVerified && it.writeCommand != null && it.writeCommand !in DacWrite.READ_ONLY }
        release { assertFalse(unproven.writeEnabled); assertTrue(unproven.copy(writeVerified = true).writeEnabled) }
    }

    @Test fun `the Allegro EQ switch and Global Gain are proven on the phone, the Dawn Pre Gain is review only and its Global Gain is never written`() {
        val allegro = DeviceTarget.ALLEGRO_MINI.settings.associateBy { it.id }
        val dawn = DeviceTarget.DAWN_PRO_2.settings.associateBy { it.id }
        release {
            // passed the PC bench (runs/2026-10-21-kiwi-ears-allegro-mini-02 T14) and the Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone (EQ OFF/ON, Global Gain 1/15/13 VERIFIED): on in a release
            assertTrue(allegro.getValue("eq-switch").writeEnabled); assertTrue(allegro.getValue("global-gain").writeEnabled)
            assertTrue(dawn.values.none { it.writeEnabled })
        }
        review {
            assertTrue(allegro.getValue("eq-switch").writeEnabled); assertTrue(allegro.getValue("global-gain").writeEnabled)
            assertFalse(dawn.getValue("global-gain").writeEnabled); assertTrue(dawn.getValue("pre-gain").writeEnabled); assertFalse(dawn.getValue("eq-index").writeEnabled)
        }
    }

    @Test fun `the Max's LOW PASS, HIGH PASS, +-12 dB and wrapped Q30 shelf boost are proven (the phone run of 10 November 2026) and offered in every build, and off when the flag is off`() {
        val max = DeviceProtocol.MAX
        val hsBoost = Band("hs", FilterType.HIGH_SHELF, 10000.0, 9.0, 0.71)   // its biquad overflows signed Q30
        val pk12 = Band("pk", FilterType.PEAK, 1000.0, 12.0, 1.0)
        val lp = Band("lp", FilterType.LOW_PASS, 9000.0, 0.0, 0.71)
        // the flag off: what a release offered before the phone run (PEAK and the shelves at +-10 dB, an overflowing shelf refused), as the narrowed caps say
        val off = ProtocolMax.capabilities(passFilters = false, wideGain = false, wrapQ30 = false)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), off.types)
        assertEquals(-10.0, off.gainMinDb); assertEquals(10.0, off.gainMaxDb); assertFalse(off.wrapQ30); assertEquals(Math.sqrt(2.0), off.shelfQScale)
        for (b in listOf(hsBoost, pk12, lp)) assertTrue(max.plan(listOf(b), null, off) is DevicePlan.Rejected, b.type.name)
        assertTrue(ProtocolMax.CANDIDATES_VERIFIED)
        for (channel in listOf<(() -> Unit) -> Unit>({ b -> release { b() } }, { b -> review { b() } })) channel {
            assertTrue(ProtocolMax.candidatesOn)
            assertTrue(setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS).all { it in max.caps.types }); assertEquals(max.caps.types, DeviceTarget.MAX.caps.types)
            assertEquals(-12.0, max.caps.gainMinDb); assertEquals(12.0, max.caps.gainMaxDb); assertTrue(max.caps.wrapQ30)
            assertEquals(Math.sqrt(2.0), max.caps.shelfQScale)
            for (b in listOf(pk12, lp)) assertTrue(max.plan(listOf(b), null) is DevicePlan.Ready, b.type.name)
            // the boost goes out wrapped to int32 exactly as the quantizer and the byte split of devicePEQ make it, the register Q is the intended Q x sqrt2, and the read-back compares registers only
            val plan = max.plan(listOf(hsBoost), null) as DevicePlan.Ready
            val report = WalkPlay.bandWriteReport(plan.bands[0], 0)
            assertContentEquals(WalkPlay.computeIir(10000.0, 9.0, 0.71, WalkPlay.TYPE_HSQ, 96000.0, wrapQ30 = true), report.copyOfRange(8, 28))
            assertEquals(Math.round(0.71 * Math.sqrt(2.0) * 256).toInt(), plan.bands[0].registers().q256)
            assertTrue(max.matches(plan, plan.bands.map { it.registers() }, plan.preampDb))
            // the other WalkPlay DACs never wrap
            assertFailsWith<WalkPlay.Q30Overflow> { WalkPlay.bandWriteReport(plan.bands[0].copy(wrapQ30 = false), 0) }
            assertFalse(DeviceProtocol.TRN.caps.wrapQ30); assertFalse(DeviceProtocol.MICRO.caps.wrapQ30)
        }
    }

    @Test fun `the four TRN DAC Filter tab rows are released and the mics are not, so the Micro and the Echo-A have no page in a release and the Max has FILTER, WORK MODE and BALANCE`() {
        val trn = WalkPlayProfiles.trn
        val tabRows = listOf("dac-filter", "work-mode", "gain-mode", "balance")
        release {
            assertEquals(tabRows, WalkPlaySettings.offered(trn).map { it.id })
            for (p in listOf(WalkPlayProfiles.micro, WalkPlayProfiles.echoA)) assertEquals(emptyList(), WalkPlaySettings.offered(p).map { it.id }, p.id)
            // the Max: FILTER, WORK MODE and BALANCE verified on a phone (WORK MODE kept after an unplug, run 22-phone); its MIC GAIN row (vendor parity, written with the flash save 01 01 00) verified by the Mac run 2026-11-11-crinear-protocol-max-23 (MAX_MIC_VERIFIED); it has no gain row (the unit's physical Eco / Boost switch wins after every replug)
            assertEquals(listOf("dac-filter", "work-mode", "balance", "mic"), WalkPlaySettings.offered(WalkPlayProfiles.max).map { it.id })
            assertEquals(WalkPlaySettings.offered(trn), DeviceTarget.TRN.settings)
            assertTrue(DeviceTarget.TRN.settings.all { it.writeVerified && it.writeEnabled })
            assertTrue(WalkPlaySettings.of(trn).single { it.id == "mic" }.let { !it.writeVerified && !it.writeEnabled })   // the mic stays read only: hidden here
            assertTrue(DeviceTarget.MICRO.settings.isEmpty() && DeviceTarget.ECHO_A.settings.isEmpty()); assertEquals(listOf("dac-filter", "work-mode", "balance", "mic"), DeviceTarget.MAX.settings.map { it.id })
        }
        review {
            assertEquals(tabRows + "mic", WalkPlaySettings.offered(trn).map { it.id })
            assertEquals(listOf("mic"), WalkPlaySettings.offered(WalkPlayProfiles.micro).map { it.id })
            assertEquals(listOf("mic"), WalkPlaySettings.offered(WalkPlayProfiles.echoA).map { it.id })
            assertEquals(listOf("dac-filter", "work-mode", "balance", "mic"), WalkPlaySettings.offered(WalkPlayProfiles.max).map { it.id })   // the same four rows in a review build
            assertTrue(WalkPlaySettings.offered(WalkPlayProfiles.max).all { it.writeEnabled })
            assertEquals(WalkPlaySettings.offered(trn), DeviceTarget.TRN.settings)
            val byId = DeviceTarget.TRN.settings.associateBy { it.id }
            for (id in tabRows) assertTrue(byId.getValue(id).writeEnabled, id)
            // the TRN ignores the mic write (runs/2026-10-21-trn-black-pearl-01 and -02 T14): the row is read only in every build
            assertFalse(byId.getValue("mic").writeEnabled); assertEquals(null, byId.getValue("mic").writeCommand)
            // the Micro and the Echo-A take the mic write for some values only (RUN-MIC2, RUN-EA4 and the phone runs): the row is shown and offers no write, review build included
            for (t in listOf(DeviceTarget.MICRO, DeviceTarget.ECHO_A)) review {
                val mic = t.settings.single()
                assertEquals("mic", mic.id); assertEquals(null, mic.writeCommand); assertFalse(mic.writeEnabled, t.caps.name); assertFalse(mic.copy(writeVerified = true).writeEnabled, t.caps.name)
            }
            // the gain mode is writable in every build: offered() no longer strips its write
            assertEquals(WalkPlaySettings.GAIN_MODE, byId.getValue("gain-mode").id); assertTrue(byId.getValue("gain-mode").writeCommand != null)
        }
    }

    @Test fun `the factory resets are offered where the vendor tool has them and open only in a review build until a unit has proven them`() {
        val fiio = listOf(DeviceTarget.KA15, DeviceTarget.MELODY, DeviceTarget.TINY_A, DeviceTarget.K13).map { it.fiio }
        for (c in fiio) {
            // the KA15 (runs/2026-10-22-fiio-ka15-05-phone) and the Melody (PC runs/2026-10-22-snowsky-melody-03, phone runs/2026-10-22-snowsky-melody-01-phone) resets passed on hardware: open in a release build too; the others open only in a review build
            val proven = c === DeviceTarget.KA15.fiio || c === DeviceTarget.MELODY.fiio
            release { assertEquals(proven, FiioReset.enabled(c), c!!.productName) }
            review { assertEquals(FiioReset.offered(c), FiioReset.enabled(c), c!!.productName) }
            assertEquals(proven, FiioReset.proven(c), c!!.productName)
        }
        assertFalse(FiioReset.offered(DeviceTarget.K13.fiio)); assertTrue(FiioReset.offered(DeviceTarget.KA15.fiio))
        // the Tiny A ignores the reset frame (runs 2026-10-23-snowsky-tiny-a-02): the bench still offers it, the app never does, in either channel
        assertTrue(FiioReset.offered(DeviceTarget.TINY_A.fiio))
        for (b in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(b) {
            assertFalse(FiioReset.inApp(DeviceTarget.TINY_A.fiio)); assertFalse(FiioReset.enabledInApp(DeviceTarget.TINY_A.fiio))
            assertTrue(FiioReset.inApp(DeviceTarget.MELODY.fiio) && FiioReset.inApp(DeviceTarget.KA15.fiio))
        }
        // the Melody's reset is proven: the app runs it in a release build too (Tiny A: never, above)
        release { assertTrue(FiioReset.proven(DeviceTarget.MELODY.fiio) && FiioReset.enabledInApp(DeviceTarget.MELODY.fiio)) }
        review { assertTrue(FiioReset.enabledInApp(DeviceTarget.MELODY.fiio)) }
        for (p in listOf(WalkPlayProfiles.micro, WalkPlayProfiles.echoA, WalkPlayProfiles.trn)) {
            release { assertFalse(WalkPlayReset.enabled(p), p.id) }
            review { assertTrue(WalkPlayReset.enabled(p), p.id) }
            assertFalse(WalkPlayReset.proven(p))
        }
        for (b in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(b) { assertFalse(WalkPlayReset.enabled(WalkPlayProfiles.max), "the Max has no reset") }
    }

    @Test fun `asChannel puts the channel back even when the block throws`() {
        runCatching { Proven.asChannel(BuildChannel.PERF) { error("x") } }
        assertEquals(BuildChannel.RELEASE, Proven.channel)
    }
}
