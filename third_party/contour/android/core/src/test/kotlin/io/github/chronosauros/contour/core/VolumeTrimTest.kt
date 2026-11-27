package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * MAX VOLUME on the Protocol Micro (owner 25.11.2026): one fixed cut kept for the DAC and added under the preamp register of every profile at send time, so a change of
 * profile keeps the level. It moves the preamp register only, never below -30 dB, never louder than the profile alone, and a read sent back is not cut twice.
 */
class VolumeTrimTest {
    private fun pk(gain: Double, f: Double = 1000.0) = Band("pk$f$gain", FilterType.PEAK, f, gain, 1.0)
    private fun hs(gain: Double) = Band("hs", FilterType.HIGH_SHELF, 8000.0, gain, 0.7)
    private fun ready(p: DevicePlan) = assertIs<DevicePlan.Ready>(p)
    private fun reads(plan: DevicePlan.Ready) = plan.bands.map { WalkPlay.parseBand(WalkPlay.bandWriteReport(it, 0)) }
    private fun prof(id: String, name: String, bands: List<Band>, preampDb: Double?) = Profile(id = id, name = name, bands = bands, preampDb = preampDb, createdAt = 0, updatedAt = 0)

    private val profiles = listOf(
        prof("auto", "AUTO +3", listOf(pk(3.0)), null),
        prof("manual", "MANUAL -4", listOf(pk(-2.0), pk(1.0, 4000.0)), -4.0),
        prof("hs", "HIGH SHELF", listOf(pk(1.0), hs(-2.0)), -3.0),
        prof("flat", "FLAT 0", listOf(pk(0.0)), 0.0),
    )

    @Test fun `no trim is the plan as before, byte for byte`() {
        for (p in profiles) assertEquals(ProtocolMicro.plan(p), ProtocolMicro.plan(p, trimDb = 0), p.name)
        for (p in profiles) assertEquals(DeviceProtocol.MICRO.plan(p), DeviceProtocol.MICRO.plan(p, 0), p.name)
    }

    @Test fun `a trim moves only the preamp register, by the trim`() {
        for (p in profiles) {
            val own = ready(ProtocolMicro.plan(p))
            val cut = ready(ProtocolMicro.plan(p, trimDb = -20))
            assertEquals(own.bands, cut.bands, p.name)
            assertEquals(own.preampDb - 20, cut.preampDb, p.name)
        }
        // the emulated HIGH SHELF's gain stays in the register too: preamp -3 + HS -2 = -5, under -20 = -25
        assertEquals(-25, ready(ProtocolMicro.plan(profiles[2], trimDb = -20)).preampDb)
    }

    @Test fun `a trimmed send writes the same frames as a profile with that preamp`() {
        val cut = ready(ProtocolMicro.plan(profiles[0], trimDb = -20)) // AUTO -3 under -20
        val same = ready(ProtocolMicro.plan(profiles[0].copy(preampDb = -23.0)))
        assertEquals(same, cut)
        assertEquals(WalkPlay.writeSequence(same.bands, same.preampDb.toDouble(), 0, commit = true).map { it.report.toList() },
            WalkPlay.writeSequence(cut.bands, cut.preampDb.toDouble(), 0, commit = true).map { it.report.toList() })
    }

    @Test fun `switching profiles keeps the level under a trim`() {
        val a = ready(ProtocolMicro.plan(profiles[0], trimDb = -18)).preampDb // AUTO -3
        val b = ready(ProtocolMicro.plan(profiles[3], trimDb = -18)).preampDb // MANUAL 0
        assertEquals(ready(ProtocolMicro.plan(profiles[0])).preampDb - ready(ProtocolMicro.plan(profiles[3])).preampDb, a - b)
    }

    @Test fun `the sum stops at -30 dB and never plays louder than the profile alone`() {
        for (own in -30..0) for (trim in -30..0) {
            val r = ProtocolMicro.trimmedRegister(own, trim)
            assertTrue(r in -30..own, "own $own, trim $trim -> $r")
            if (own + trim >= -30) assertEquals(own + trim, r)
        }
        val deep = prof("deep", "AUTO -12", listOf(pk(10.0), pk(2.0)), null)
        assertEquals(-12, ready(ProtocolMicro.plan(deep)).preampDb)
        assertEquals(-30, ready(ProtocolMicro.plan(deep, trimDb = -20)).preampDb)
    }

    @Test fun `a trim never makes a refused profile fit, nor refuses one that fits`() {
        val loud = prof("loud", "MANUAL +1", listOf(pk(0.0)), 1.0)
        assertIs<DevicePlan.Rejected>(ProtocolMicro.plan(loud))
        assertIs<DevicePlan.Rejected>(ProtocolMicro.plan(loud, trimDb = -20))
        val tooDeep = prof("deep", "AUTO", listOf(pk(10.0), pk(10.0, 1100.0), pk(10.0, 900.0), pk(10.0, 1050.0)), null)
        assertEquals(ProtocolMicro.plan(tooDeep) is DevicePlan.Rejected, ProtocolMicro.plan(tooDeep, trimDb = -30) is DevicePlan.Rejected)
        val floor = prof("floor", "MANUAL -30", listOf(pk(0.0)), -30.0)
        assertEquals(-30, ready(ProtocolMicro.plan(floor, trimDb = -30)).preampDb)
    }

    @Test fun `the profile sent under a trim is ON DAC, and only under that trim`() {
        val p = profiles[1]
        val cut = ready(ProtocolMicro.plan(p, trimDb = -20))
        val regs = cut.bands.map { it.registers() }
        assertTrue(DeviceProtocol.MICRO.matches(p, regs, cut.preampDb, -20))
        assertFalse(DeviceProtocol.MICRO.matches(p, regs, cut.preampDb, 0))
        assertFalse(DeviceProtocol.MICRO.matches(p, regs, cut.preampDb, -19))
    }

    @Test fun `a read comes back without the trim, so sending it back does not cut twice`() {
        val cut = ready(ProtocolMicro.plan(profiles[0], trimDb = -20)) // AUTO -3 -> register -23
        val eq = assertIs<DacImport.Ready>(DeviceProtocol.MICRO.importExact(reads(cut), cut.preampDb, trimDb = -20)).eq
        assertEquals(-3.0, eq.preampDb)
        val back = prof("back", "FROM DAC", eq.bands, eq.preampDb)
        assertEquals(cut, ready(ProtocolMicro.plan(back, trimDb = -20)))
    }

    @Test fun `a read at the floor comes back as the loudest preamp that writes the same register`() {
        val deep = prof("deep", "AUTO -12", listOf(pk(10.0), pk(2.0)), null)
        val cut = ready(ProtocolMicro.plan(deep, trimDb = -20)) // -12 under -20 -> -30
        val eq = assertIs<DacImport.Ready>(DeviceProtocol.MICRO.importExact(reads(cut), cut.preampDb, trimDb = -20)).eq
        assertEquals(-10.0, eq.preampDb)
        assertEquals(cut, ready(ProtocolMicro.plan(prof("back", "FROM DAC", eq.bands, eq.preampDb), trimDb = -20)))
    }

    @Test fun `a DAC not written under the trim is read as it is, not refused`() {
        // the trim set and nothing sent since: the register (0) is above anything a send under -20 writes
        val own = ready(ProtocolMicro.plan(profiles[3])) // MANUAL 0
        val eq = assertIs<DacImport.Ready>(DeviceProtocol.MICRO.importExact(reads(own), own.preampDb, trimDb = -20)).eq
        assertEquals(0.0, eq.preampDb)
        val regs = own.bands.map { it.registers() }
        assertTrue(DeviceProtocol.MICRO.matches(profiles[3], regs, own.preampDb, 0))
        assertFalse(DeviceProtocol.MICRO.matches(profiles[3], regs, own.preampDb, -20)) // ON DAC only after a send under the trim
        // a whole factory-flat DAC still opens
        assertIs<DacImport.Ready>(DeviceProtocol.MICRO.importExact(reads(ProtocolMicro.flatPlan()), 0, trimDb = -20))
    }

    @Test fun `MAX VOLUME is the Micro's only, in whole dB from -30 to 0`() {
        assertEquals(listOf(DeviceProtocol.MICRO), DeviceProtocol.entries.filter { it.takesVolumeTrim })
        assertFailsWith<IllegalArgumentException> { DeviceProtocol.MAX.plan(profiles[0], -1) }
        assertFailsWith<IllegalArgumentException> { ProtocolMicro.plan(profiles[0], trimDb = -31) }
        assertFailsWith<IllegalArgumentException> { ProtocolMicro.plan(profiles[0], trimDb = 1) }
        assertEquals(-30, ProtocolMicro.TRIM_MIN_DB)
    }
}
