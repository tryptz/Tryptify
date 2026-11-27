package io.github.chronosauros.contour.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The project's only automated check: the device protocol and the text codecs against real captures.
 * Inputs (read-only): research/protocol/ (device read 25.09.2026, walkplay.py dry runs) and the
 * maintainer's profiles in a sibling eq-library folder. That folder is not part of the repo: without it
 * tests 4-6 are skipped and 1-3 still check the protocol.
 */
class ProtocolParityTest {
    @Test
    fun `Protocol Max discovery is strict`() {
        assertEquals(DeviceProtocol.MAX, DeviceProtocol.find(0x3302, 0x43CC))
        assertNull(DeviceProtocol.find(0x262A, 0x0001))
        assertNull(DeviceProtocol.find(0x3302, 0x43CD))
        assertEquals(DeviceProtocol.MICRO, DeviceProtocol.find(0x3302, 0xC20F))
        // The TRN Black Pearl (43E8) is the third entry; other SchemeNo16 products stay unsupported.
        assertEquals(DeviceProtocol.TRN, DeviceProtocol.find(0x3302, 0x43E8))
        assertNull(DeviceProtocol.find(0x3302, 0x43E9))
    }

    @Test
    fun `Micro dispatch is exactly the 1_2_2 code`() {
        val m = DeviceProtocol.MICRO
        val hs = Band("hs", FilterType.HIGH_SHELF, 4000.0, 3.0, 0.71)
        val pk = Band("pk", FilterType.PEAK, 120.0, 4.0, 1.2)
        for (pre in listOf(null, -4.0)) {
            val profile = Profile("p", "p", bands = listOf(pk, hs), preampDb = pre, createdAt = 0, updatedAt = 0)
            assertEquals(ProtocolMicro.plan(profile), m.plan(profile))
            assertEquals(
                ProtocolMicro.devicePreamp(profile.bands, pre) - ProtocolMicro.highShelfGainSum(profile.bands),
                m.shownPreamp(profile),
            )
        }
        assertEquals(ProtocolMicro.flatPlan(), m.flatPlan())
        assertEquals(8, m.caps.bands)
        assertTrue(m.supportsAb && !m.experimental)
        // 1.2.2 receive predicates: no length or direction hardening for the Micro.
        val shortVersion = WalkPlay.report(0x80, 0x0C, 0).copyOf(3)
        assertEquals(WalkPlay.isReply(shortVersion, WalkPlay.CMD_VERSION), m.isReply(shortVersion, WalkPlay.CMD_VERSION))
        val echo = WalkPlay.bandWriteReport(WalkPlay.factoryFlat(0), 0)
        assertEquals(WalkPlay.isReply(echo, WalkPlay.CMD_PEQ), m.isSlotReply(echo))
        assertEquals(WalkPlay.parseBand(echo).registers, m.parseBand(echo, 0).registers)
    }

    @Test
    fun `Protocol Max ten bands native shelves and complete deterministic spare slots`() {
        val max = DeviceProtocol.MAX
        // 8 kHz: a positive native shelf below about 5.5 kHz (Q 0.71, +3 dB) does not fit signed Q30: the Max sends it wrapped to int32 (devicePEQ's bytes; the unit plays the registers, dac-lab run
        // 2026-11-10-crinear-protocol-max-16) since ProtocolMax.CANDIDATES_VERIFIED, and without that the planner refuses it as before.
        val hs = Band("hs", FilterType.HIGH_SHELF, 8000.0, 3.0, 0.71)
        assertTrue(max.plan(listOf(hs.copy(freqHz = 4000.0)), -4.0) is DevicePlan.Ready)
        val refused = max.plan(listOf(hs.copy(freqHz = 4000.0)), -4.0, ProtocolMax.capabilities(passFilters = true, wideGain = true, wrapQ30 = false)) as DevicePlan.Rejected
        assertEquals(listOf("Max band 1 cannot be represented safely. Reduce the boost, raise the shelf frequency or adjust Q."), refused.issues)
        // a shelf with no finite biquad at all (a low shelf of -10 dB at the top of the shelf Q range) is blocked, wrapped or not
        assertTrue(max.plan(listOf(Band("ls", FilterType.LOW_SHELF, 100.0, -10.0, 7.07)), -4.0) is DevicePlan.Rejected)
        // AUTO that needs more than -30 dB is refused with the number (it used to be sent as -30); an explicit -30 still sends.
        val stacked = List(5) { Band("p$it", FilterType.PEAK, 1000.0, 10.0, 0.7) }
        val tooLow = "this EQ needs -50 dB; Contour supports down to -30 dB for this device. Reduce the combined boosts."
        assertEquals(listOf(tooLow), (max.plan(stacked, null) as DevicePlan.Rejected).issues)
        assertEquals(listOf(tooLow), (ProtocolMicro.plan(stacked, null) as DevicePlan.Rejected).issues)
        assertEquals(-30, (max.plan(stacked, -30.0) as DevicePlan.Ready).preampDb)
        assertTrue((max.plan(stacked, -40.0) as DevicePlan.Rejected).issues.single().startsWith("Preamp -40 dB outside -30..0"))
        val plan = max.plan(List(10) { hs.copy(id = "$it") }, -4.0) as DevicePlan.Ready
        assertEquals(10, plan.bands.size)
        assertEquals(9, plan.bands.last().index)
        assertEquals(WalkPlay.TYPE_HSQ, plan.bands.last().typeCode)
        assertEquals(-4, plan.preampDb)
        assertEquals(9.toByte(), WalkPlay.bandWriteReport(plan.bands.last(), 101)[5])
        assertTrue(max.plan(List(11) { hs }, -4.0) is DevicePlan.Rejected)
        assertTrue(max.plan(listOf(hs.copy(q = Double.NaN)), -4.0) is DevicePlan.Rejected)
        assertTrue(max.plan(listOf(hs.copy(gainDb = Double.POSITIVE_INFINITY)), -4.0) is DevicePlan.Rejected)
        assertTrue(max.plan(listOf(hs), Double.NaN) is DevicePlan.Rejected)
        assertTrue(max.plan(listOf(hs.copy(q = 10.0, gainDb = 10.0)), null) is DevicePlan.Rejected)
        val short = max.plan(listOf(hs), -4.0) as DevicePlan.Ready
        assertEquals(10, short.bands.size)
        assertEquals(0.0, short.bands[9].gainDb)
        assertEquals(max.flatPlan().bands[9].registers(), short.bands[9].registers())
        assertEquals(3.0, plan.bands[0].gainDb)
        assertEquals(8, ProtocolMicro.flatPlan().bands.size)
        assertEquals(WalkPlay.TYPE_LSQ, (ProtocolMicro.plan(listOf(hs), -4.0) as DevicePlan.Ready).bands[0].typeCode)
    }

    private fun maxReply(w: WalkPlay.BandWrite, slot: Int = 0): ByteArray =
        WalkPlay.bandWriteReport(w, slot).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }

    @Test
    fun `Protocol Max parser rejects wrong direction length index type and malformed slot`() {
        val p = DeviceProtocol.MAX
        val good = maxReply(p.flatPlan().bands[9])
        assertEquals(9, p.parseBand(good, 9).registers.index)
        assertFailsWith<IllegalArgumentException> { p.parseBand(good.copyOf(36), 9) }
        assertFailsWith<IllegalArgumentException> { p.parseBand(good.copyOf().also { it[1] = 1 }, 9) }
        assertFailsWith<IllegalArgumentException> { p.parseBand(good.copyOf().also { it[0] = 0x03 }, 9) }
        assertFailsWith<IllegalArgumentException> { p.parseBand(good, 8) }
        assertFailsWith<IllegalArgumentException> { p.parseBand(good.copyOf().also { it[34] = 6 }, 9) }   // 1..5 are the codes a unit can hold (the TRN's tolerance)
        assertFailsWith<IllegalArgumentException> { p.parseSlot(good) }
        assertFailsWith<IllegalArgumentException> { p.parseSlot(good.copyOf(10)) }
        assertEquals(101, p.parseSlot(maxReply(p.flatPlan().bands[0], 101)))
        assertFailsWith<IllegalArgumentException> { p.parsePreamp(WalkPlay.report(0x01, 0x03, 0x02, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { p.parseVersion(WalkPlay.report(0x80, 0x0C, 0)) }
    }

    @Test
    fun `Protocol Max exact import and matching include the tenth slot`() {
        val p = DeviceProtocol.MAX
        val bands = List(10) { Band("b$it", FilterType.PEAK, 1000.0 + it * 100, -1.0, 0.75) }.toMutableList()
        bands[9] = bands[9].copy(type = FilterType.HIGH_SHELF, freqHz = 8000.0, gainDb = 3.0) // 1.9 kHz +3 dB no longer fits Q30
        val plan = p.plan(bands, -5.0) as DevicePlan.Ready
        val reads = plan.bands.map { p.parseBand(maxReply(it), it.index) }
        val imported = p.importExact(reads, plan.preampDb) as DacImport.Ready
        assertEquals(10, imported.eq.bands.size)
        assertEquals(FilterType.HIGH_SHELF, imported.eq.bands[9].type)
        assertTrue(p.matches(p.plan(imported.eq.bands, imported.eq.preampDb) as DevicePlan.Ready, reads.map { it.registers }, -5))
        assertTrue(!p.matches(plan, reads.dropLast(1).map { it.registers }, -5))
        assertTrue(!p.matches(plan, reads.mapIndexed { i, b -> if (i == 9) b.registers.copy(gain256 = 0) else b.registers }, -5))
        assertTrue(p.importExact(reads.dropLast(1), -5) is DacImport.Rejected)
        val flat = p.flatPlan().bands.map { p.parseBand(maxReply(it), it.index) }
        assertTrue(p.importExact(flat, 0) is DacImport.Ready)
    }

    private val repo = File(System.getProperty("contour.repoRoot") ?: error("contour.repoRoot not set"))
    private val protocol = File(repo, "research/protocol")
    private val library = File(repo.parentFile, "eq-library")

    /** Tests 4-6 need the maintainer's eq-library next to the repo; elsewhere they are skipped. */
    private fun needLibrary() = assumeTrue(library.isDirectory, "no eq-library next to the repo ($library) - skipped")

    private fun hex(s: String): ByteArray = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /** Log lines "  12.3 ms RX 4b ..." / "TX (dry-run, not sent) 4b ...": (time ms, bytes). */
    private fun reports(file: File, direction: String): List<Pair<Double, ByteArray>> =
        file.readLines().mapNotNull { line ->
            val m = Regex("""^\s*([\d.]+) ms $direction(?: \(dry-run, not sent\))? ((?:[0-9a-f]{2} ?)+)$""").find(line)
                ?: return@mapNotNull null
            m.groupValues[1].toDouble() to hex(m.groupValues[2])
        }

    private fun roundTo(x: Double, decimals: Int): Double {
        val p = Math.pow(10.0, decimals.toDouble())
        return WalkPlay.jsRound(x * p) / p
    }

    private val backup: JsonObject by lazy {
        Json.parseToJsonElement(File(protocol, "device-backup-2026-09-25.json").readText()).jsonObject
    }

    private fun backupFilters() = backup["filters"]!!.jsonArray.map { it.jsonObject }

    /** The 8 band replies of the raw read (the bulk slot read answers with band 0 first; take the last 8). */
    private fun bandReplies(): List<ByteArray> {
        val rx = reports(File(protocol, "raw-read-2026-09-25.log"), "RX").map { it.second }
        return rx.filter { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) }.takeLast(8)
    }

    @Test
    fun `1 - decoding the device's band replies gives the backup JSON`() {
        val replies = bandReplies()
        val filters = backupFilters()
        assertEquals(8, replies.size)
        var ok = 0
        replies.forEachIndexed { i, buf ->
            val d = WalkPlay.parseBand(buf)
            val f = filters[i]
            val raw = f["raw"]!!.jsonObject
            assertEquals(f["index"]!!.jsonPrimitive.int, d.registers.index)
            assertEquals(f["enabled"]!!.jsonPrimitive.boolean, d.enabled)
            assertEquals(f["type"]!!.jsonPrimitive.content, ApoType.of(d.type))
            // The backup's freq_hz / q went through devicePEQ 0617f382's SchemeNo11 decompensation; Contour
            // reads the registers as CrinEar's tool does: whole Hz and Q rounded to 2 decimals.
            assertEquals(raw["freq"]!!.jsonPrimitive.int.toDouble(), d.freqHz)
            assertEquals(f["gain_db"]!!.jsonPrimitive.double, roundTo(d.gainDb, 2))
            assertEquals(roundTo(raw["q_x256"]!!.jsonPrimitive.int / 256.0, 2), d.q)
            assertEquals(raw["freq"]!!.jsonPrimitive.int, d.registers.freq)
            assertEquals(raw["q_x256"]!!.jsonPrimitive.int, d.registers.q256)
            assertEquals(raw["gain_x256"]!!.jsonPrimitive.int, d.registers.gain256)
            assertEquals(raw["type"]!!.jsonPrimitive.int, d.registers.typeCode)
            assertEquals(raw["slot_byte"]!!.jsonPrimitive.int, d.slotByte)
            assertEquals(raw["biquad_hex"]!!.jsonPrimitive.content, hex(d.biquad))
            ok++
        }
        println("PARITY 1 decode band replies = backup JSON: $ok/8")
        assertEquals(8, ok)
        // the rest of the read: version, slot, preamp
        val rx = reports(File(protocol, "raw-read-2026-09-25.log"), "RX").map { it.second }
        assertEquals(backup["firmware_version"]!!.jsonPrimitive.content, WalkPlay.parseVersion(rx.first { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }))
        assertEquals(backup["current_slot"]!!.jsonPrimitive.int, WalkPlay.parseSlot(rx.first { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) }))
        assertEquals(backup["preamp_db"]!!.jsonPrimitive.int, WalkPlay.parsePreamp(rx.first { WalkPlay.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) }))
    }

    @Test
    fun `2 - encoding the factory flat band of each slot gives the device's own bytes`() {
        val replies = bandReplies()
        var ok = 0
        for (i in 0 until 8) {
            val w = WalkPlay.factoryFlat(i)
            val report = WalkPlay.bandWriteReport(w, slot = 0)
            val reply = replies[i]
            // freq, Q, gain, type, 00, slot at buf[28..36]; biquad at buf[8..27]; index at buf[5]
            assertEquals(hex(reply.copyOfRange(28, 37)), hex(report.copyOfRange(28, 37)), "slot $i metadata")
            assertEquals(hex(reply.copyOfRange(8, 28)), hex(report.copyOfRange(8, 28)), "slot $i biquad")
            assertEquals(reply[5], report[5])
            assertEquals(WalkPlay.parseBand(reply).registers, w.registers())
            ok++
        }
        println("PARITY 2 factory flat encode = device bytes: $ok/8")
        assertEquals(8, ok)
    }

    /** write_test.py main() in --dry-run: modified state (band 1 gain -0.5 dB), then the original. */
    private fun dryRunSequence(commit: Boolean): List<WalkPlay.Step> {
        val orig = backupFilters().map {
            val r = it["raw"]!!.jsonObject
            WalkPlay.BandWrite(
                index = it["index"]!!.jsonPrimitive.int,
                freq = r["freq"]!!.jsonPrimitive.int.toDouble(),
                gainDb = r["gain_x256"]!!.jsonPrimitive.int / 256.0,
                q = r["q_x256"]!!.jsonPrimitive.int / 256.0,
                typeCode = r["type"]!!.jsonPrimitive.int,
            )
        }
        val g = backupFilters()[0]["raw"]!!.jsonObject["gain_x256"]!!.jsonPrimitive.int - 128
        val mod = listOf(orig[0].copy(gainDb = g / 256.0)) + orig.drop(1)
        val preamp = backup["preamp_db"]!!.jsonPrimitive.double
        val slot = backup["current_slot"]!!.jsonPrimitive.int
        return WalkPlay.writeSequence(mod, preamp, slot, commit) + WalkPlay.writeSequence(orig, preamp, slot, commit)
    }

    private fun checkLog(name: String, commit: Boolean): Pair<Int, Int> {
        val log = reports(File(protocol, name), "TX")
        val ours = dryRunSequence(commit)
        assertEquals(log.size, ours.size, "$name: number of reports")
        var same = 0
        ours.forEachIndexed { i, step ->
            assertEquals(hex(log[i].second), hex(step.report), "$name report ${i + 1}")
            same++
        }
        // pacing: within each step the gap in the log equals our pause (the log adds < 3 ms of overhead)
        val perStep = ours.size / 2
        for (half in 0..1) {
            for (k in 0 until perStep - 1) {
                val i = half * perStep + k
                val gap = log[i + 1].first - log[i].first
                assertTrue(gap - ours[i].delayAfterMs in -0.5..3.0, "$name gap after report ${i + 1}: $gap ms vs ${ours[i].delayAfterMs}")
            }
        }
        return same to log.size
    }

    @Test
    fun `3 - write and commit reports reproduce the walkplay_py dry runs byte for byte`() {
        val (v, vn) = checkLog("dry-run-volatile.log", commit = false)
        println("PARITY 3a dry-run-volatile.log reports reproduced: $v/$vn")
        val (d, dn) = checkLog("dry-run-devicepeq.log", commit = true)
        val commits = reports(File(protocol, "dry-run-devicepeq.log"), "TX").count { it.second[1].toInt() == WalkPlay.WRITE && it.second[2].toInt() != WalkPlay.CMD_PEQ && it.second[2].toInt() != WalkPlay.CMD_GLOBAL_GAIN }
        println("PARITY 3b dry-run-devicepeq.log reports reproduced: $d/$dn (commit reports $commits/$commits)")
        assertEquals(vn, v)
        assertEquals(dn, d)
    }

    @Test
    fun `A-B temporary bands contain only changed slots and TEMP_WRITE without preamp or flash`() {
        // Sample A: one cut, one boost, all remaining bands already at zero; DAC preamp stays -3 dB.
        val a = listOf(
            WalkPlay.BandWrite(0, 2000.0, -10.0, 179 / 256.0, WalkPlay.TYPE_PK),
            WalkPlay.BandWrite(1, 100.0, 2.0, 192 / 256.0, WalkPlay.TYPE_LSQ),
        ) + (2 until WalkPlay.BANDS).map { WalkPlay.factoryFlat(it) }
        val changed = a.filter { it.registers().gain256 != 0 }
        val toB = WalkPlay.temporaryBandSequence(changed.map { it.copy(gainDb = 0.0) }, slot = 7)
        val toA = WalkPlay.temporaryBandSequence(changed, slot = 7)
        val temp = WalkPlay.report(WalkPlay.WRITE, 0x0A, 0x04, 0, 0, 0xFF, 0xFF, 0)
        assertEquals(3, toB.size)
        assertEquals(3, toA.size)
        for (sequence in listOf(toB, toA)) {
            assertEquals(listOf(0x09, 0x09, 0x0A), sequence.map { it.report[2].toInt() and 0xFF })
            assertEquals(listOf(20L, 20L, 0L), sequence.map { it.delayAfterMs })
            assertEquals(hex(temp), hex(sequence.last().report))
            assertTrue(sequence.all { it.report.size == WalkPlay.REPORT_SIZE })
        }
        changed.forEachIndexed { i, original ->
            assertEquals(original.registers().copy(gain256 = 0), WalkPlay.parseBand(toB[i].report).registers)
            assertEquals(original.registers(), WalkPlay.parseBand(toA[i].report).registers)
            assertEquals(7, WalkPlay.parseSlot(toB[i].report))
            assertEquals(hex(WalkPlay.bandWriteReport(original, 7)), hex(toA[i].report))
        }
        assertEquals(hex(temp), hex(WalkPlay.temporaryBandSequence(emptyList(), 7).single().report))
        println("A/B SAMPLE A->B slot=7, preamp=-3 (not written); full 64-byte reports:")
        toB.forEachIndexed { i, step -> println("  ${i + 1}: ${hex(step.report)}; delay ${step.delayAfterMs} ms") }
    }

    @Test
    fun `4 - APO text round trip is lossless and Nightfall's auto preamp is -3 dB`() {
        needLibrary()
        val files = library.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.name.endsWith(".txt") } }
            .sortedBy { it.path }
        assertTrue(files.isNotEmpty(), "no profiles in $library")
        var ok = 0
        for (f in files) {
            val first = ApoText.parse(f.readText())
            assertTrue(first.bands.isNotEmpty(), "${f.name}: no bands")
            val text = ApoText.format(first.bands, first.preampDb ?: 0.0)
            val second = ApoText.parse(text)
            assertEquals(first, second, "${f.parentFile.name}/${f.name}")
            println("  ${f.parentFile.name}/${f.name}: ${first.bands.size} bands, preamp ${first.preampDb}, auto ${Preamp.auto(first.bands, ShelfForm.SLOPE)}")
            ok++
        }
        println("PARITY 4 APO parse -> format -> parse lossless: $ok/${files.size}")
        assertEquals(files.size, ok)

        val nightfallDir = File(library, "crinear-nightfall")
        val txt = ApoText.parse(nightfallDir.listFiles()!!.first { it.name.endsWith(".txt") }.readText())
        val json = EqByEarJson.parse(nightfallDir.listFiles()!!.first { it.name.endsWith(".json") }.readText())
        assertNull(json.preampDb, "preampAuto: true must read as auto")
        assertEquals(txt.bands, json.bands)
        val auto = Preamp.auto(json.bands, ShelfForm.SLOPE)
        println("PARITY 4 Nightfall auto preamp: $auto dB")
        assertEquals(-3, auto)
    }

    // ---- 5: HIGH SHELF emulation on the Protocol Micro -------------------------------------------------

    /** Magnitude in dB of a device biquad (the 20 Q30 bytes exactly as written) at [f], fs 96 kHz. */
    private fun q30Db(bytes: ByteArray, f: Double): Double {
        fun i32(k: Int) = (bytes[k].toInt() and 0xFF) or ((bytes[k + 1].toInt() and 0xFF) shl 8) or
            ((bytes[k + 2].toInt() and 0xFF) shl 16) or (bytes[k + 3].toInt() shl 24)
        val c = DoubleArray(5) { i32(it * 4) / 1073741824.0 } // b0 b1 b2 -a1 -a2
        val w = 2 * Math.PI * f / WalkPlay.DESIGN_FS
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a1 = -c[3]; val a2 = -c[4]
        val nr = b0 + b1 * Math.cos(w) + b2 * Math.cos(2 * w); val ni = -(b1 * Math.sin(w) + b2 * Math.sin(2 * w))
        val dr = 1 + a1 * Math.cos(w) + a2 * Math.cos(2 * w); val di = -(a1 * Math.sin(w) + a2 * Math.sin(2 * w))
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** The curve the device realises from a list of band writes plus a preamp (dB), on [freqs]. */
    private fun deviceCurve(writes: List<WalkPlay.BandWrite>, preamp: Double, freqs: DoubleArray): DoubleArray {
        val coeffs = writes.map { WalkPlay.computeIir(it.freq, it.gainDb, it.q, it.typeCode) }
        return DoubleArray(freqs.size) { i -> preamp + coeffs.sumOf { q30Db(it, freqs[i]) } }
    }

    /**
     * Max deviation from its mean of (emulated device curve - intended curve), 1 024 log points. Both curves
     * in the device's own maths (the Q30 biquads Contour writes): the intended one
     * with native HIGH SHELF biquads and the user's preamp, the emulated one = ProtocolMicro.plan.
     */
    private fun hsDeviation(bands: List<Band>, preampDb: Double?): Double {
        val plan = ProtocolMicro.plan(bands, preampDb) as DevicePlan.Ready
        assertTrue(plan.bands.none { it.typeCode == WalkPlay.TYPE_HSQ }, "a HIGH SHELF reached the wire")
        val freqs = Dsp.logFreqs(1024)
        val active = bands.filter { it.enabled }
        val intendedWrites = List(8) { if (it < active.size) WalkPlay.bandWrite(it, active[it]) else WalkPlay.factoryFlat(it) }
        val intended = deviceCurve(intendedWrites, preampDb ?: Preamp.auto(active, ShelfForm.SLOPE).toDouble(), freqs)
        val device = deviceCurve(plan.bands, plan.preampDb.toDouble(), freqs)
        val diff = DoubleArray(freqs.size) { device[it] - intended[it] }
        val mean = diff.average()
        return diff.maxOf { Math.abs(it - mean) }
    }

    @Test
    fun `5 - HIGH SHELF emulation keeps the curve shape exactly`() {
        needLibrary()
        val mega = ApoText.parse(File(library, "hisenior-mega5est").listFiles()!!.first { it.name.endsWith(".txt") }.readText())
        assertTrue(mega.bands.any { it.type == FilterType.HIGH_SHELF }, "Mega5EST has no HS band")
        val synthetic = listOf(
            Band("s1", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7),
            Band("s2", FilterType.PEAK, 3000.0, -3.0, 2.0),
            Band("s3", FilterType.LOW_SHELF, 100.0, 2.0, 0.7),
        )
        val dMega = hsDeviation(mega.bands, null)
        val dSyn = hsDeviation(synthetic, null)
        val dSynManual = hsDeviation(synthetic, -6.0)
        println("PARITY 5 HS emulation max deviation: Mega5EST %.2e dB, synthetic %.2e dB (manual preamp %.2e dB)".format(dMega, dSyn, dSynManual))
        // the preamp rules: AUTO over the device-domain curve, manual floor(preamp + sum HS)
        val synPlan = ProtocolMicro.plan(synthetic, -6.0) as DevicePlan.Ready
        assertEquals(-2, synPlan.preampDb, "manual -6 dB + HS +4 dB")
        println("  preamp: Mega5EST AUTO ${(ProtocolMicro.plan(mega.bands, null) as DevicePlan.Ready).preampDb} dB, synthetic AUTO ${(ProtocolMicro.plan(synthetic, null) as DevicePlan.Ready).preampDb} dB, synthetic manual -6 -> ${synPlan.preampDb} dB")
        assertTrue(dMega < 0.01 && dSyn < 0.01 && dSynManual < 0.01)
    }

    @Test
    fun `display shelves agree in absolute dB with reference Q30 over the grid and centres`() {
        val cases = listOf(
            Band("ls", FilterType.LOW_SHELF, 1000.0, 6.0, 0.7),
            Band("hs", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7),
            Band("pk", FilterType.PEAK, 3000.0, -3.0, 2.0),
            Band("boundary", FilterType.LOW_SHELF, 1000.0, 10.0, 6.8716),
        )
        for (band in cases) {
            val freqs = Dsp.logFreqs(512) + doubleArrayOf(band.freqHz, 500.0, 20_000.0)
            val actual = Dsp.bandDb(band, ShelfForm.SLOPE, Dsp.FreqGrid(freqs))
            val bytes = WalkPlay.computeIir(band.freqHz, band.gainDb, band.q, WalkPlay.typeCode(band.type))
            freqs.forEachIndexed { i, f ->
                assertEquals(q30Db(bytes, f), actual[i], 0.003, "${band.type} Q=${band.q} at $f Hz")
            }
        }
        assertEquals(5.163, Dsp.bandDb(cases[0], ShelfForm.SLOPE, Dsp.FreqGrid(doubleArrayOf(500.0)))[0], 0.02)
        assertEquals(3.753, Dsp.bandDb(cases[1], ShelfForm.SLOPE, Dsp.FreqGrid(doubleArrayOf(20_000.0)))[0], 0.02)
        assertEquals(-7, Preamp.auto(listOf(Band("auto", FilterType.LOW_SHELF, 1000.0, 6.0, 2.0)), ShelfForm.SLOPE))
    }

    @Test
    fun `fractional high shelf offset is only whole dB preamp quantisation`() {
        val band = Band("hs", FilterType.HIGH_SHELF, 8000.0, 2.5, 0.7)
        val native = WalkPlay.bandWrite(0, band)
        val nativeBytes = WalkPlay.computeIir(native.freq, native.gainDb, native.q, native.typeCode)
        val freqs = Dsp.logFreqs(512) + doubleArrayOf(8000.0, 20_000.0)
        for ((manual, expectedOffset) in listOf(null to 0.5, -6.0 to -0.5)) {
            val plan = ProtocolMicro.plan(listOf(band), manual) as DevicePlan.Ready
            val emulated = plan.bands[0]
            val emulatedBytes = WalkPlay.computeIir(emulated.freq, emulated.gainDb, emulated.q, emulated.typeCode)
            val nativePreamp = manual ?: Preamp.auto(listOf(band), ShelfForm.SLOPE).toDouble()
            for (f in freqs) {
                val difference = plan.preampDb + q30Db(emulatedBytes, f) - nativePreamp - q30Db(nativeBytes, f)
                assertEquals(expectedOffset, difference, 0.003, "preamp=$manual at $f Hz")
            }
        }
    }

    /** devicePEQ v0.20 computeIIRFilter + quantizer (graph.hangout.audio, 02.07.2026): the PK biquad for every type, designed for [rate] (the vendor's tool: 96 kHz). */
    private fun hangoutPkBiquad(freq: Double, gain: Double, q: Double, rate: Double = 96000.0): ByteArray {
        val a = Math.sqrt(Math.pow(10.0, gain / 20)); val w = freq * 6.283185307179586 / rate
        val sn = Math.sin(w) / (2 * q); val d4 = sn * a; val d5 = sn / a; val d6 = d5 + 1
        fun r(x: Double) = Math.round(x * 1073741824)
        val den = listOf(1.0, Math.cos(w) * -2 / d6, (1 - d5) / d6).map(::r)
        val num = listOf((d4 + 1) / d6, Math.cos(w) * -2 / d6, (1 - d4) / d6).map(::r)
        val out = ByteArray(20)
        listOf(num[0], num[1], num[2], -den[1], -den[2]).forEachIndexed { i, v ->
            val n = v.toInt(); for (k in 0..3) out[i * 4 + k] = ((n shr (8 * k)) and 0xFF).toByte()
        }
        return out
    }

    /** The vendor's formula at its own 96 kHz is still [WalkPlay.computeIir]'s default; only the Micro's and the Echo-A's records move the rate (peq.coefficientRateHz). */
    @Test
    fun `computeIir at the vendor's 96 kHz equals devicePEQ's bytes and the Micro's rate is 93750`() {
        assertEquals(93750.0, DeviceProtocol.MICRO.caps.coefficientRateHz)
        assertEquals(96000.0, WalkPlay.DESIGN_FS)
        for ((f, g, q) in listOf(Triple(6900.0, -5.0, 10.0), Triple(1000.0, 5.0, 2.0), Triple(20.0, -1.8, 0.5), Triple(16000.0, 3.0, 0.7))) {
            val vendor = hangoutPkBiquad(f, g, q)
            assertEquals(hex(vendor), hex(WalkPlay.computeIir(f, g, q, WalkPlay.TYPE_PK)), "$f Hz")
            assertEquals(hex(vendor), hex(WalkPlay.computeIir(f, g, q, WalkPlay.TYPE_PK, 96000.0)), "$f Hz explicit")
            assertEquals(hex(hangoutPkBiquad(f, g, q, 93750.0)), hex(WalkPlay.computeIir(f, g, q, WalkPlay.TYPE_PK, 93750.0)), "$f Hz at 93750")
            assertNotEquals(hex(vendor), hex(WalkPlay.computeIir(f, g, q, WalkPlay.TYPE_PK, 93750.0)), "$f Hz differs between the rates")
        }
    }

    @Test
    fun `bands reach the wire as set and read back like CrinEar's tool`() {
        // katetuotto's report 26.09: 6900 Hz set in Contour showed 7058 Hz on graph.hangout.audio, 10200 -> 10434.
        val bands = listOf(
            Band("a", FilterType.PEAK, 6900.0, -5.0, 10.0),
            Band("b", FilterType.PEAK, 10200.0, -4.0, 8.3),
            Band("c", FilterType.LOW_SHELF, 5000.0, -4.0, 0.71),
            Band("d", FilterType.PEAK, 20.0, -1.8, 0.5),
        )
        val plan = ProtocolMicro.plan(bands, null) as DevicePlan.Ready
        bands.forEachIndexed { i, b ->
            val report = WalkPlay.bandWriteReport(plan.bands[i], slot = 0)
            val read = WalkPlay.parseBand(report)
            assertEquals(b.freqHz, read.freqHz, "band $i freq")
            assertEquals(b.q, read.q, "band $i Q")
            assertEquals(b.gainDb, read.gainDb, "band $i gain")
            assertEquals(b.type, read.type, "band $i type")
            if (b.type == FilterType.PEAK) {
                // The Micro's DSP runs the bytes at 93 750 Hz (acoustic runs 24.10.2026), so Contour writes the vendor formula designed for that rate.
                assertEquals(hex(hangoutPkBiquad(b.freqHz, b.gainDb, b.q, 93750.0)), hex(report.copyOfRange(8, 28)), "band $i biquad")
            }
        }
        // A profile saved on graph.hangout.audio imports with the same numbers.
        val imported = ProtocolMicro.importExact(plan.bands.map { WalkPlay.parseBand(WalkPlay.bandWriteReport(it, 0)) }, plan.preampDb)
        assertTrue(imported is DacImport.Ready, "$imported")
        assertEquals(listOf(6900.0, 10200.0, 5000.0, 20.0), imported.eq.bands.map { it.freqHz })
    }

    @Test
    fun `exact DAC import reconstructs fractional raw registers including low frequency bins`() {
        val original = listOf(
            WalkPlay.BandWrite(0, 26.25, 257 / 256.0, 257 / 256.0, WalkPlay.TYPE_PK),
            WalkPlay.BandWrite(1, 31.25, -257 / 256.0, 257 / 256.0, WalkPlay.TYPE_LSQ),
        ) + (2 until WalkPlay.BANDS).map { WalkPlay.factoryFlat(it) }
        val decoded = original.map { WalkPlay.parseBand(WalkPlay.bandWriteReport(it, 0)) }
        assertEquals(257, decoded[0].registers.q256)
        assertEquals(257, decoded[0].registers.gain256)
        assertEquals(26, decoded[0].registers.freq)
        assertEquals(31, decoded[1].registers.freq)
        val imported = ProtocolMicro.importExact(decoded, -4)
        assertTrue(imported is DacImport.Ready, "$imported")
        val plan = ProtocolMicro.plan(imported.eq.bands, imported.eq.preampDb) as DevicePlan.Ready
        assertTrue(ProtocolMicro.matches(plan, decoded.map { it.registers }, -4), "$plan")
        assertEquals(257 / 256.0, imported.eq.bands[0].gainDb)
    }

    @Test
    fun `exact DAC import rejects unsupported native types and nonfactory trailing slots`() {
        val flat = List(WalkPlay.BANDS) { WalkPlay.factoryFlat(it) }
        fun decoded(writes: List<WalkPlay.BandWrite>) = writes.map { WalkPlay.parseBand(WalkPlay.bandWriteReport(it, 0)) }
        val factoryImport = ProtocolMicro.importExact(decoded(flat), 0)
        assertTrue(factoryImport is DacImport.Ready)
        assertEquals(1, factoryImport.eq.bands.size, "factory flat imports as an editable band")
        assertTrue(ProtocolMicro.matches(ProtocolMicro.plan(factoryImport.eq.bands, factoryImport.eq.preampDb) as DevicePlan.Ready,
            decoded(flat).map { it.registers }, 0))
        val late = flat.toMutableList().also { it[7] = WalkPlay.BandWrite(7, 1000.25, -1.0, 1.0, WalkPlay.TYPE_PK) }
        val lateImport = ProtocolMicro.importExact(decoded(late), -2)
        assertTrue(lateImport is DacImport.Ready, "$lateImport")
        assertEquals(8, lateImport.eq.bands.size, "factory slots before an active slot retain their positions")
        assertTrue(ProtocolMicro.matches(ProtocolMicro.plan(lateImport.eq.bands, lateImport.eq.preampDb) as DevicePlan.Ready,
            decoded(late).map { it.registers }, -2))
        val nativeHs = flat.toMutableList().also { it[0] = WalkPlay.BandWrite(0, 8000.0, 4.0, 0.7, WalkPlay.TYPE_HSQ) }
        assertTrue(ProtocolMicro.importExact(decoded(nativeHs), -4) is DacImport.Rejected)
        val impossible = flat.toMutableList().also { it[7] = WalkPlay.BandWrite(7, 19.0, 1.0, 1.0, WalkPlay.TYPE_PK) }
        assertTrue(ProtocolMicro.importExact(decoded(impossible), 0) is DacImport.Rejected)
        val disabled = decoded(flat).toMutableList().also {
            it[0] = it[0].copy(enabled = false, registers = it[0].registers.copy(freq = 0, q256 = 0))
        }
        assertTrue(ProtocolMicro.importExact(disabled, 0) is DacImport.Rejected)
    }

    private fun register(p: DeviceProtocol, bands: List<Band>, pre: Double?): Int? = (p.plan(bands, pre) as? DevicePlan.Ready)?.preampDb

    /**
     * Round 6: what is stored and sent is never louder than what the file, the typed number or AUTO asks for.
     * One device rule: register = floor(d + 1e-6) must be in -30..0 (0 < d < 1 sends 0, d >= 1 is refused).
     */
    @Test
    fun `preamp is rounded down and never louder than asked`() {
        val flat = listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 0.7))
        val hsUp = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7))
        val hsDown = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, -4.0, 0.7))
        val hs394 = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, 3.94, 0.7))
        val hs396 = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, 3.96, 0.7))
        val max = DeviceProtocol.MAX; val micro = DeviceProtocol.MICRO
        fun ok(p: DeviceProtocol, b: List<Band>, pre: Double) = p.plan(b, pre) is DevicePlan.Ready
        // (protocol, bands, file value, stored value, register or null when refused)
        class Case(val p: DeviceProtocol, val bands: List<Band>, val x: Double, val stored: Double, val reg: Int?)
        val cases = listOf(
            Case(max, flat, -30.04, -30.1, null), Case(max, flat, -30.4, -30.4, null),
            Case(max, flat, -29.6, -29.6, -30), Case(max, flat, -12.3, -12.3, -13),
            Case(max, flat, -6.4, -6.4, -7), // an AutoEQ file with max boost 6.4: register -7 (round 4 sent -6)
            Case(max, flat, 0.16, 0.1, 0), Case(max, flat, 0.04, 0.0, 0), Case(max, flat, 5.0, 5.0, null),
            Case(max, flat, 0.0, 0.0, 0), Case(max, flat, -6.0, -6.0, -6),
            Case(micro, hsUp, -34.04, -34.1, null), Case(micro, hsUp, -33.6, -33.6, -30),
            Case(micro, hsDown, -26.4, -26.4, null), Case(micro, hsDown, -25.6, -25.6, -30),
            Case(micro, hsDown, 3.5, 3.5, -1), // round 5 sent register 0 here
            Case(micro, hsDown, 5.0, 5.0, null),
            Case(micro, flat, -40.0, -40.0, null),
            Case(micro, hs394, -3.95, -4.0, -1), Case(micro, hs394, -3.94, -4.0, -1), Case(micro, hs394, -3.9, -3.9, 0),
            Case(micro, hs396, -33.96, -34.0, null), // flooring would flip the verdict: the exact value is kept (see below)
        )
        for (c in cases) {
            val stored = c.p.fitImportedPreamp(c.bands, c.x)
            if (c.x == -33.96) { assertEquals(c.x, stored, "verdict flip keeps the exact file value") ; continue }
            assertEquals(c.stored, stored, "stored ${c.p} ${c.x}")
            assertEquals(c.reg != null, c.p.preampFits(c.bands, stored), "fits ${c.p} ${c.x} -> $stored")
            assertEquals(c.reg, register(c.p, c.bands, stored), "register ${c.p} ${c.x} -> $stored")
            assertEquals(true, stored <= c.x, "never louder: ${c.x} -> $stored")
            // typed values use the same rounding: accepted iff the stored (floored) value passes
            assertEquals(c.reg != null, c.p.preampFits(c.bands, Preamp.floorTo(c.x)), "typed ${c.p} ${c.x}")
        }
        // the kept off-grid value: accepted, register -30, and floored -34.0 would be refused
        assertEquals(true, micro.preampFits(hs396, -33.96)); assertEquals(false, micro.preampFits(hs396, -34.0))
        assertEquals(-30, register(micro, hs396, -33.96))
        // refusal texts
        val microText = "Preamp -34.1 dB needs device preamp -30.1 dB (HIGH SHELF adjustment +4 dB), outside -30..0 dB range"
        assertEquals(listOf(microText), (micro.plan(hsUp, -34.1) as DevicePlan.Rejected).issues)
        assertEquals(microText, micro.preampRefusal(hsUp, -34.1))
        assertEquals("Preamp -30.04 dB outside -30..0 dB policy", max.preampRefusal(flat, -30.04))
        assertEquals("Preamp 5 dB outside -30..0 dB policy", max.preampRefusal(flat, 5.0))
        assertEquals(null, max.preampRefusal(flat, 0.04)); assertEquals(null, max.preampRefusal(flat, -30.0))
        assertEquals("-30.04", dbText(-30.04)); assertEquals("-34", dbText(-34.0)); assertEquals("-30.1", dbText(-34.1 + 4.0))
        assertEquals("Preamp -30.00004 dB outside -30..0 dB policy", max.preampRefusal(flat, -30.00004))
        val exact = micro.preampRefusal(hs396, -33.96004)!!
        assertEquals(true, exact.startsWith("Preamp -33.96004 dB needs device preamp -30.0000"), exact)
        // AUTO that needs more than -30 is refused; AUTO that fits is not
        val stack = { n: Int -> List(n) { Band("s$it", FilterType.PEAK, 1000.0, 10.0, 0.7) } }
        for (p in listOf(micro, max)) for (n in 1..5) {
            assertEquals(p.plan(stack(n), null) is DevicePlan.Rejected, p.autoRefusal(stack(n)) != null, "$p AUTO with $n stacked boosts")
        }
        assertEquals("this EQ needs -50 dB; Contour supports down to -30 dB for this device. Reduce the combined boosts.", max.autoRefusal(stack(5)))
        assertEquals(null, micro.autoRefusal(stack(2)))
        // AUTO to MANUAL: the lowest 0.1 dB value with AUTO's own register (R5-1: HS +3.94 AUTO is register 0; -3.9 keeps it)
        assertEquals(-3.9, micro.manualForAuto(hs394)); assertEquals(0, register(micro, hs394, null)); assertEquals(0, register(micro, hs394, -3.9))
        assertEquals(-7.0, max.manualForAuto(listOf(Band("p", FilterType.PEAK, 1000.0, 6.4, 0.7))))
        // sweep over shelf gains, peaks and stacks: the manual value reproduces AUTO's register, and one step lower would not
        for (p in listOf(micro, max)) for (g in 0..1000) {
            val bands = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, g / 100.0, 0.7), Band("k", FilterType.PEAK, 300.0, g % 7 / 2.0, 1.0))
            val auto = register(p, bands, null) ?: continue
            val m = p.manualForAuto(bands)!!
            assertEquals(auto, register(p, bands, m), "$p AUTO->MANUAL $g")
            assertEquals(true, (register(p, bands, m - 0.1) ?: -999) < auto, "$p lowest $g")
            assertEquals(m, Math.round(m * 10) / 10.0, "$p one decimal $g")
        }
        // property sweep: imports, typed values - the stored value and the register are never above what was asked
        for (p in listOf(micro, max)) for (bands in listOf(flat, hsUp, hsDown, hs394, hs396)) for (i in -6000..500) {
            val x = i / 100.0
            val stored = p.fitImportedPreamp(bands, x)
            assertEquals(p.preampFits(bands, x), p.preampFits(bands, stored), "$p $x -> $stored verdict")
            assertEquals(true, stored <= x + 1e-9, "$p $x -> $stored stored above asked")
            val d = x + p.shelfOffset(bands)
            val r = register(p, bands, stored)
            if (r != null) assertEquals(true, r <= Math.floor(d + 1e-6).toInt(), "$p $x -> $stored register $r above floor(d)")
            val typed = Preamp.floorTo(x)
            assertEquals(true, typed <= x + 1e-9); assertEquals(typed, Math.round(typed * 10) / 10.0)
            if (p.preampFits(bands, Preamp.floorTo(x)) == p.preampFits(bands, x)) assertEquals(Preamp.floorTo(x), stored, "$p $x floor")
            assertEquals(p.preampFits(bands, stored), ok(p, bands, stored), "$p $x plan")
        }
        // floorTo keeps grid values and goes down otherwise
        assertEquals(-3.2, Preamp.floorTo(-3.2)); assertEquals(-3.3, Preamp.floorTo(-3.25)); assertEquals(3.2, Preamp.floorTo(3.25))
        assertEquals(0.0, Preamp.floorTo(0.04)); assertEquals(-0.1, Preamp.floorTo(-0.04)); assertEquals(-7.0, Preamp.floorTo(-6.4, 1.0))
    }

    /** Round 7: finite input never becomes Infinity, an exported gain/preamp is the exact value, AUTO to MANUAL finds the lowest tenth. */
    @Test
    fun `huge values stay finite, export is exact and AUTO to MANUAL picks the lowest tenth`() {
        val flat = listOf(Band("p", FilterType.PEAK, 1000.0, 1.0, 0.7))
        val big = "1" + "0".repeat(308)
        for (sign in listOf("", "-")) {
            val eq = ApoText.parse("Preamp: $sign$big dB\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 0.7\n")
            val x = eq.preampDb!!
            assertEquals(true, x.isFinite()); assertEquals(x, Preamp.floorTo(x))
            for (p in listOf(DeviceProtocol.MICRO, DeviceProtocol.MAX)) {
                val stored = p.fitImportedPreamp(eq.bands, x)
                assertEquals(true, stored.isFinite()); assertEquals(false, p.preampFits(eq.bands, stored))
                assertEquals(true, p.plan(eq.bands, stored) is DevicePlan.Rejected)
                assertEquals(true, p.preampRefusal(eq.bands, stored)!!.startsWith("Preamp "))
            }
            val prof = Profile("i", "n", bands = eq.bands, preampDb = x, createdAt = 0, updatedAt = 0)
            assertEquals(prof, Json.decodeFromString(Profile.serializer(), Json.encodeToString(Profile.serializer(), prof)))
            assertEquals(x, ApoText.parse(ApoText.format(eq.bands, x)).preampDb)
            val hugeGain = ApoText.parse("Preamp: 0 dB\nFilter 1: ON PK Fc 1000 Hz Gain $sign$big dB Q 0.7\n")
            assertEquals(hugeGain.bands[0].gainDb, ApoText.parse(ApoText.format(hugeGain.bands, 0.0)).bands[0].gainDb)
        }
        assertEquals(Double.NaN.toString(), Preamp.floorTo(Double.NaN).toString())
        // export: on-grid values exactly as before, anything else the exact decimal (never rounded up, never a different verdict)
        assertEquals("Preamp: -6.1 dB\n", ApoText.format(emptyList(), -6.1)); assertEquals("Preamp: 0.0 dB\n", ApoText.format(emptyList(), 0.0))
        assertEquals("Preamp: -12.0 dB\n", ApoText.format(emptyList(), -12.0)); assertEquals("Preamp: -6.25 dB\n", ApoText.format(emptyList(), -6.25))
        val two = ApoText.format(listOf(Band("a", FilterType.PEAK, 1000.0, 3.25, 0.71), Band("b", FilterType.PEAK, 2000.0, 0.0, 1.0)), -1.0)
        assertEquals(true, "Gain 3.25 dB" in two && "Gain 0.0 dB" in two, two)
        val text = ApoText.format(listOf(Band("a", FilterType.PEAK, 1000.0, -2.344, 0.71)), -30.0000005)
        assertEquals(true, "Gain -2.344 dB" in text && "Preamp: -30.0000005 dB" in text, text)
        val back = ApoText.parse(text)
        assertEquals(-30.0000005, back.preampDb); assertEquals(-2.344, back.bands[0].gainDb)
        assertEquals(DeviceProtocol.MAX.preampFits(flat, -30.0000005), DeviceProtocol.MAX.preampFits(flat, back.preampDb!!))
        val rnd = java.util.Random(5)
        repeat(1000) {
            val g = Math.round((rnd.nextDouble() * 24 - 12) * 10000) / 10000.0; val pre = Math.round((rnd.nextDouble() * 35 - 30) * 10000) / 10000.0
            if (g == 0.0 || pre == 0.0) return@repeat
            val t = ApoText.parse(ApoText.format(listOf(Band("a", FilterType.PEAK, 1000.0, g, 0.7)), pre))
            assertEquals(pre, t.preampDb); assertEquals(g, t.bands[0].gainDb)
        }
        // AUTO to MANUAL: the lowest tenth, also at the tolerance edge (R6-4)
        val hsEdge = listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, 3.9999995, 0.7))
        assertEquals(0, register(DeviceProtocol.MICRO, hsEdge, null))
        assertEquals(-4.0, DeviceProtocol.MICRO.manualForAuto(hsEdge)); assertEquals(0, register(DeviceProtocol.MICRO, hsEdge, -4.0))
        assertEquals(-3.9, DeviceProtocol.MICRO.manualForAuto(listOf(Band("h", FilterType.HIGH_SHELF, 8000.0, 3.94, 0.7))))
    }

    private fun fromDacBand(w: WalkPlay.BandWrite, type: FilterType) =
        WalkPlay.DeviceBand(w.registers(), 0, ByteArray(20), true, type, w.freq, w.gainDb, w.q)

    /** Round 5: a DAC that already holds a band the guard refuses to send still loads, with a warning; sending stays blocked. */
    @Test
    fun `FROM DAC loads a band the guard refuses with a warning and sending stays blocked`() {
        val max = DeviceProtocol.MAX
        val hsBad = WalkPlay.BandWrite(0, 1000.0, 13.0, 0.71, WalkPlay.TYPE_HSQ)   // 13 dB: outside the offered +-12
        val reads = max.flatPlan().bands.map { fromDacBand(it, FilterType.PEAK) }.toMutableList()
        assertEquals(null, (max.importExact(reads, -3) as DacImport.Ready).warning) // a normal DAC state: no warning
        reads[0] = fromDacBand(hsBad, FilterType.HIGH_SHELF)
        val loaded = max.importExact(reads, -3) as DacImport.Ready
        assertEquals(FilterType.HIGH_SHELF, loaded.eq.bands[0].type)
        assertEquals(13.0, loaded.eq.bands[0].gainDb)
        assertEquals(true, loaded.warning!!.startsWith("Band 1: outside CrinEar Protocol Max supported types/ranges"), loaded.warning)
        assertEquals(true, "stays blocked" in loaded.warning!!)
        assertEquals(true, max.plan(loaded.eq.bands, loaded.eq.preampDb) is DevicePlan.Rejected)
        // Micro: an extreme peak written by another tool
        val pkBad = WalkPlay.BandWrite(0, 12000.0, 10.0, 0.1, WalkPlay.TYPE_PK)
        val microReads = ProtocolMicro.flatPlan().bands.map { fromDacBand(it, FilterType.PEAK) }.toMutableList()
        microReads[0] = fromDacBand(pkBad, FilterType.PEAK)
        val microLoaded = ProtocolMicro.importExact(microReads, -3) as DacImport.Ready
        assertEquals(true, microLoaded.warning!!.startsWith("Micro band 1 cannot be represented safely."), microLoaded.warning)
        assertEquals(true, ProtocolMicro.plan(microLoaded.eq.bands, microLoaded.eq.preampDb) is DevicePlan.Rejected)
        // a state that does not re-encode exactly is still refused, as before
        microReads[1] = fromDacBand(WalkPlay.BandWrite(1, 1000.0, 3.0, 0.71, WalkPlay.TYPE_HSQ), FilterType.HIGH_SHELF)
        assertEquals(true, ProtocolMicro.importExact(microReads, -3) is DacImport.Rejected)
    }

    @Test
    fun `shelf with invalid biquad cannot reach the wire`() {
        val shelf = Band("s", FilterType.LOW_SHELF, 1000.0, 10.0, 10.0)
        val plan = ProtocolMicro.plan(listOf(shelf), null)
        assertTrue(plan is DevicePlan.Rejected)
        assertTrue(plan.issues.any { it.contains("Band 1: non-finite biquad") }, "$plan")
        val write = WalkPlay.bandWrite(0, shelf)
        assertFailsWith<IllegalArgumentException> { WalkPlay.bandWriteReport(write, slot = 0) }
        assertTrue(ProtocolMicro.plan(listOf(shelf.copy(q = 0.7)), null) is DevicePlan.Ready)
    }

    @Test
    fun `invalid extreme shelves reject AUTO rather than looking like zero dB`() {
        for (type in listOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF)) {
            val invalid = Band("extreme", type, 1000.0, 10.0, 10.0)
            assertFailsWith<IllegalArgumentException>("$type must not report AUTO 0") {
                Preamp.auto(listOf(invalid), ShelfForm.SLOPE)
            }
            val plan = ProtocolMicro.plan(listOf(invalid), null)
            assertTrue(plan is DevicePlan.Rejected, "$type AUTO unexpectedly ready: $plan")
            assertTrue(plan.issues.any { it.contains("non-finite") || it.contains("no real response") }, "$plan")
            val valid = invalid.copy(q = 0.7)
            assertTrue(Preamp.auto(listOf(valid), ShelfForm.SLOPE) < 0, "$type valid AUTO lost")
            assertTrue(ProtocolMicro.plan(listOf(valid), null) is DevicePlan.Ready, "$type valid shelf rejected")
        }
    }

    @Test
    fun `manual preamp rejects out of range HS adjusted register without silent clamp`() {
        val shelf = Band("hs", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7)
        val tooHigh = ProtocolMicro.plan(listOf(shelf), 0.0)
        assertTrue(tooHigh is DevicePlan.Rejected)
        assertTrue(tooHigh.issues.any { it.contains("HIGH SHELF adjustment") })
        assertEquals(-30, (ProtocolMicro.plan(listOf(shelf), -34.0) as DevicePlan.Ready).preampDb)
        assertTrue(ProtocolMicro.plan(listOf(shelf), -34.1) is DevicePlan.Rejected)
        assertEquals(0, (ProtocolMicro.plan(listOf(shelf), -3.9) as DevicePlan.Ready).preampDb) // +0.1 is sent as 0, never louder
        assertTrue(ProtocolMicro.plan(listOf(shelf), -2.9) is DevicePlan.Rejected) // +1.1 is refused
    }

    // ---- 6: which profile is on the DAC -----------------------------------------------------------

    private fun profileOf(name: String, eq: ImportedEq) = Profile(name, name, bands = eq.bands, preampDb = null, createdAt = 0, updatedAt = 0)

    /** What a read-back of [plan] would decode to: each write report parsed as a band reply (same layout). */
    private fun readBack(plan: DevicePlan.Ready): List<WalkPlay.Registers> =
        plan.bands.map { WalkPlay.parseBand(WalkPlay.bandWriteReport(it, slot = 0)).registers }

    @Test
    fun `6 - matching the DAC's registers to a profile`() {
        needLibrary()
        val nightfall = profileOf("nightfall", ApoText.parse(File(library, "crinear-nightfall").listFiles()!!.first { it.name.endsWith(".txt") }.readText()))
        val plan = ProtocolMicro.plan(nightfall) as DevicePlan.Ready
        var ok = 0
        if (ProtocolMicro.matches(nightfall, readBack(plan), plan.preampDb)) ok++ else println("  Nightfall does not match its own read-back")
        val nudged = nightfall.copy(bands = nightfall.bands.mapIndexed { i, b -> if (i == 1) b.copy(gainDb = b.gainDb + 0.1) else b })
        if (!ProtocolMicro.matches(nudged, readBack(plan), plan.preampDb)) ok++ else println("  Nightfall +0.1 dB still matches")
        val flatRegs = bandReplies().map { WalkPlay.parseBand(it).registers }
        val flatPreamp = backup["preamp_db"]!!.jsonPrimitive.int
        val seeded = library.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            dir.listFiles().orEmpty().filter { it.name.endsWith(".txt") }.maxByOrNull { it.name }?.let { profileOf(dir.name, ApoText.parse(it.readText())) }
        }
        assertTrue(
            seeded.size >= 5,
            "found ${seeded.size} seeded profiles (need 5) in ${library.absolutePath}: " +
                library.listFiles().orEmpty().joinToString { it.name + if (it.isDirectory) "/" else "" },
        )
        assertTrue(ProtocolMicro.isFlat(flatRegs, flatPreamp), "the factory read is not FLAT")
        if (seeded.none { ProtocolMicro.matches(it, flatRegs, flatPreamp) }) ok++ else println("  the factory flat read matches a seeded profile")
        println("PARITY 6 matching: $ok/3 (flat read = FLAT, ${seeded.size} seeded profiles checked)")
        assertEquals(3, ok)
    }
}

/** walkplay.py's type names, for comparing with the backup JSON. */
private object ApoType {
    fun of(t: FilterType): String = when (t) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSQ"
        FilterType.HIGH_SHELF -> "HSQ"
        FilterType.LOW_PASS -> "LP"
        FilterType.HIGH_PASS -> "HP"
        FilterType.BAND_PASS -> "BP"
        FilterType.ALL_PASS -> "AP"
    }
}
