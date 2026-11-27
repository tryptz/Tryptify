package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The editor keeps what the DAC cannot hold off the screen: a whole-dB preamp on the SnowSky DACs and the KA15 and the gain of an emulated HIGH SHELF on the Micro and the Echo-A. */
class PreampGridTest {
    @Test fun `a manual preamp moves in whole dB exactly where the DAC keeps whole dB, and 0,1 dB elsewhere`() {
        for (t in listOf(DeviceTarget.MELODY, DeviceTarget.TINY_A, DeviceTarget.KA15)) { // KA15: a fractional preamp reset it during the send (Pixel 26.10.2026)
            assertEquals(1.0, t.preampStep, t.caps.name)
            assertEquals(-4.0, t.heldPreamp(-3.5), t.caps.name) // the number the DAC holds after a send (Melody and Tiny A phone runs 25.10.2026: -3.5 written as -4)
            assertEquals(-3.0, t.heldPreamp(-3.0), t.caps.name)
            assertEquals(2.0, t.heldPreamp(2.9), t.caps.name)
        }
        assertEquals(0.1, DeviceTarget.K13.preampStep)
        assertEquals(-3.5, DeviceTarget.K13.heldPreamp(-3.5))
    }

    @Test fun `the WalkPlay targets show the whole dB the planner sends`() {
        // 1.6.2: a manual -2.5 dB on the Protocol Micro showed -2.5 and went out as -3 (user report 27.10.2026); the editor now keeps whole dB there.
        for (t in listOf(DeviceTarget.MICRO, DeviceTarget.MAX, DeviceTarget.TRN, DeviceTarget.ECHO_A)) {
            assertEquals(1.0, t.preampStep, t.caps.name)
            assertEquals(-3.0, t.heldPreamp(-2.5), t.caps.name)
            assertEquals(kotlin.math.floor(-2.5 + 1e-6), t.heldPreamp(-2.5), t.caps.name) // the planner's rule, the same number
        }
        val plan = DeviceProtocol.MICRO.plan(listOf(Band("pk", FilterType.PEAK, 1000.0, 2.5, 1.0)), -2.5)
        assertEquals(DeviceTarget.MICRO.heldPreamp(-2.5).toInt(), (plan as DevicePlan.Ready).preampDb)
    }

    @Test fun `an emulated HIGH SHELF keeps its gain on the preamp grid, so the level is exact`() {
        assertEquals(1.0, DeviceTarget.MICRO.highShelfStepDb)
        for (t in listOf(DeviceTarget.MAX, DeviceTarget.TRN, DeviceTarget.KA15, DeviceTarget.K13, DeviceTarget.MELODY, DeviceTarget.TINY_A)) assertNull(t.highShelfStepDb, t.caps.name) // native shelves
        assertEquals(1.0, DeviceTarget.ECHO_A.highShelfStepDb) // the Echo-A writes in a release since 25.10.2026, with the same whole-dB rule
        assertNull(DeviceTarget.forEcho(DeviceTarget.ECHO_A.echo!!.copy(hardwareVerified = false)).takeIf { !Proven.on(false) }?.highShelfStepDb) // read only: nothing is sent, nothing to keep on a grid
        // Echo-A phone run 25.10.2026: HS -1.5 dB with a -3 dB preamp wrote register -5 (floor(-4.5)), 0.5 dB low; on the whole-dB grid the register is exactly preamp + shelf gain.
        fun register(g: Double) = ProtocolMicro.devicePreamp(listOf(Band("hs", FilterType.HIGH_SHELF, 8000.0, g, 0.7)), -3.0).toDouble()
        assertEquals(-5.0, register(-1.5))
        for (g in listOf(-2.0, -1.0, 0.0, -4.0)) assertEquals(-3.0 + g, register(g), "HS $g dB")
    }
}
