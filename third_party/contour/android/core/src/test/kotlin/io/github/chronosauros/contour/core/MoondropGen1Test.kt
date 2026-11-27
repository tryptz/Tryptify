package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.FamilySettings
import io.github.chronosauros.contour.core.native.FamilyState
import io.github.chronosauros.contour.core.native.MoondropGen1Catalog
import io.github.chronosauros.contour.core.native.MoondropGen1Codec
import io.github.chronosauros.contour.core.native.MoondropGen1Session
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Moondrop DUSK-SP (gen 1, report 1 over the control pipe): the frames, the meta rounding, the coefficient sets against an independent port of FreeDSP-Studio's `compute_biquad`, the neutral
 * (factory) bands, the replies as the owner's unit sent them (read 11.11.2026), a whole write and its read-back on a fake cable that keeps meta records only (as the unit does), the firmware-preset
 * rules, the HID descriptor qualification and the gate.
 *
 * The coefficient vectors below were derived once by `android/tools/catalog/ref_biquad_gen1.py` (a line-by-line Python port of FreeDSP-Studio tools/app/src/dsp.rs `rbj` and `compute_biquad`, Rust's half-away `f64::round`
 * written out, run apart from Contour's `Dsp`); the codec takes the same numbers through [Dsp.biquad].
 */
class MoondropGen1Test {
    private val model = MoondropGen1Catalog.duskSp
    private val c = MoondropGen1Codec

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    private fun bytes(text: String) = text.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    /** The wire report of [text] (a hex string without the trailing zero padding) padded to 62 bytes. */
    private fun wire(text: String) = bytes(text).copyOf(62)
    private fun band(i: Int, type: FilterType, f: Double, g: Double, q: Double, enabled: Boolean = true) = Band("b$i", type, f, g, q, enabled)
    private val profile = listOf(band(0, FilterType.PEAK, 1000.0, 3.5, 1.41), band(1, FilterType.LOW_SHELF, 105.0, -4.5, 0.7), band(2, FilterType.HIGH_SHELF, 8000.0, 6.0, 1.0))

    // ---- frames ----

    @Test fun `read, select and commit frames are the 62-byte reports FreeDSP sends`() {
        assertEquals(hex(wire("01 00 0d 00 5a 01 00 23 2d b3 5a")), hex(c.presetQuery().report))
        assertEquals(hex(wire("01 00 0d 00 dd 01 00 23 2d b3 03")), hex(c.bandQuery(3).report))
        val select = c.selectSteps(3, 12)
        assertEquals(hex(wire("01 00 0d 00 5a 00 00 23 2d b3 5a 00 00 00 03")), hex(select[0].report))
        // a select is ONE frame: the commit would restart the cable onto preset 1 (run 2026-11-11-moondrop-dusk-04-mac), so it is never part of a select
        assertEquals(1, select.size)
        assertEquals(listOf(12L), select.map { it.delayAfterMs })
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 ff")), hex(c.writeSteps(model, c.plan(model, emptyList())).last().report))
        assertTrue(c.isRestartingCommit(c.writeSteps(model, c.plan(model, emptyList())).last().payload)); assertFalse(c.isRestartingCommit(c.presetQuery().payload))
        assertEquals(62, c.presetQuery().report.size)
        assertEquals(10, c.readQueries(9).size)
    }

    @Test fun `a write is 56 requests in FreeDSP's order with its gaps`() {
        val steps = c.writeSteps(model, c.plan(model, profile))
        assertEquals(56, steps.size)
        assertEquals(hex(wire("01 00 0d 00 5a 00 00 23 2d b3 5a 00 00 00 00")), hex(steps[0].report)) // the Custom EQ
        // band 1: PK 1000 Hz, Q 1.41 x 256 = 360.96 -> 361 = 0x169, gain 3.5 -> 4
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 00 00 00 00 01 00 00 00 e8 03 00 00 69 01 00 00 00 00 00 00 04")), hex(steps[1].report))
        // band 2: LS 105 Hz, Q 0.7 x 256 = 179.2 -> 179 = 0xb3, type 1, gain -4.5 -> -5 = 0xfffffb as 24 bits
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 00 00 00 00 02 00 00 00 69 00 00 00 b3 00 00 00 01 00 00 00 fb ff ff")), hex(steps[2].report))
        // band 3: HS 8000 Hz = 0x1f40, Q 1.0 = 256, type 2, gain 6
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 00 00 00 00 03 00 00 00 40 1f 00 00 00 01 00 00 02 00 00 00 06")), hex(steps[3].report))
        // an unused band is the factory band of its position: band 4 PEAK 2000 Hz Q 1.0 at 0 dB
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 00 00 00 00 04 00 00 00 d0 07 00 00 00 01")), hex(steps[4].report))
        // 9 meta records, then 5 rates x 9 bands of coefficients (band by band, the rates in order), then the commit
        assertTrue(steps.subList(1, 10).all { it.what.endsWith("meta") })
        assertEquals(listOf(4, 5, 6, 7, 8, 4, 5, 6, 7, 8), steps.subList(10, 20).map { c.dword(it.payload, 10) })
        assertEquals(List(10) { (it / 5) + 1 }, steps.subList(10, 20).map { c.dword(it.payload, 14) })
        assertTrue(steps.subList(10, 55).all { c.dword(it.payload, 18) == 3 })
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 ff")), hex(steps[55].report))
        assertEquals(List(55) { model.writeGapMs } + model.afterCommitMs, steps.map { it.delayAfterMs })
        assertEquals(6L, model.writeGapMs); assertEquals(10L, model.afterCommitMs)
        assertEquals(10 + steps.size, c.plannedRequests(model)); assertEquals(1 + 10, c.plannedRequestsAfterReturn(model))
    }

    private fun coefficientFrame(steps: List<MoondropGen1Codec.Frame>, band: Int, rateIndex: Int): List<Int> = steps[10 + (band - 1) * 5 + rateIndex].let { f ->
        listOf(c.dword(f.payload, 10), c.dword(f.payload, 14), c.dword(f.payload, 18)) + (0 until 5).map { c.dword(f.payload, 22 + 4 * it) }
    }

    @Test fun `the coefficient sets equal an independent port of FreeDSP's compute_biquad`() {
        val steps = c.writeSteps(model, c.plan(model, profile))
        // PK 1000 Hz +3.5 dB Q 1.41 (band 1)
        assertEquals(listOf(4, 1, 3, 4276595, -7975311, 3780354, 7975311, -3862644), coefficientFrame(steps, 1, 0))
        assertEquals(listOf(5, 1, 3, 4270191, -8013609, 3812567, 8013609, -3888454), coefficientFrame(steps, 1, 1))
        assertEquals(listOf(8, 1, 3, 4204130, -8347889, 4144876, 8347889, -4154702), coefficientFrame(steps, 1, 4))
        // LS 105 Hz -4.5 dB Q 0.7 (band 2): the exact gain, not the rounded -5 of the meta record
        assertEquals(listOf(4, 2, 3, 4180536, -8268051, 4088229, 8267809, -4074703), coefficientFrame(steps, 2, 0))
        assertEquals(listOf(6, 2, 3, 4187960, -8333034, 4145226, 8332982, -4138933), coefficientFrame(steps, 2, 2))
        // HS 8000 Hz +6 dB Q 1.0 (band 3)
        assertEquals(listOf(5, 3, 3, 6592063, -5238182, 1881950, 1818273, -859799), coefficientFrame(steps, 3, 1))
        assertEquals(listOf(8, 3, 3, 8104824, -14948997, 6935380, 7469499, -3366401), coefficientFrame(steps, 3, 4))
        // a neutral band is a 0 dB peak, whose coefficients are not zero (b1 = -a1, b2 = -a2 cancel): PK 300 Hz Q 1.0 (band 1 of an empty profile)
        val empty = c.writeSteps(model, c.plan(model, emptyList()))
        assertEquals(listOf(4, 1, 3, 4194304, -8205634, 4018831, 8205634, -4018831), coefficientFrame(empty, 1, 0))
        assertEquals(listOf(8, 1, 3, 4194304, -8367969, 4173766, 8367969, -4173766), coefficientFrame(empty, 1, 4))
    }

    @Test fun `the meta record is rounded half away from zero, the coefficients keep the exact gain`() {
        assertEquals(-3L, c.roundAway(-2.5)); assertEquals(3L, c.roundAway(2.5)); assertEquals(1L, c.roundAway(0.5)); assertEquals(-1L, c.roundAway(-0.5))
        assertEquals(0L, c.roundAway(-0.49999999999999994)); assertEquals(0L, c.roundAway(0.4)); assertEquals(-3L, c.roundAway(-3.4)); assertEquals(-4L, c.roundAway(-3.5))
        assertEquals(-3, c.meta(band(0, FilterType.PEAK, 1000.0, -2.5, 1.0)).gainDb)
        assertEquals(3, c.meta(band(0, FilterType.PEAK, 1000.0, 2.5, 1.0)).gainDb)
        assertEquals(12, c.meta(band(0, FilterType.PEAK, 1000.0, 11.5, 1.0)).gainDb)
        assertEquals(0, c.meta(band(0, FilterType.PEAK, 1000.0, 0.4, 1.0)).gainDb)
        assertEquals(1001, c.meta(band(0, FilterType.PEAK, 1000.5, 0.0, 1.0)).freqHz); assertEquals(1000, c.meta(band(0, FilterType.PEAK, 999.5, 0.0, 1.0)).freqHz)
        assertEquals(361, c.meta(band(0, FilterType.PEAK, 1000.0, 0.0, 1.41)).qRaw); assertEquals(51, c.meta(band(0, FilterType.PEAK, 1000.0, 0.0, 0.2)).qRaw)
        assertEquals(0xFFFFFF, c.gain24(-1)); assertEquals(0xFFFFF4, c.gain24(-12)); assertEquals(12, c.gain24(12))
        // 2.3 dB and 2.0 dB share a meta record and differ in the coefficients: the sound is the coefficients
        val a = c.plan(model, listOf(band(0, FilterType.PEAK, 1000.0, 2.3, 1.0)))[0]; val b = c.plan(model, listOf(band(0, FilterType.PEAK, 1000.0, 2.0, 1.0)))[0]
        assertEquals(a.meta, b.meta)
        assertFalse(a.coefficients[1].contentEquals(b.coefficients[1]))
    }

    @Test fun `a missing or disabled band is the factory band of its position, so an empty profile restores the factory bank`() {
        val factory = listOf(300, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500)
        assertEquals(factory, model.factoryFrequenciesHz)
        val empty = c.plan(model, emptyList())
        assertEquals(factory.map { MoondropGen1Codec.Meta(it, 256, 0, 0) }, empty.map { it.meta })
        val disabled = c.plan(model, listOf(band(0, FilterType.PEAK, 1000.0, 3.0, 1.0), band(1, FilterType.PEAK, 2000.0, 6.0, 1.0, enabled = false)))
        assertEquals(MoondropGen1Codec.Meta(1000, 256, 0, 3), disabled[0].meta)
        assertEquals(MoondropGen1Codec.Meta(1000, 256, 0, 0), disabled[1].meta) // the factory band 2: 1000 Hz, not the disabled band's 2000
        assertEquals(MoondropGen1Codec.Meta(1500, 256, 0, 0), disabled[2].meta)
        assertEquals(9, disabled.size)
    }

    // ---- replies, as the unit sent them (run 2026-11-11-moondrop-dusk-01-mac) ----

    private val presetReply = bytes("01 00 0d 00 5a 81 00 23 2d b3 5a 00 00 00 01 00 00 00").copyOf(62).copyOfRange(1, 62)
    private val band1Reply = bytes("01 00 06 00 dd 81 00 23 2d b3 05 00 00 00 01 00 00 00 2c 01 00 00 00 01 00 00 00 00 00 00 00 00 00").copyOf(62).copyOfRange(1, 62)
    private val band2Reply = bytes("01 00 06 00 dd 81 00 23 2d b3 05 00 00 00 02 00 00 00 e8 03 00 00 00 01 00 00 00 00 00 00 00 00 00").copyOf(62).copyOfRange(1, 62)

    @Test fun `the replies of the owner's unit parse`() {
        assertTrue(c.isPresetReply(presetReply)); assertEquals(1, c.parsePreset(presetReply))
        assertEquals(MoondropGen1Codec.RawBand(1, 300, 256, 0, 0), c.parseBand(band1Reply, 1))
        assertEquals(MoondropGen1Codec.RawBand(2, 1000, 256, 0, 0), c.parseBand(band2Reply, 2))
        // a stale reply of another band is not the answer; a preset reply is not a band reply; a short report is nothing
        assertFalse(c.isBandReply(band1Reply, 2)); assertFalse(c.isBandReply(presetReply, 1)); assertFalse(c.isPresetReply(band1Reply)); assertFalse(c.isWholeReply(band1Reply.copyOf(40)))
        // the gain is a signed int24 in whole dB at bytes 30-32, and the type a dword at 26
        val negative = band1Reply.copyOf().also { it[29] = 0xF4.toByte(); it[30] = 0xFF.toByte(); it[31] = 0xFF.toByte(); it[25] = 2 }
        assertEquals(MoondropGen1Codec.RawBand(1, 300, 256, 2, -12), c.parseBand(negative, 1))
    }

    // ---- a whole write on a fake cable that keeps what the unit keeps: meta records and the preset ----

    /** The cable, by literal wire offsets: preset (starts on 1 = DUSK-Default), nine meta records; replies echo the request with byte 5 | 0x80; coefficient sets and the commit are acknowledged and dropped. */
    private class Cable(var preset: Int = 1, var dropBand: Int? = null, var silentReads: Int = 0) : NativePort {
        override val deviceKey = "cable"
        val factory = intArrayOf(300, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500)
        val meta = Array(9) { intArrayOf(factory[it], 256, 0, 0) }
        val sent = ArrayList<ByteArray>()
        val pauses = ArrayList<Long>()
        var restarts = 0
        override fun guard() {}
        override fun pause(ms: Long) { pauses += ms }
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = 61
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("every frame of this cable is a request")
        private fun u(p: ByteArray, wire: Int) = p[wire - 1].toInt() and 255
        private fun d(p: ByteArray, wire: Int) = u(p, wire) or (u(p, wire + 1) shl 8) or (u(p, wire + 2) shl 16) or (u(p, wire + 3) shl 24)
        private fun put(out: ByteArray, wire: Int, v: Int) { for (k in 0 until 4) out[wire - 1 + k] = (v ushr (8 * k)).toByte() }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(1, id)
            sent += payload.copyOf()
            val out = payload.copyOf()
            out[4] = (u(payload, 5) or 0x80).toByte()
            val cmd = u(payload, 4); val flag = u(payload, 5)
            when {
                cmd == 0x5a && flag == 1 -> { if (silentReads-- > 0) throw IOException("Native request timed out or returned invalid state"); put(out, 14, preset) }
                cmd == 0xdd && flag == 1 -> { val n = d(payload, 10); out[1] = 6; put(out, 10, 5); put(out, 14, n); put(out, 18, meta[n - 1][0]); put(out, 22, meta[n - 1][1]); put(out, 26, meta[n - 1][2]); put(out, 30, meta[n - 1][3] and 0xFFFFFF) }
                cmd == 0x5a && flag == 0 -> preset = d(payload, 14)
                cmd == 0xdc && flag == 0 && d(payload, 10) == 0xff -> { preset = 1; restarts++ } // the commit stores the bank and restarts the cable onto preset 1
                cmd == 0xdc && flag == 0 && d(payload, 10) == 0 -> {
                    val n = d(payload, 14)
                    if (n != dropBand) { val g = d(payload, 30).let { if (it and 0x800000 != 0) it - 0x1000000 else it }; meta[n - 1] = intArrayOf(d(payload, 18), d(payload, 22), d(payload, 26), g) }
                }
            }
            if (!matches(out)) throw IOException("Native request timed out or returned invalid state")
            return out
        }
    }

    @Test fun `a write ends pending at the commit, and the RAM select and the read after the restart prove it`() {
        val cable = Cable(preset = 1)
        val session = MoondropGen1Session(model, cable)
        val r = session.write(profile, explicitHold = true)
        assertFalse(r.verified); assertTrue(r.pending); assertNull(r.readback)
        assertEquals(1, session.lastBefore?.activeIndex)  // found on DUSK-Default
        assertEquals(10 + 56, cable.sent.size)            // the read before and the 56 requests, nothing after the commit
        assertEquals(1, cable.restarts); assertEquals(1, cable.preset) // the commit restarted the cable onto preset 1
        assertEquals(hex(wire("01 00 0d 00 dc 00 00 23 2d b3 ff")), hex(byteArrayOf(1) + cable.sent.last())) // the last frame is the commit
        val before = cable.sent.size
        val v = session.verifyAfterReturn(requireNotNull(session.lastPlanned))
        assertTrue(v.verified, v.reason); assertNull(v.reason)
        assertEquals(0, v.readback?.activeIndex)          // the RAM select put it on the Custom EQ
        assertEquals(listOf(MoondropGen1Codec.Meta(1000, 361, 0, 4), MoondropGen1Codec.Meta(105, 179, 1, -5), MoondropGen1Codec.Meta(8000, 256, 2, 6)), v.readback!!.bands.take(3).map { it.meta })
        assertEquals(MoondropGen1Codec.Meta(2000, 256, 0, 0), v.readback!!.bands[3].meta)
        // after the return: the select `5a 00 [5a, 0]` ALONE (no commit), then the nine bands and the preset
        assertEquals(before + 1 + 10, cable.sent.size)
        assertEquals(hex(wire("01 00 0d 00 5a 00 00 23 2d b3 5a 00 00 00 00")), hex(byteArrayOf(1) + cable.sent[before]))
        assertEquals(1, cable.restarts, "the select after the return sent no commit")
        cable.sent.forEach { c.requireSendable(it) }
        // never faster than the record's gaps: every write is followed by its pause (6 ms; 10 ms after the commit), the select 12 ms
        assertEquals(List(55) { 6L } + 10L + 12L, cable.pauses.filter { it > 0 })
    }

    @Test fun `writeAndVerify is the whole write for a transport that survives the restart`() {
        val cable = Cable(preset = 1)
        val r = MoondropGen1Session(model, cable).writeAndVerify(profile, explicitHold = true)
        assertTrue(r.verified, r.reason); assertEquals(0, r.readback?.activeIndex)
        assertEquals(10 + 56 + 1 + 10, cable.sent.size)
    }

    @Test fun `a write whose band does not read back is reported after the restart and names the band`() {
        val cable = Cable(dropBand = 3)
        val r = MoondropGen1Session(model, cable).writeAndVerify(profile, explicitHold = true)
        assertFalse(r.verified)
        assertTrue("band 3" in r.reason.orEmpty(), r.reason)
        // a cable that came back on the power-up preset and was not selected onto the Custom EQ is not verified either
        val planned = MoondropGen1Codec.plan(model, profile)
        assertTrue(MoondropGen1Codec.mismatches(planned, 1, planned.map { MoondropGen1Codec.RawBand(it.index, it.meta.freqHz, it.meta.qRaw, it.meta.type, it.meta.gainDb) }, model.customIndex).single().contains("preset 1"))
    }

    @Test fun `a silent cable is asked again for a read and nothing is written to it`() {
        val noisy = Cable(silentReads = 2)
        assertEquals(1, MoondropGen1Session(model, noisy).read().activeIndex)
        val dead = Cable(silentReads = 100)
        assertFailsWith<IOException> { MoondropGen1Session(model, dead).write(profile, explicitHold = true) }
        assertTrue(dead.sent.none { (it[4].toInt() and 255) == 0 || (it[3].toInt() and 255) == 0xdc }, "no write left before the opening read answered")
    }

    @Test fun `a preset select is the select frame and the read back, no commit, no restart`() {
        val cable = Cable(preset = 0)
        val session = MoondropGen1Session(model, cable)
        assertEquals(3, session.selectPreset(3, explicitUserAction = true))
        assertEquals(listOf(wire("01 00 0d 00 5a 00 00 23 2d b3 5a 00 00 00 03"), wire("01 00 0d 00 5a 01 00 23 2d b3 5a")).map { hex(it) },
            cable.sent.map { hex(byteArrayOf(1) + it) })
        assertEquals(listOf(12L), cable.pauses)
        assertEquals(0, cable.restarts); assertEquals(3, cable.preset)
        assertEquals(0, session.selectPreset(0, explicitUserAction = true)) // PLAY CUSTOM EQ
        assertFailsWith<IllegalArgumentException> { session.selectPreset(9, explicitUserAction = true) }
        assertFailsWith<IllegalArgumentException> { session.selectPreset(1, explicitUserAction = false) }
    }

    // ---- what the app may offer ----

    @Test fun `ranges and the shelf Q ceiling are refused before a frame is sent`() {
        fun issues(vararg b: Band) = c.issues(model, b.toList())
        assertEquals(emptyList(), issues(band(0, FilterType.PEAK, 20.0, -12.0, 0.2), band(1, FilterType.PEAK, 20000.0, 12.0, 10.0), band(2, FilterType.LOW_SHELF, 105.0, 12.0, 4.0)))
        assertTrue(issues(band(0, FilterType.PEAK, 1000.0, 12.5, 1.0)).single().contains("gain"))
        assertTrue(issues(band(0, FilterType.PEAK, 1000.0, 0.0, 0.1)).single().contains("Q"))
        assertTrue(issues(band(0, FilterType.PEAK, 19.0, 0.0, 1.0)).single().contains("freq"))
        assertTrue(issues(band(0, FilterType.LOW_PASS, 1000.0, 0.0, 0.7)).single().contains("type unsupported"))
        // the RBJ shelf with slope S = Q has no real response above about Q 5 at +-12 dB: FreeDSP's formula gives NaN there, Contour refuses
        assertTrue(issues(band(0, FilterType.HIGH_SHELF, 8000.0, 12.0, 10.0)).single().contains("no real response"))
        assertEquals(emptyList(), issues(band(0, FilterType.HIGH_SHELF, 8000.0, 12.0, 5.0)))
        assertTrue(c.issues(model, List(10) { band(it, FilterType.PEAK, 1000.0, 0.0, 1.0) }).single().contains("10 bands"))
        assertTrue(issues(band(0, FilterType.PEAK, 1000.0, 3.0, 1.0, enabled = false).copy(gainDb = 99.0)).isEmpty(), "a disabled band is not sent, so its values are not checked")
    }

    @Test fun `the target is gen 1, has no preamp, and writes in every build (hardware verified)`() {
        val t = DeviceTarget.MOONDROP_DUSK
        assertEquals(DeviceTarget.Family.MOONDROP_GEN1, t.family)
        assertTrue(t.isFamily); assertFalse(t.hasPreamp); assertEquals(9, t.caps.bands)
        assertEquals(setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), t.caps.types)
        assertEquals(-12.0, t.caps.gainMinDb); assertEquals(12.0, t.caps.gainMaxDb); assertEquals(0.2, t.caps.qMin); assertEquals(10.0, t.caps.qMax)
        assertEquals(DeviceTarget.findDevice(0x35D8, 0x1499, "DUSK-SP"), t)
        assertNull(DeviceTarget.findDevice(0x35D8, 0x1499, "DUSK-SP2")); assertNull(DeviceTarget.findDevice(0x35D8, 0x1499, null)); assertNull(DeviceTarget.find(0x35D8, 0x1499))
        assertTrue(model.hardwareVerified) // a phone wrote and read back the Custom EQ, run 2026-11-11-moondrop-dusk-09-phone
        Proven.asChannel(BuildChannel.RELEASE) {
            assertFalse(t.readOnly)
            assertEquals(emptyList(), t.issues(Profile("p", "p", bands = profile, createdAt = 0, updatedAt = 0)))
        }
        Proven.asChannel(BuildChannel.PERF) {
            assertFalse(t.readOnly)
            assertEquals(emptyList(), t.issues(Profile("p", "p", bands = profile, createdAt = 0, updatedAt = 0)))
        }
        assertTrue(DeviceTarget.MOONDROP_DUSK in DeviceTarget.FAMILIES, "FAMILIES holds the families that write in every build")
    }

    @Test fun `the preset row offers the Custom EQ and the five firmware presets, written in every build`() {
        val row = DeviceTarget.MOONDROP_DUSK.settings.single()
        assertEquals(FamilySettings.PRESET, row.id)
        assertEquals(listOf("CUSTOM EQ", "DUSK-DEFAULT", "DUSK-V", "DUSK-HARMAN", "DUSK-BASS+", "DUSK-DIFFUSE-TILTED"), row.options)
        Proven.asChannel(BuildChannel.RELEASE) { assertTrue(row.writeEnabled) }
        Proven.asChannel(BuildChannel.PERF) { assertTrue(row.writeEnabled) }
        assertTrue(row.inlineChoice, "the DAC page lists the presets as buttons on the page"); assertTrue(model.stockPickerInline)
    }

    // ---- the state the app shows ----

    private fun snapshot(preset: Int, planned: List<MoondropGen1Codec.BandWrite>) =
        MoondropGen1Codec.Snapshot(model, preset, planned.map { MoondropGen1Codec.RawBand(it.index, it.meta.freqHz, it.meta.qRaw, it.meta.type, it.meta.gainDb) })

    @Test fun `on a firmware preset the bank read is not what plays`() {
        val p = Profile("p", "p", bands = profile, createdAt = 0, updatedAt = 0)
        val planned = c.plan(model, profile)
        val onCustom = FamilyState.Dusk(model, snapshot(0, planned))
        assertTrue(onCustom.matches(p)); assertTrue(onCustom.holds(p)); assertTrue(onCustom.importable); assertNull(onCustom.presetBands); assertNull(onCustom.importName)
        val onPreset = FamilyState.Dusk(model, snapshot(1, planned)) // the Custom bank holds the profile, but the cable plays DUSK-Default
        assertFalse(onPreset.matches(p)); assertFalse(onPreset.holds(p)) // the profile is not DUSK-Default
        // the foot button names the cable's preset in capitals, from the record's labels (no notice text: owner 11.11.2026)
        assertEquals("DUSK-DEFAULT", onPreset.footLabel); assertEquals("CUSTOM EQ", onCustom.footLabel); assertEquals(1, model.powerUpPreset)
        assertEquals(listOf("CUSTOM EQ", "DUSK-DEFAULT", "DUSK-V", "DUSK-HARMAN", "DUSK-BASS+", "DUSK-DIFFUSE-TILTED"), (0..5).map { FamilyState.Dusk(model, snapshot(it, planned)).footLabel })
        assertEquals(listOf(1), onPreset.readings().map { it.value }); assertEquals(FamilySettings.PRESET, onPreset.readings().single().id)
        // a gain that differs only below the meta record's whole dB still matches (the comparison is on the meta, which is all the cable can say)
        val exact = Profile("p", "p", bands = listOf(band(0, FilterType.PEAK, 1000.0, 3.6, 1.41), profile[1], profile[2]), createdAt = 0, updatedAt = 0)
        assertTrue(onCustom.matches(exact))
        // a band that reads back differently is not the profile
        assertFalse(onCustom.matches(Profile("p", "p", bands = listOf(band(0, FilterType.PEAK, 1001.0, 3.5, 1.41), profile[1], profile[2]), createdAt = 0, updatedAt = 0)))
    }

    // Crinacle's published values (https://crinacle.com/2024/04/10/moondrop-x-crinacle-dusk-eq-dsp-values/, FreeDSP-Studio app.rs STARTING), typed here independently of the record: Hz, dB, Q per cable index.
    private val published = mapOf(
        1 to listOf(1400.0 to (-3.0 to 0.8), 5400.0 to (-3.0 to 2.0), 14000.0 to (-5.0 to 2.0)),
        2 to listOf(250.0 to (-3.0 to 0.4), 1500.0 to (-6.0 to 0.6), 5400.0 to (-3.0 to 2.0), 14000.0 to (-5.0 to 2.0)),
        3 to listOf(80.0 to (-2.0 to 0.2), 400.0 to (-3.0 to 0.4), 1600.0 to (-4.0 to 1.2), 14000.0 to (-10.0 to 2.0)),
        4 to listOf(140.0 to (1.0 to 0.4), 200.0 to (-1.0 to 3.0), 300.0 to (-6.0 to 0.4), 1500.0 to (-7.0 to 0.6), 5400.0 to (-3.0 to 2.5), 8000.0 to (-5.0 to 0.4), 14000.0 to (-7.0 to 2.0)),
        5 to listOf(65.0 to (-7.0 to 0.7), 1500.0 to (-5.0 to 0.4), 2900.0 to (3.0 to 2.5), 6000.0 to (-2.0 to 3.0), 14000.0 to (-10.0 to 2.0)),
    )

    @Test fun `what plays is the preset's table on a firmware preset and the Custom bank on the Custom EQ`() {
        val bank = c.plan(model, profile) // what the Custom bank holds, whatever the cable plays
        for ((index, table) in published) {
            val playing = model.presetBands(index)
            assertNotNull(playing, "preset $index")
            assertEquals(table.map { (f, gq) -> listOf(FilterType.PEAK, f, gq.first, gq.second) }, playing.map { listOf(it.type, it.freqHz, it.gainDb, it.q) }, "table of preset $index")
            val state = FamilyState.Dusk(model, snapshot(index, bank))
            assertTrue(state.importable); assertEquals(model.presetName(index), state.importName)
            // FROM DAC / the editor open the table, not the Custom bank the read returned
            val eq = state.importExact()
            assertEquals(playing.map { listOf(it.freqHz, it.gainDb, it.q) }, eq.bands.map { listOf(it.freqHz, it.gainDb, it.q) })
            assertEquals(0.0, eq.preampDb)
            // a library profile with exactly those bands (any order, 0 dB and disabled bands aside) is what plays: ON DAC; the bank's own profile is not
            val same = Profile("p$index", "p", bands = eq.bands.reversed() + band(9, FilterType.PEAK, 3000.0, 0.0, 1.0) + band(10, FilterType.PEAK, 100.0, 4.0, 1.0, enabled = false), createdAt = 0, updatedAt = 0)
            assertTrue(state.holds(same)); assertTrue(state.matches(same))
            assertFalse(state.holds(Profile("bank", "bank", bands = profile, createdAt = 0, updatedAt = 0)))
            assertFalse(state.holds(same.copy(bands = same.bands.drop(1)))) // a band short
            assertFalse(state.holds(same.copy(bands = same.bands.mapIndexed { i, b -> if (i == 0) b.copy(gainDb = b.gainDb + 1.0) else b }))) // a gain 1 dB off
            // no other preset's profile matches this one
            for (other in published.keys - index) assertFalse(state.holds(Profile("o", "o", bands = model.presetBands(other)!!, createdAt = 0, updatedAt = 0)), "preset $other on $index")
        }
        // the Custom EQ: the bank is what plays (unchanged); the table is not used
        val custom = FamilyState.Dusk(model, snapshot(0, bank))
        assertNull(custom.presetBands); assertTrue(custom.holds(Profile("p", "p", bands = profile, createdAt = 0, updatedAt = 0)))
        assertFalse(custom.holds(Profile("d", "d", bands = model.presetBands(1)!!, createdAt = 0, updatedAt = 0)))
        assertNull(model.presetBands(0)); assertNull(model.presetBands(6))
        // a preset the record does not list is only shown
        val unknown = FamilyState.Dusk(model, snapshot(6, bank))
        assertFalse(unknown.importable); assertFailsWith<IllegalArgumentException> { unknown.importExact() }
        // every table band is inside what the cable takes, so the profile made from a preset can be sent
        for (index in published.keys) assertTrue(c.issues(model, model.presetBands(index)!!).isEmpty(), "preset $index is sendable")
    }

    @Test fun `FROM DAC opens the meta records and leaves the trailing factory bands out`() {
        val eq = FamilyState.Dusk(model, snapshot(0, c.plan(model, profile))).importExact()
        assertEquals(3, eq.bands.size)
        assertEquals(listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), eq.bands.map { it.type })
        assertEquals(listOf(1000.0, 105.0, 8000.0), eq.bands.map { it.freqHz })
        assertEquals(listOf(4.0, -5.0, 6.0), eq.bands.map { it.gainDb }) // whole dB: the meta record
        assertEquals(361 / 256.0, eq.bands[0].q)
        // the factory Custom bank (nine PEAK bands at 0 dB) opens as one band at the first position's frequency and Q, as a send writes it back
        assertEquals(1, FamilyState.Dusk(model, snapshot(0, c.plan(model, emptyList()))).importExact().bands.size)
        assertEquals(0.0, FamilyState.Dusk(model, snapshot(0, c.plan(model, emptyList()))).importExact().bands.single().gainDb)
    }

    // ---- the HID report descriptor ----

    private val descriptor = bytes("05 0c 09 01 a1 01 85 01 15 00 26 ff 00 09 00 75 08 95 3d 91 02 09 00 81 02 c0 05 0c 09 01 a1 01 85 02 15 00 25 01 75 01 95 02 09 e9 09 ea 81 02 95 02 09 cd 09 cf 81 02 95 04 09 00 81 01 c0")

    @Test fun `the descriptor read on the unit qualifies by its own rule and not by the vendor-page rule`() {
        val shape = NativeHidReports.parseGen1(descriptor)
        shape.qualifyGen1()
        assertEquals(62, shape.rawSize(1, NativeHidReports.Kind.INPUT)); assertEquals(62, shape.rawSize(1, NativeHidReports.Kind.OUTPUT)); assertEquals(61, shape.payloadSize(1, NativeHidReports.Kind.OUTPUT))
        // every other family still parses vendor pages only: the Consumer-page report 1 is not seen, so no other family could open this interface
        assertFailsWith<IllegalStateException> { NativeHidReports.parse(descriptor, undefinedDesktop = true).rawSize(1, NativeHidReports.Kind.OUTPUT) }
        assertEquals(62, NativeHidReports.minimumRawBytes(DeviceTarget.MOONDROP_DUSK, NativeHidReports.Kind.INPUT))
        // a report of another size, or a missing Output, is refused
        val short = descriptor.copyOf().also { it[descriptor.indexOf(0x3d.toByte())] = 0x3c }
        assertTrue(assertFailsWith<IllegalArgumentException> { NativeHidReports.parseGen1(short).qualifyGen1() }.message.orEmpty().contains("exactly 62"))
        val noOutput = bytes("05 0c 09 01 a1 01 85 01 15 00 26 ff 00 09 00 75 08 95 3d 81 02 c0")
        assertFailsWith<IllegalStateException> { NativeHidReports.parseGen1(noOutput).qualifyGen1() }
        // a vendor-page report 1 of 62 bytes is not this protocol's descriptor in a family of its own either: parseGen1 reads consumer pages only through the same shape
        assertNotNull(NativeHidReports.parseGen1(descriptor).rawSizes[NativeHidReports.Key(2, NativeHidReports.Kind.INPUT)])
    }
}
