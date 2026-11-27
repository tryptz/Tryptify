package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.MoondropCatalog
import io.github.chronosauros.contour.core.native.MoondropCodec
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Dawn Pro 2 plays a shelf's Q register divided by sqrt(2) (run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k: the registers read back, shelf law `reg`; the plain-Q law misses one
 * shelf case by 1.13 dB), so the record's `peq.shelfQScale` = sqrt2 puts intended Q x sqrt(2) in the register of a shelf, and only there: peaks and pass filters play the register as Q.
 */
class MoondropShelfQTest {
    private val model = MoondropCatalog.dawnPro2
    private fun band(type: FilterType, q: Double, gain: Double = 5.0, f: Double = 100.0) = Band("t", type, f, if (type.isPass) 0.0 else gain, q)
    private fun q8(type: FilterType, q: Double, f: Double = 100.0, gain: Double = 5.0) = MoondropCodec.planFor(model, listOf(band(type, q, gain, f)), null).also { assertEquals(emptyList(), it.issues, "$type $q") }.bands[0].qQ8

    @Test fun `the record carries sqrt2 and the model, the target and the catalog table say so`() {
        assertEquals(sqrt(2.0), model.shelfQScale)
        assertEquals(sqrt(2.0), DeviceTarget.DAWN_PRO_2.caps.shelfQScale)
        assertEquals(1.0, DeviceTarget.DAWN_PRO_2.caps.peakQScale)
        // the intended Q range of a shelf is the register range divided by the scale; a peak and a pass filter keep the register range
        assertEquals(model.maxQ / sqrt(2.0), DeviceTarget.DAWN_PRO_2.caps.qRange(FilterType.LOW_SHELF).endInclusive, 1e-12)
        assertEquals(model.maxQ, DeviceTarget.DAWN_PRO_2.caps.qRange(FilterType.PEAK).endInclusive)
        assertEquals(model.maxQ, DeviceTarget.DAWN_PRO_2.caps.qRange(FilterType.LOW_PASS).endInclusive)
    }

    @Test fun `a shelf at Q 0_71 writes the register Q x sqrt2, a peak and a pass filter write the Q as it is`() {
        assertEquals(Math.round(0.71 * sqrt(2.0) * 256).toInt(), q8(FilterType.LOW_SHELF, 0.71))
        assertEquals(257, q8(FilterType.LOW_SHELF, 0.71))
        assertEquals(Math.round(1.0 * sqrt(2.0) * 256).toInt(), q8(FilterType.HIGH_SHELF, 1.0, 8000.0, -6.0))
        assertEquals(362, q8(FilterType.HIGH_SHELF, 1.0, 8000.0, -6.0))
        assertEquals(182, q8(FilterType.PEAK, 0.71))
        assertEquals(182, q8(FilterType.LOW_PASS, 0.71))
        assertEquals(182, q8(FilterType.HIGH_PASS, 0.71))
    }

    @Test fun `the shelf Q ceiling is the register ceiling over sqrt2`() {
        val top = model.maxQ / sqrt(2.0)
        assertEquals(emptyList(), MoondropCodec.planFor(model, listOf(band(FilterType.HIGH_SHELF, top - 0.01, 0.0)), null).issues)
        assertTrue(MoondropCodec.planFor(model, listOf(band(FilterType.HIGH_SHELF, top + 0.01, 0.0)), null).issues.isNotEmpty())
        // the target says the same before a send
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(Profile("p", "", bands = listOf(band(FilterType.LOW_SHELF, top - 0.01, 0.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)))
        assertTrue(DeviceTarget.DAWN_PRO_2.issues(Profile("p", "", bands = listOf(band(FilterType.LOW_SHELF, top + 0.01, 0.0)), preampDb = 0.0, createdAt = 0, updatedAt = 0)).isNotEmpty())
        // a peak keeps the whole register range
        assertEquals(emptyList(), MoondropCodec.planFor(model, listOf(band(FilterType.PEAK, 10.0, 0.0)), null).issues)
    }

    @Test fun `the biquad stays on the intended Q, so only the register moves`() {
        val shelf = MoondropCodec.bandWrite(0, band(FilterType.LOW_SHELF, 0.71), shelfQScale = sqrt(2.0))
        val plain = MoondropCodec.bandWrite(0, band(FilterType.LOW_SHELF, 0.71))
        assertEquals(plain.coefficients.toList(), shelf.coefficients.toList())
        assertEquals(182, plain.qQ8)
        assertEquals(257, shelf.qQ8)
    }

    @Test fun `a shelf read from the DAC comes back as the Q that was typed`() {
        val q = 0.71
        val w = MoondropCodec.planFor(model, listOf(band(FilterType.LOW_SHELF, q)), null).bands[0]
        assertEquals(q, MoondropCodec.intendedQ(model, FilterType.LOW_SHELF, w.qQ8 / 256.0), 0.003) // the register holds Q x sqrt2 to 1/256
        assertEquals(w.qQ8 / 256.0, MoondropCodec.intendedQ(model, FilterType.PEAK, w.qQ8 / 256.0))
        assertEquals(w.qQ8 / 256.0, MoondropCodec.intendedQ(model, null, w.qQ8 / 256.0))
    }
}
