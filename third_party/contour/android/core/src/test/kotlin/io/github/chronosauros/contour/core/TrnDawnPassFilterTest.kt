package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.MoondropCatalog
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.core.native.MoondropCodec
import io.github.chronosauros.contour.core.native.WalkPlayProfiles
import io.github.chronosauros.contour.core.native.WalkPlaySession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A port that is never used: the tests only plan. */
private object StubPort : NativePort {
    override val deviceKey = "stub"
    override fun guard() {}
    override fun pause(ms: Long) {}
    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a plan sends nothing")
    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray = error("a plan sends nothing")
}

/**
 * LOW PASS and HIGH PASS on the TRN Black Pearl and the Moondrop Dawn Pro 2, behind [ProtocolTrn.PASS_FILTERS_VERIFIED] and [MoondropCatalog.PASS_FILTERS_VERIFIED] through [Proven.on];
 * the Dawn's shelves behind [MoondropCatalog.SHELVES_VERIFIED]. The TRN's flag is false: a release hides them and a review build ("perf"/"debug") offers them. The Dawn's two flags are true
 * since the desk run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k (LS, HS with the law reg, LP, HP PASS) and the phone run 2026-10-25-contour16-r7-phone-nothing. (The SnowSky Melody's gate,
 * [io.github.chronosauros.contour.core.native.SnowSkyMelody], is in [ProvenTest]; the Micro's in [ProvenTest] too.) PC evidence: the TRN stores LP and HP as sent (19.10.2026, runs RUN-TRN1..3).
 */
class TrnDawnPassFilterTest {
    private fun <T> release(block: () -> T) = Proven.asChannel(BuildChannel.RELEASE, block)
    private fun <T> review(block: () -> T) = Proven.asChannel(BuildChannel.PERF, block)
    private val pass = setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS)
    private fun profile(vararg bands: Band) = Profile("id", "t", bands = bands.toList(), preampDb = -3.0, createdAt = 0, updatedAt = 0)
    private fun lp(f: Double = 9000.0) = Band("lp", FilterType.LOW_PASS, f, 0.0, 0.707)
    private fun hp(f: Double = 30.0) = Band("hp", FilterType.HIGH_PASS, f, 0.0, 0.707)
    private fun pk(f: Double = 9000.0) = Band("pk", FilterType.PEAK, f, 0.0, 0.707)

    @Test fun `the TRN's flag is off, so a release hides LP and HP there, a review build offers them, and the Dawn Pro 2 has LP, HP and both shelves in every build`() {
        val dawn = setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS)
        assertFalse(ProtocolTrn.PASS_FILTERS_VERIFIED); assertTrue(MoondropCatalog.PASS_FILTERS_VERIFIED); assertTrue(MoondropCatalog.SHELVES_VERIFIED)
        release {
            assertFalse(ProtocolTrn.passFiltersOn); assertTrue(MoondropCatalog.passFiltersOn); assertTrue(MoondropCatalog.shelvesOn)
            assertTrue(pass.none { it in DeviceProtocol.TRN.caps.types }); assertTrue(pass.none { it in DeviceTarget.TRN.caps.types })
            assertEquals(dawn, DeviceTarget.DAWN_PRO_2.caps.types); assertEquals(dawn, DeviceTarget.MOONDROP_TYPES)
        }
        review {
            assertTrue(ProtocolTrn.passFiltersOn); assertTrue(MoondropCatalog.passFiltersOn)
            assertTrue(pass.all { it in DeviceProtocol.TRN.caps.types }); assertTrue(pass.all { it in DeviceTarget.TRN.caps.types })
            assertTrue(pass.all { it in WalkPlayProfiles.trn.caps.types })
            assertEquals(dawn, DeviceTarget.DAWN_PRO_2.caps.types)
            // nothing else is widened: the TRN keeps its shelves and gets neither BP nor AP, the Dawn gets neither BP nor AP
            assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF) + pass, DeviceProtocol.TRN.caps.types)
            assertTrue(FilterType.BAND_PASS !in DeviceTarget.DAWN_PRO_2.caps.types && FilterType.ALL_PASS !in DeviceTarget.DAWN_PRO_2.caps.types)
            // the Max's LP and HP follow the Max's own flag (ProtocolMax.CANDIDATES_VERIFIED), not the TRN's
            assertEquals(ProtocolMax.candidatesOn, pass.all { it in DeviceProtocol.MAX.caps.types })
        }
    }

    @Test fun `the TRN plans and writes LP and HP in a review build, the type byte 4 and 5, plain RBJ coefficients the Micro's pass path sends too, and refuses them in a release`() {
        release {
            for (b in listOf(lp(), hp())) assertTrue(DeviceProtocol.TRN.plan(profile(b)) is DevicePlan.Rejected, b.type.name)
            val session = WalkPlaySession(WalkPlayProfiles.trn, StubPort)
            assertTrue(session.plan(profile(lp())) is DevicePlan.Rejected)
        }
        review {
            val micro = DeviceProtocol.MICRO
            for ((band, code) in listOf(lp() to WalkPlay.TYPE_LP, hp() to WalkPlay.TYPE_HP)) {
                val plan = DeviceProtocol.TRN.plan(profile(band)) as DevicePlan.Ready
                val w = plan.bands.first()
                assertEquals(code, w.typeCode); assertEquals(0.0, w.gainDb)
                val frame = DeviceProtocol.TRN.writeSequence(plan, 101).first().report
                assertEquals(WalkPlay.TRN_BAND_LEN, frame[3].toInt() and 255, "the TRN's length byte")
                assertEquals(code, frame[34].toInt() and 255, "the type byte of the band frame")
                // the same band through the Micro's pass path: the frame differs in the length byte alone (and the TRN's other 9 bands are its own factory flat), once the Micro's
                // 93 750 Hz coefficient rate is put back to the TRN's 96 000 Hz
                val m = (micro.plan(profile(band)) as DevicePlan.Ready).bands.first()
                assertEquals(93750.0, m.rateHz)
                val microFrame = WalkPlay.bandWriteReport(m.copy(rateHz = DeviceProtocol.TRN.caps.coefficientRateHz), 101)
                assertEquals(microFrame.toList(), frame.copyOf().also { it[3] = microFrame[3] }.toList(), band.type.name)
                // and it is the RBJ low-pass / high-pass, not the peak biquad devicePEQ sends for these codes
                val peak = WalkPlay.bandWriteReport(WalkPlay.BandWrite(0, band.freqHz, 0.0, band.q, WalkPlay.TYPE_PK), 101, WalkPlay.TRN_BAND_LEN)
                assertNotEquals(peak.copyOfRange(8, 28).toList(), frame.copyOfRange(8, 28).toList(), "${band.type} coefficients are not the peak's")
                // the session path the app uses (DacClient.writePlan) takes it too
                val session = WalkPlaySession(WalkPlayProfiles.trn, StubPort)
                assertTrue(session.plan(profile(band)) is DevicePlan.Ready, band.type.name)
            }
            // AP and BP stay out
            assertTrue(DeviceProtocol.TRN.plan(profile(Band("bp", FilterType.BAND_PASS, 1000.0, 0.0, 1.0))) is DevicePlan.Rejected)
        }
    }

    @Test fun `the Dawn Pro 2 accepts LP and HP in every build, and writes them as native types 4 and 5 with gain 0, and its shelves as native types 1 and 3`() {
        val t = DeviceTarget.DAWN_PRO_2
        val bands = listOf(lp(12000.0), hp(30.0))
        for (ch in listOf(BuildChannel.RELEASE, BuildChannel.PERF)) Proven.asChannel(ch) {
            assertEquals(emptyList(), t.issues(profile(*bands.toTypedArray())), "$ch")
            val planned = MoondropCodec.planFor(t.moondrop!!, bands, -3.0)
            assertEquals(emptyList(), planned.issues, "$ch")
            assertEquals(listOf(4, 5), planned.bands.take(2).map { it.nativeType }, "$ch")
            assertEquals(listOf(0, 0), planned.bands.take(2).map { it.gainQ8 }, "$ch")
            val shelves = listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 3.0, 1.0), Band("hs", FilterType.HIGH_SHELF, 8000.0, -6.0, 1.0))
            assertEquals(emptyList(), t.issues(profile(*shelves.toTypedArray())), "$ch")
            assertEquals(listOf(1, 3), MoondropCodec.planFor(t.moondrop!!, shelves, -3.0).bands.take(2).map { it.nativeType }, "$ch")
            // band pass and all pass have no native code
            assertTrue(t.issues(profile(Band("bp", FilterType.BAND_PASS, 1000.0, 0.0, 1.0))).isNotEmpty(), "$ch")
        }
    }
}
