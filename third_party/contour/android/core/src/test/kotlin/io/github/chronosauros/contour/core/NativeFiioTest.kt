package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure JVM tests. Original captures are byte-identical pinned upstream resources, not invented
 * device observations. All generated replies below are explicitly synthetic unit-test inputs.
 * No device, driver stub, Android API, private directory, or external resource dependency.
 */
class NativeFiioTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/fiio/$name")).use { it.readBytes() }
    private fun json(name: String) = Json.parseToJsonElement(resource(name).decodeToString())
    private fun bytes(data: JsonArray) = data.map { it.jsonPrimitive.int.also { value -> require(value in 0..255) }.toByte() }.toByteArray()
    private fun codec(name: String) = FiioCodec(FiioCatalog.rules.single { it.productName == name })
    private fun peak(n: Int = 0) = Band("b$n", FilterType.PEAK, 1000.0, 0.0, 0.71)
    private fun plan(c: FiioCodec, bands: List<Band> = listOf(peak()), preamp: Double? = 0.0, slot: Int = c.config.userSlots.first()) =
        c.planOnExplicitSend(bands, preamp, slot, explicitUserAction = true)
    // SYNTHETIC framing; never used as capture evidence.
    private fun reply(cmd: Int, data: List<Int>) = (listOf(0xBB, 0x0B, 0, 0, cmd, data.size) + data + listOf(0, 0xEE)).map { it.toByte() }.toByteArray()
    private fun pair(v: Int) = listOf((v ushr 8) and 255, v and 255)
    private fun bandReply(r: FiioRegisters) = reply(FiioCodec.BAND, listOf(r.index) + pair(r.gainTenths) + pair(r.frequencyHz) + pair(r.qHundredths) + listOf(r.typeCode))
    private fun snapshot(c: FiioCodec, s: FiioSnapshot): FiioSnapshot = c.snapshot(
        reply(FiioCodec.SLOT, listOf(s.activeSlot)), reply(FiioCodec.COUNT, listOf(s.count)),
        reply(FiioCodec.PREAMP, pair(s.preampTenths)), s.bands.map { bandReply(it) }, reply(FiioCodec.SLOT, listOf(s.activeSlot)))

    private fun capturePackets(name: String): List<JsonObject> {
        val obj = json(name).jsonObject
        return obj["sequence"]?.jsonArray?.map { it.jsonObject }
            ?: obj.getValue("exchanges").jsonArray.flatMap { it.jsonObject.getValue("responses").jsonArray }.map { it.jsonObject }
    }
    private fun captureLogical(c: FiioCodec, name: String): List<ByteArray> = capturePackets(name).map {
        val p = bytes(it.getValue("data").jsonArray)
        c.logicalReply(it.getValue("reportId").jsonPrimitive.int, p, p.size)
    }
    /** Recorded fixtures lack a second closing slot poll. Reusing the opening reply here tests
     * assembly only; it is NOT evidence that firmware was stable throughout a live read.
     * Captures can carry duplicate asynchronous events: dedupe explicitly for the import oracle;
     * the production assembler rejects unfiltered duplicate indices.
     */
    private fun capturedSnapshot(c: FiioCodec, name: String): FiioSnapshot {
        val packets = captureLogical(c, name)
        val slot = packets.first { it[4].toInt() == FiioCodec.SLOT }
        val count = packets.first { it[4].toInt() == FiioCodec.COUNT }
        val pg = packets.first { it[4].toInt() == FiioCodec.PREAMP }
        val bands = packets.filter { it[4].toInt() == FiioCodec.BAND }.distinctBy { it[6] }
        return c.snapshot(slot, count, pg, bands, slot)
    }

    @Test fun `fixture provenance hashes and literal capture identity`() {
        val manifest = json("provenance.json").jsonObject
        assertEquals(FiioCatalog.PINNED_COMMIT, manifest.getValue("pinnedCommit").jsonPrimitive.content)
        val files = manifest.getValue("files").jsonArray
        assertEquals(1, files.size)
        for (file in files) {
            val f = file.jsonObject
            val name = f.getValue("name").jsonPrimitive.content
            val digest = MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(f.getValue("sha256").jsonPrimitive.content, digest)
            assertTrue(f.getValue("url").jsonPrimitive.content.contains(FiioCatalog.PINNED_COMMIT))
            val d = json(name).jsonObject.getValue("device").jsonObject
            val c = assertNotNull(FiioCatalog.capturedRoute(d.getValue("vendorId").jsonPrimitive.int,
                d.getValue("productId").jsonPrimitive.int, d.getValue("productName").jsonPrimitive.content))
            assertEquals(d.getValue("productName").jsonPrimitive.content, c.productName)
        }
    }
    @Test fun `stable catalog is exactly the KA15, K13 R2R, Melody, Tiny A, JA11, RETRO NANO and JIEZI rules not a VID fallback`() {
        val oracle = json("catalog-oracle.json").jsonArray
        // The oracle holds the upstream rows of the KA15 and K13 R2R; Melody and Tiny A are measured on hardware (see FiioCatalog).
        assertEquals(7, FiioCatalog.rules.size) // the JA11, the RETRO NANO and the JIEZI come after the Tiny A: found by their USB names, not in the oracle
        assertEquals(oracle.map { it.jsonObject.getValue("productName").jsonPrimitive.content }.toSet(), FiioCatalog.rules.take(2).map { it.productName }.toSet())
        for (row in oracle) {
            val r = row.jsonObject
            val name = r.getValue("productName").jsonPrimitive.content
            val c = FiioCatalog.rules.single { it.productName == name }
            assertEquals(r.getValue("vendorIds").jsonArray.map { it.jsonPrimitive.int }.toSet(), c.vendorIds)
            assertEquals(r.getValue("maxFilters").jsonPrimitive.int, c.maxFilters)
            assertEquals(r.getValue("minGain").jsonPrimitive.double, c.minGainDb)
            assertEquals(r.getValue("saveCommand").jsonPrimitive.int, c.saveCommand)
            assertEquals(r.getValue("userSlots").jsonArray.map { it.jsonPrimitive.int }.toSet(), c.userSlots)
            assertEquals(r.getValue("bestGuess").jsonPrimitive.boolean, c.bestGuessProductName)
            assertTrue(c.userSlots.intersect(c.stockSlots).isEmpty())
            assertTrue(c.sourceUrl.contains(FiioCatalog.PINNED_COMMIT))
            assertEquals("beta-unverified", c.status)
            // KA15: the three types of its capture; K13 R2R: all seven (FiiO web app capture, 14.10.2026, FINDINGS.md).
            assertEquals(if (name == "FIIO KA15") setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF) else FilterType.entries.toSet(), c.typeCodes.keys, name)
            FiioCodec(c) // Configuration invariants.
        }
        assertEquals(0, FiioCatalog.rules.count { it.codecBlockers.isNotEmpty() })
        assertEquals(1, FiioCatalog.rules.count { it.capture != null }) // K13 R2R, Melody and Tiny A have no capture: their identity is the measured product ID
        assertNull(FiioCatalog.capturedRoute(0x0A12, 0x4005, "Qudelix-5K USB DAC 48KHz"))
        assertNull(FiioCatalog.capturedRoute(0x2972, 0xFFFF, "FIIO QX13"))
        assertNull(FiioCatalog.capturedRoute(0x2972, 0x0104, "Unknown"))
        assertEquals(7, assertNotNull(FiioCatalog.capturedRoute(0x2972, 0x0104, "FIIO KA15")).reportId)
        assertNull(FiioCatalog.capturedRoute(0x2972, 0x0104, "FIIO QX13"))
    }
    @Test fun `KA15 capture decodes a complete native snapshot with exact inverse import`() {
        for (c in FiioCatalog.rules.filter { it.capture != null }.map { FiioCodec(it) }) {
            val fixture = c.config.capture!!.fixturePath.substringAfterLast('/')
            val s = capturedSnapshot(c, fixture)
            assertTrue(s.count <= c.config.maxFilters)
            val imported = c.importExact(s)
            assertEquals(s.count, imported.bands.size)
            // Intended pregain = register - offset (KA15: -12 dB, measured 04.10.2026).
            assertEquals((s.preampTenths - c.config.preampOffsetDb * 10) / 10.0, imported.preampDb, 1e-9)
            // Reading a stock/ambiguous slot is permitted; re-saving to it is not.
            val target = c.config.userSlots.first()
            val p = plan(c, imported.bands, imported.preampDb, target)
            assertTrue(c.matches(p.expected, s.copy(activeSlot = target)))
        }
        assertEquals(10, capturedSnapshot(codec("FIIO KA15"), "fiio_fiio_ka15.json").count)
    }
    @Test fun `JA11 replies read on the Mac carry CRC-8 MAXIM over the bytes after the bb 0b header, 12 of 13 (not the slot reply), so its frames stay without a checksum`() {
        fun crc(bytes: List<Int>): Int { var c = 0; for (b in bytes) { c = c xor b; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 } }; return c }
        // dac-lab run 2026-11-11-fiio-ja11-01-mac, backup-read.txt: bb 0b 00 SEQ cmd LEN data CRC ee
        val replies = listOf(
            "bb 0b 00 00 18 01 05 94 ee", "bb 0b 00 00 17 02 00 00 f1 ee",
            "bb 0b 00 00 15 08 00 00 00 03 e8 00 46 00 e6 ee", "bb 0b 00 00 15 08 01 00 00 07 d0 00 46 00 ee ee", "bb 0b 00 00 15 08 02 00 00 13 88 00 46 00 8c ee",
            "bb 0b 00 00 15 08 03 00 00 1f 40 00 46 00 cb ee", "bb 0b 00 00 15 08 04 00 00 27 10 00 46 00 79 ee",
            "bb 0b 00 00 09 01 07 c9 ee", "bb 0b 00 00 0b 02 02 14 b6 ee", "bb 0b 00 00 02 01 3c 96 ee", "bb 0b 00 00 12 01 01 9f ee", "bb 0b 00 00 20 01 01 0e ee",
        ).map { line -> line.split(" ").map { it.toInt(16) } }
        for (f in replies) assertEquals(f[f.size - 2], crc(f.subList(2, f.size - 2)), f.joinToString(" ") { "%02x".format(it) })
        // the first reply of the session, the slot (04, Close EQ), carries c6 where this rule gives 3e; and the header-covering CRC of Contour's own requests (the web app's capture) gives neither
        assertEquals(0x3E, crc(listOf(0, 0, 0x16, 1, 4))); assertEquals(0xF3, crc(listOf(0xBB, 0x0B, 0, 0, 0x16, 1, 4)))
        // the JA11's record keeps pacing.checksum false: the frames are the Mac reader's (sequence and CRC 0), which the unit answered
        val c = codec("JadeAudio JA11")
        assertFalse(c.config.checksumFrames)
        assertEquals(listOf(0xBB, 0x0B, 0, 0, 0x16, 0, 0, 0xEE), c.querySlot().bytes)
        assertEquals(listOf(0xBB, 0x0B, 0, 0, 0x15, 1, 4, 0, 0xEE), c.queryBand(4).bytes)
        assertEquals(2, c.config.reportId)
    }
    @Test fun `JA11 offers PEAK, both shelves, LOW PASS and HIGH PASS in every build and never BAND PASS or ALL PASS, is written in every build (hardwareVerified after the phone run), and a send selects USER1 before its five bands and ends at SAVE`() {
        val t = DeviceTarget.FIIO_JA11
        val five = setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS)
        val codes = mapOf(FilterType.PEAK to 0, FilterType.LOW_SHELF to 1, FilterType.HIGH_SHELF to 2, FilterType.LOW_PASS to 4, FilterType.HIGH_PASS to 5)
        for (channel in BuildChannel.entries) Proven.asChannel(channel) {
            assertEquals(five, t.caps.types, "$channel"); assertEquals(codes, t.fiio!!.typeCodes, "$channel"); assertEquals(3, t.destinationSlot)
            assertFalse(t.readOnly, "$channel") // hardwareVerified 11.11.2026 (phone run 2026-11-11-fiio-ja11-10-phone)
        }
        assertTrue(FiioJa11.PASS_FILTERS_VERIFIED && FiioJa11.passFiltersOn)
        assertTrue(FilterType.BAND_PASS !in t.caps.types && FilterType.ALL_PASS !in t.caps.types) // BP plays flat (checked twice), AP is indistinguishable from an ignored type
        // the four proven FiiO targets are never read only
        for (other in listOf(DeviceTarget.KA15, DeviceTarget.K13, DeviceTarget.MELODY, DeviceTarget.TINY_A)) assertFalse(other.readOnly, other.caps.name)
        val c = codec("JadeAudio JA11")
        assertEquals(5, c.config.maxFilters); assertEquals(setOf(3), c.config.userSlots); assertEquals(4, c.config.bypassSlot); assertTrue(c.config.disconnectOnSave)
        val p = plan(c, List(5) { peak(it) })
        assertEquals(listOf(0xAA, 0x0A, 0, 0, 0x16, 1, 3, 0, 0xEE), p.selection.bytes) // USER1 first: the unit sits on Close EQ (4) from the factory
        assertEquals(listOf(FiioCodec.PREAMP, FiioCodec.COUNT) + List(5) { FiioCodec.BAND }, p.writes.map { it.bytes[4] })
        assertEquals(listOf(0xAA, 0x0A, 0, 0, 0x19, 1, 3, 0, 0xEE), p.save.bytes)
        assertEquals(FiioSaveVerification.RECONNECT_AND_READ_REQUIRED, p.saveVerification)
        assertEquals(200, c.config.writeGapMs); assertEquals(120, p.expected.preampTenths); assertEquals(12.0, c.config.preampOffsetDb) // USER1 plays 12 dB below Close EQ: register = intended preamp + 12 dB (measured 13.11.2026)
        for (slot in listOf(0, 1, 2, 4)) assertFailsWith<IllegalArgumentException>("slot $slot") { plan(c, slot = slot) }
        // never built for this unit: 0x1A, 0x21, 0x30 (and report 0x54 is no FiiO frame at all)
        val all = p.writes + p.selection + p.save + p.backupQueries + p.verificationQueries
        assertTrue(all.all { it.reportId == 2 && (it.bytes[4] and 255) !in setOf(0x1A, 0x21, 0x30) })
        // a pending-save plan: Mismatch unless the first read after the DAC is back equals what was planned
        assertEquals(FiioVerification.Pending("Disconnect-on-save requires reconnect and complete read"), c.verification(p, p.expected))
        assertEquals(FiioVerification.RegistersMatchPersistenceUnproven, c.verification(p, p.expected, reconnected = true))
    }
    @Test fun `exact requests frame directions effects and no hidden slot change on read`() {
        val c = codec("FIIO KA15")
        // Sequence byte and CRC-8/MAXIM exactly as captured from fiiocontrol.fiio.com (03.10.2026, frames seq 3 and 4).
        repeat(3) { c.queryPreamp() }
        assertEquals(listOf(0xBB, 0x0B, 0, 3, 0x18, 0, 0xF1, 0xEE), c.queryCount().bytes)
        assertEquals(listOf(0xBB, 0x0B, 0, 4, 0x16, 0, 0x57, 0xEE), c.querySlot().bytes)
        fun masked(f: FiioFrame) = f.bytes.toMutableList().also { it[3] = 0; it[it.size - 2] = 0 }
        assertEquals(listOf(187, 11, 0, 0, 23, 0, 0, 238), masked(c.queryPreamp()))
        assertEquals(listOf(187, 11, 0, 0, 21, 1, 9, 0, 238), masked(c.queryBand(9)))
        assertTrue(c.activeReadQueries(10).all { it.effect == FiioEffect.READ_ONLY && it.bytes.first() == 0xBB })
        val p = plan(c, slot = 9)
        assertEquals(listOf(170, 10, 0, 0, 22, 1, 9, 0, 238), masked(p.selection))
        assertEquals(FiioEffect.SELECT_ACTIVE_USER_BANK, p.selection.effect)
        assertTrue(p.writes.all { it.effect == FiioEffect.WRITE_ACTIVE_BANK && it.bytes.first() == 0xAA })
        assertEquals(FiioEffect.SAVE_USER_BANK, p.save.effect)
        assertTrue(p.selectionVerification.effect == FiioEffect.READ_ONLY)
        assertEquals(listOf(22, 24, 23), p.backupQueries.map { it.bytes[4] })
        assertEquals(FiioCodec.SLOT, p.verificationQueries.last().bytes[4])
    }
    @Test fun `wrong header direction command declared length terminator short report and index rejected`() {
        val c = codec("FIIO KA15")
        val good = bandReply(plan(c).expected.bands.single())
        for (bad in listOf(good.copyOf(15), good.copyOf(17), good.copyOf().also { it[0] = 0xAA.toByte() },
            good.copyOf().also { it[1] = 0x0A }, good.copyOf().also { it[4] = 0x17 },
            good.copyOf().also { it[5] = 7 }, good.copyOf().also { it[15] = 0 },
            good.copyOf().also { it[6] = 1 }, good.copyOf().also { it[13] = 3 })) {
            assertFailsWith<IllegalArgumentException> { c.parseBand(bad, 0, 1) }
        }
        assertFailsWith<IllegalArgumentException> { c.parseBand(good, -1, 1) }
        assertFailsWith<IllegalArgumentException> { c.parseBand(good, 0, 0) }
        assertFailsWith<IllegalArgumentException> { c.parseBand(good, 0, 11) }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(2, good, 16) }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(7, good, 63) }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(7, good.copyOf(7), 7) }
        assertFailsWith<IllegalArgumentException> { c.parseSlot(c.querySlot().payload()) }
        assertFailsWith<IllegalArgumentException> { c.parseCount(reply(FiioCodec.COUNT, listOf(11))) }
        assertFailsWith<IllegalArgumentException> { c.queryBand(10) }
        assertFailsWith<IllegalArgumentException> { c.activeBandQueries(-1) }
    }
    @Test fun `opaque sequence checksum and stale nonzero tail are not mistaken for payload`() {
        val c = codec("FIIO KA15")
        val raw = bytes(capturePackets("fiio_fiio_ka15.json").first().getValue("data").jsonArray)
        assertTrue(raw.drop(9).any { it.toInt() != 0 })
        val logical = c.logicalReply(7, raw, raw.size)
        assertEquals(9, logical.size)
        assertEquals(9, c.parseSlot(logical))
        assertFailsWith<IllegalArgumentException> { c.parseSlot(raw) }
        val bad = raw.copyOf().also { it[8] = 0 }
        assertFailsWith<IllegalArgumentException> { c.logicalReply(7, bad, bad.size) }
    }
    @Test fun `snapshot rejects duplicate missing extra count slot drift and malformed preamp`() {
        val c = codec("FIIO KA15")
        val s = plan(c, listOf(peak(0), peak(1))).expected
        val slot = reply(FiioCodec.SLOT, listOf(s.activeSlot))
        val count = reply(FiioCodec.COUNT, listOf(2))
        val pg = reply(FiioCodec.PREAMP, pair(s.preampTenths)) // KA15: intended 0 dB is register 120
        val r = s.bands.map { bandReply(it) }
        assertEquals(s, c.snapshot(slot, count, pg, r.reversed(), slot))
        for (bad in listOf(listOf(r[0], r[0]), r.dropLast(1), r + r[0], listOf(r[0], bandReply(s.bands[1].copy(index = 2))))) {
            assertFailsWith<IllegalArgumentException> { c.snapshot(slot, count, pg, bad, slot) }
        }
        assertFailsWith<IllegalArgumentException> { c.snapshot(slot, count, pg, r, reply(FiioCodec.SLOT, listOf(8))) }
        assertFailsWith<IllegalArgumentException> { c.snapshot(slot, count, byteArrayOf(), r, slot) }
    }
    @Test fun `native quantization negative pregain zero is written and disabled band is zero gain`() {
        val c = codec("FIIO KA15")
        val b = peak().copy(freqHz = 1000.9, gainDb = -3.26, q = 0.716)
        // KA15 register = intended pregain + 12 dB (measured 04.10.2026). Pregain is rounded DOWN (quieter, never louder):
        // -4.35 -> -44 tenths + 120 = 76 (it was -43 + 120 = 77 with nearest rounding) on a 0.1 dB DAC; the KA15 itself keeps whole dB (Pixel 26.10.2026: a fractional preamp reset it), -4.35 -> -5 -> register 70.
        assertEquals(70, plan(c, listOf(b), -4.35).expected.preampTenths)
        assertEquals(80, plan(c, listOf(b), -4.0 - 1e-9).expected.preampTenths) // -4.0 a hair off the grid stays -4
        val tenth = FiioCodec(c.config.copy(preampWholeDb = false))
        val p = plan(tenth, listOf(b), -4.35)
        // Band gain -3.26 is rounded DOWN too: -33 tenths (it was -32, JS truncation toward zero, a shallower cut).
        assertEquals(FiioRegisters(0, 1000, -33, 72, 0), p.expected.bands.single())
        assertEquals(76, p.expected.preampTenths)
        assertEquals(listOf(0, 76), p.writes.first().bytes.subList(6, 8))
        assertEquals(77, plan(tenth, listOf(b), -4.3).expected.preampTenths) // already on the grid: unchanged
        assertEquals(-43, c.parsePreamp(reply(FiioCodec.PREAMP, pair(-43))))
        val zero = plan(c, preamp = -12.0)
        assertEquals(listOf(0, 0), zero.writes.first().bytes.subList(6, 8))
        val negative = FiioCodec(tenth.config.copy(preampOffsetDb = 0.0))
        assertEquals(listOf(255, 212), plan(negative, listOf(b), -4.35).writes.first().bytes.subList(6, 8))
        assertEquals(0, plan(c, listOf(b.copy(enabled = false))).expected.bands.single().gainTenths)
        assertEquals(1, plan(c, listOf(peak().copy(type = FilterType.LOW_SHELF))).expected.bands.single().typeCode)
        assertEquals(2, plan(c, listOf(peak().copy(type = FilterType.HIGH_SHELF))).expected.bands.single().typeCode)
    }
    @Test fun `band gain and pregain are rounded down never louder and keep grid values`() {
        val c = codec("FIIO KA15")
        fun tenths(g: Double) = plan(c, listOf(peak().copy(gainDb = g))).expected.bands.single().gainTenths
        for (k in -120..120) assertEquals(k, tenths(k / 10.0), "grid value ${k / 10.0} is unchanged")
        assertEquals(-24, tenths(-2.34)); assertEquals(23, tenths(2.34)); assertEquals(-3, tenths(-0.21)); assertEquals(0, tenths(0.09))
        val rnd = java.util.Random(7)
        repeat(2000) {
            val g = (rnd.nextDouble() * 24 - 12).let { Math.round(it * 1000) / 1000.0 } // 3 decimals, off the grid
            val t = tenths(g)
            assertTrue(t / 10.0 <= g + 1e-6, "gain $g sent as ${t / 10.0} is louder")
            assertTrue(t / 10.0 > g - 0.1 - 1e-6, "gain $g sent as ${t / 10.0} is more than a step lower")
        }
        for (p in listOf(-30.04, -4.35, -0.01, -11.99)) {
            val t = plan(c, preamp = p.coerceIn(c.config.preampMinDb, c.config.preampMaxDb)).expected.preampTenths - 120
            assertTrue(t / 10.0 <= p.coerceIn(c.config.preampMinDb, c.config.preampMaxDb) + 1e-6)
        }
    }
    @Test fun `each model preserves capacity range save command and denies every forbidden slot`() {
        for (c in FiioCatalog.rules.filter { it.codecBlockers.isEmpty() }.map { FiioCodec(it) }) {
            val bands = List(c.config.maxFilters) { peak(it) }
            val p = plan(c, bands, c.config.preampMinDb)
            assertEquals(c.config.maxFilters, p.expected.count)
            assertEquals(c.config.maxFilters + 2, p.writes.size)
            assertEquals(c.config.saveCommand, p.save.bytes[4])
            assertEquals(c.config.maxFilters - 1, p.expected.bands.last().index)
            assertEquals(p.expected, snapshot(c, p.expected))
            assertFailsWith<IllegalArgumentException> { plan(c, bands + peak(), 0.0) }
            assertFailsWith<IllegalArgumentException> { plan(c, preamp = c.config.preampMinDb - 0.1) }
            assertFailsWith<IllegalArgumentException> { plan(c, preamp = c.config.preampMaxDb + 0.1) }
            for (slot in (0..255).filter { it !in c.config.userSlots }) {
                assertFailsWith<IllegalArgumentException> { plan(c, slot = slot) }
                assertFailsWith<IllegalArgumentException> { c.selectUserSlot(slot) }
            }
        }
        assertEquals(setOf(7, 8, 9), codec("FIIO KA15").config.userSlots)
        assertEquals(0x19, plan(codec("FIIO KA15")).save.bytes[4])
    }
    @Test fun `the bypass select is the select frame with the model's bypass number, and no write path takes the bypass`() {
        for ((config, bypass) in listOf(FiioCatalog.K13 to 240, FiioCatalog.MELODY to 240, FiioCatalog.TINY_A to 240, FiioCatalog.KA15 to 10)) {
            val c = FiioCodec(config)
            val f = c.selectBypassSlot()
            assertEquals(bypass, config.bypassSlot, config.productName)
            assertEquals(FiioEffect.SELECT_BYPASS_SLOT, f.effect)
            // the same command and shape as the USER select (0x16, one data byte), only the number differs
            val user = c.selectUserSlot(config.userSlots.min())
            assertEquals(FiioCodec.SLOT, f.bytes[4], config.productName); assertEquals(1, f.bytes[5]); assertEquals(bypass, f.bytes[6])
            assertEquals(user.bytes.size, f.bytes.size); assertEquals(user.reportId, f.reportId)
            assertEquals(user.bytes.take(3) + user.bytes[4], f.bytes.take(3) + f.bytes[4]) // byte 3 is the host sequence where the model counts it
            // still no way to select it as a USER slot, to plan a write to it or to select a factory preset
            assertFailsWith<IllegalArgumentException> { c.selectUserSlot(bypass) }
            assertFailsWith<IllegalArgumentException> { plan(c, slot = bypass) }
            for (preset in config.stockSlots) assertFailsWith<IllegalArgumentException> { c.selectUserSlot(preset) }
        }
        // the K13 R2R's frame is in the 8-bit AA/BB family, command 0x16
        assertEquals(0xAA, FiioCodec(FiioCatalog.K13).selectBypassSlot().bytes[0])
    }
    @Test fun `a written USER name is the full NUL padded field on every FiiO with slot names, so a 5 letter name reads back as written`() {
        val named = FiioCatalog.rules.filter { it.userSlotNames }
        assertEquals(setOf(FiioCatalog.KA15.productName, FiioCatalog.MELODY.productName, FiioCatalog.TINY_A.productName, FiioCatalog.JIEZI.productName), named.map { it.productName }.toSet()) // the K13 and the RETRO NANO have a slot picker, no names on the DAC
        for (config in named) {
            val c = FiioCodec(config)
            val slot = config.userSlots.min()
            for (n in 1..config.nameLength) {
                val name = "ABCDEFGH".take(n)
                val f = c.writeName(slot, name)
                val payload = f.bytes.subList(6, 6 + f.bytes[5])
                assertEquals(1 + config.nameLength, payload.size, "${config.productName} $name: slot id plus the whole field")
                assertEquals(name.map { it.code } + List(config.nameLength - n) { 0 }, payload.drop(1), config.productName)
                // The SnowSky DACs keep a stale 0x44 at field index 5 (Tiny A run 25.10.2026): a DAC that copies exactly what it is sent shows the name as written.
                val field = IntArray(config.nameLength).also { if (it.size > 5) it[5] = 0x44 }
                payload.drop(1).forEachIndexed { i, b -> field[i] = b }
                assertEquals(name, field.takeWhile { it != 0 }.joinToString("") { it.toChar().toString() }, "${config.productName} $name")
            }
        }
    }
    @Test fun `the wait after the flash save is per model - the KA15 about 2 s, the others 800 ms - and the plan carries the model's own`() {
        val waits = FiioCatalog.rules.associate { it.productName to it.afterSaveMs }
        assertEquals(2000, waits.getValue("FIIO KA15"))
        for ((name, ms) in waits) if (name != "FIIO KA15") assertEquals(800, ms, name)
        for (c in FiioCatalog.rules.filter { it.userSlots.isNotEmpty() }.map { FiioCodec(it) }) assertEquals(c.config.afterSaveMs, plan(c).delayAfterSaveMs, c.config.productName)
    }
    @Test fun `NaN infinity range unsupported type missing preamp and implicit action rejected atomically`() {
        val c = codec("FIIO KA15")
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { plan(c, preamp = x) }
            for (b in listOf(peak().copy(freqHz = x), peak().copy(gainDb = x), peak().copy(q = x), peak().copy(gainDb = x, enabled = false))) {
                assertFailsWith<IllegalArgumentException> { plan(c, listOf(b)) }
            }
        }
        for (b in listOf(peak().copy(freqHz = 19.0), peak().copy(freqHz = 20001.0), peak().copy(gainDb = -12.1),
            peak().copy(gainDb = 12.1), peak().copy(q = 0.09), peak().copy(q = 10.01), peak().copy(q = 0.0),
            peak().copy(type = FilterType.LOW_PASS), peak().copy(type = FilterType.HIGH_PASS),
            peak().copy(type = FilterType.BAND_PASS), peak().copy(type = FilterType.ALL_PASS))) { // the KA15 has only PK/LS/HS bytes
            assertFailsWith<IllegalArgumentException> { plan(c, listOf(peak(), b)) }
        }
        assertFailsWith<IllegalArgumentException> { plan(c, preamp = null) }
        assertFailsWith<IllegalArgumentException> { c.planOnExplicitSend(listOf(peak()), 0.0, 7, false) }
        val profile = Profile("p", "Original", bands = listOf(peak()), preampDb = null, createdAt = 0, updatedAt = 0)
        assertFailsWith<IllegalArgumentException> { c.planOnExplicitSend(profile, 7, true) }
        assertEquals(null, profile.preampDb)
        assertEquals(1, profile.bands.size)
    }
    @Test fun `send selection backup barrier and post save read never fake success`() {
        val ka = codec("FIIO KA15")
        val kp = plan(ka)
        val selected = reply(FiioCodec.SLOT, listOf(7))
        assertEquals(kp.writes, ka.writesAfterSelection(kp, selected, kp.expected))
        assertFailsWith<IllegalArgumentException> { ka.writesAfterSelection(kp, reply(FiioCodec.SLOT, listOf(0)), kp.expected) }
        assertFailsWith<IllegalArgumentException> { ka.writesAfterSelection(kp, selected, kp.expected.copy(activeSlot = 0)) }
        assertTrue(ka.verification(kp, kp.expected.copy(preampTenths = 1)) is FiioVerification.Mismatch)
        assertEquals(FiioSaveVerification.POST_SAVE_READ_REQUIRED, kp.saveVerification)
        assertTrue(ka.verification(kp, null) is FiioVerification.Pending)
        assertEquals(FiioVerification.RegistersMatchPersistenceUnproven, ka.verification(kp, kp.expected))
    }
    @Test fun `raw register boundaries unsupported shapes and compensated exact edges`() {
        val c = codec("FIIO KA15")
        for (gain in listOf(-12.0, 12.0)) {
            val p = plan(c, listOf(peak().copy(freqHz = 20000.0, gainDb = gain, q = 10.0)), gain - c.config.preampOffsetDb)
            assertEquals((gain * 10).toInt(), p.expected.bands.single().gainTenths)
            assertEquals((gain * 10).toInt(), p.expected.preampTenths)
            assertEquals(1000, p.expected.bands.single().qHundredths)
            assertEquals(p.expected, snapshot(c, p.expected))
        }
        val valid = plan(c).expected
        for (bad in listOf(valid.bands.single().copy(frequencyHz = 0), valid.bands.single().copy(frequencyHz = 20001),
            valid.bands.single().copy(gainTenths = -121), valid.bands.single().copy(gainTenths = 121),
            // Past the KA15 readback drift (1 below 0.10, 5 above 10.00: see the Q readback test); 9 and 1001..1005 parse there.
            valid.bands.single().copy(qHundredths = 0), valid.bands.single().copy(qHundredths = 8), valid.bands.single().copy(qHundredths = 1006),
            valid.bands.single().copy(typeCode = 6))) {
            assertFailsWith<IllegalArgumentException> { c.parseBand(bandReply(bad), 0, 1) }
            assertFailsWith<IllegalArgumentException> { c.importExact(valid.copy(bands = listOf(bad))) }
        }
        assertFailsWith<IllegalArgumentException> { c.parsePreamp(reply(FiioCodec.PREAMP, pair(-121))) }
        assertFailsWith<IllegalArgumentException> { c.parseCount(reply(FiioCodec.COUNT, listOf(255))) }
        for (gain in -120..120) for (q in listOf(10, 1000)) {
            val native = FiioSnapshot("FIIO KA15", 7, 1, 0, listOf(FiioRegisters(0, 1000, gain, q, 0)))
            val eq = c.importExact(native)
            assertTrue(c.matches(native, plan(c, eq.bands, eq.preampDb, 7).expected))
        }
    }
    /** KA15 acoustics measured 04.10.2026 (PROTOCOL.md, FiiO KA15): shelves play register Q / sqrt(2) and the output
     * sits at -12 dB + preamp register, so registers carry intended shelf Q * sqrt(2) and intended preamp + 12 dB. */
    @Test fun `KA15 shelf Q and pregain are written and read in the measured acoustic domain`() {
        val c = codec("FIIO KA15")
        assertEquals(kotlin.math.sqrt(2.0), c.config.shelfQScale)
        assertEquals(12.0, c.config.preampOffsetDb)
        assertFalse(c.config.shelfAlphaCompensation)
        // Only the KA15, the JA11 and the Melody carry the preamp offset (the Tiny A and the K13 R2R measured 0 on 13.11.2026); the shelf Q scale is the KA15's, the Melody's and the Tiny A's (acoustic runs 20.10 and 24.10.2026; the JIEZI takes the Tiny A's, offset 0 included, until step 2; the RETRO NANO measured offset 0 and shelf Q = register), the peak Q scale the K13 R2R's (25.10.2026).
        val shelfScaled = setOf("FIIO KA15", "SNOWSKY Melody", "SNOWSKY TINY A", "JadeAudio JIEZI") // the JIEZI: the Tiny A's scale as a starting assumption, to be measured at step 2; the RETRO NANO measured Q = register (13.11.2026)
        assertTrue(FiioCatalog.rules.all { (it.shelfQScale == kotlin.math.sqrt(2.0)) == (it.productName in shelfScaled) && (it.shelfQScale == 1.0) == (it.productName !in shelfScaled) })
        assertTrue(FiioCatalog.rules.all { (it.peakQScale == kotlin.math.sqrt(2.0)) == (it.productName == "FIIO K13 R2R") && (it.peakQScale == 1.0) == (it.productName != "FIIO K13 R2R") })
        assertTrue(FiioCatalog.rules.filter { it.productName != "FIIO KA15" && it.productName != "JadeAudio JA11" && it.productName != "SNOWSKY Melody" }.all { it.preampOffsetDb == 0.0 })
        assertEquals(12.0, FiioCatalog.rules.single { it.productName == "JadeAudio JA11" }.preampOffsetDb) // the JA11 plays USER presets 12 dB below Close EQ like the KA15 (13.11.2026)
        assertEquals(12.0, FiioCatalog.rules.single { it.productName == "SNOWSKY Melody" }.preampOffsetDb) // the Melody plays USER presets 12 dB below Close EQ like the KA15 and the JA11 (13.11.2026)
        val neutral = FiioCodec(c.config.copy(shelfQScale = 1.0, preampOffsetDb = 0.0))
        assertEquals(71, plan(neutral, listOf(peak().copy(type = FilterType.LOW_SHELF)), 0.0).expected.bands.single().qHundredths)
        for (bad in listOf(c.config.copy(shelfAlphaCompensation = true), c.config.copy(shelfQScale = 0.0),
            c.config.copy(shelfQScale = Double.NaN), c.config.copy(preampOffsetDb = 12.05))) {
            assertFailsWith<IllegalArgumentException> { FiioCodec(bad) }
        }

        // The owner's profiles: shelves x sqrt(2), PK as set, preamp + 12 dB.
        val owner = plan(c, listOf(Band("ls", FilterType.LOW_SHELF, 80.0, 4.0, 0.71), Band("hs", FilterType.HIGH_SHELF, 1000.0, -1.2, 1.40),
            Band("p1", FilterType.PEAK, 120.0, -2.0, 0.71), Band("p2", FilterType.PEAK, 3000.0, 3.0, 3.80),
            Band("p3", FilterType.PEAK, 6000.0, -4.0, 5.30)), -4.0).expected
        assertEquals(listOf(100, 198, 71, 380, 530), owner.bands.map { it.qHundredths })
        assertEquals(listOf(1, 2, 0, 0, 0), owner.bands.map { it.typeCode })
        assertEquals(80, owner.preampTenths)
        assertEquals(90, plan(c, preamp = -3.0).expected.preampTenths)
        assertEquals(110, plan(c, preamp = -1.0).expected.preampTenths)

        // Readback: register Q 100 -> 0.7071, preamp 80 -> -4.0, and the exact registers come back on recompile.
        val stored = FiioSnapshot("FIIO KA15", 7, 1, 80, listOf(FiioRegisters(0, 80, 40, 100, 1)))
        val read = c.importExact(stored)
        assertEquals(0.7071, read.bands.single().q, 1e-4)
        assertEquals(-4.0, read.preampDb)
        assertEquals(stored, plan(c, read.bands, read.preampDb, 7).expected)
        for (type in listOf(1, 2)) for (gain in listOf(-120, -35, 0, 12, 40, 120)) for (q in 10..1000) {
            val native = FiioSnapshot("FIIO KA15", 7, 1, 0, listOf(FiioRegisters(0, 1000, gain, q, type)))
            val eq = c.importExact(native)
            assertEquals(native, plan(c, eq.bands, eq.preampDb, 7).expected, "type $type gain $gain Q $q")
        }
        // Whole dB registers come back exactly; a 0.1 dB register (another app, a stock preset) reads as it is and the next send rounds it down to a whole dB.
        for (r in -120..120) {
            val eq = c.importExact(FiioSnapshot("FIIO KA15", 7, 1, r, listOf(FiioRegisters(0, 1000, 0, 100, 0))))
            assertEquals((r - 120) / 10.0, eq.preampDb, 1e-9)
            assertEquals(Math.floorDiv(r, 10) * 10, plan(c, eq.bands, eq.preampDb, 7).expected.preampTenths, "register $r")
        }

        // Boundaries: shelf Q 7.07 -> register 1000, 7.08 refused with the intended range; PK keeps Q 10.
        val shelf = Band("s", FilterType.HIGH_SHELF, 2000.0, -10.0, 7.07)
        assertEquals(1000, plan(c, listOf(shelf)).expected.bands.single().qHundredths)
        for (s in listOf(shelf.copy(q = 7.08), shelf.copy(type = FilterType.LOW_SHELF, q = 7.08), shelf.copy(q = 10.0))) {
            assertTrue(assertFailsWith<IllegalArgumentException> { plan(c, listOf(s)) }.message!!.contains("0.07..7.07"))
        }
        assertEquals(1000, plan(c, listOf(peak().copy(q = 10.0))).expected.bands.single().qHundredths)
        assertEquals(-120, plan(c, preamp = -24.0).expected.preampTenths)
        assertEquals(120, plan(c, preamp = 0.0).expected.preampTenths)
        for (x in listOf(0.1, -24.1)) {
            assertTrue(assertFailsWith<IllegalArgumentException> { plan(c, preamp = x) }.message!!.contains("[-24.0, 0.0]"))
        }

        // The same limits block HOLD TO SEND with a message before any write (DeviceTarget.issues).
        fun profile(b: Band, preamp: Double?) = Profile("p", "p", bands = listOf(b), preampDb = preamp, createdAt = 0, updatedAt = 0)
        val t = DeviceTarget.KA15
        assertTrue(t.issues(profile(shelf, 0.0)).isEmpty())
        for (q in listOf(10, 1000)) { // the extreme shelf registers read back stay sendable
            val eq = c.importExact(FiioSnapshot("FIIO KA15", 7, 1, -120, listOf(FiioRegisters(0, 2000, -100, q, 2))))
            assertTrue(t.issues(profile(eq.bands.single(), eq.preampDb)).isEmpty())
        }
        assertEquals(listOf("Band 1: shelf Q 7.08 outside 0.07-7.07"), t.issues(profile(shelf.copy(q = 7.08), 0.0)))
        assertTrue(t.issues(profile(peak().copy(q = 10.0), 0.0)).isEmpty())
        assertEquals(listOf("Pregain 0.1 outside -24.0..0.0 dB"), t.issues(profile(peak(), 0.1)))
        assertEquals(listOf("Pregain -24.1 outside -24.0..0.0 dB"), t.issues(profile(peak(), -24.1)))
        assertTrue(t.issues(profile(peak(), -24.0)).isEmpty())
    }
    @Test fun `KA15 keeps ten filters so the tail is padded neutral and Q readback drift is tolerated`() {
        val ka = codec("FIIO KA15")
        val three = List(3) { peak(it).copy(gainDb = -3.0, q = 3.9) }
        val padded = ka.padToDeviceCount(three)
        assertEquals(10, padded.size)
        assertTrue(padded.drop(3).all { it.gainDb == 0.0 && it.type == FilterType.PEAK })
        val p = plan(ka, padded, -3.0)
        assertEquals(10, p.expected.count)
        assertEquals(10, p.writes.count { it.bytes[4] == FiioCodec.BAND })
        val drift = p.expected.copy(bands = p.expected.bands.map { if (it.index < 3) it.copy(qHundredths = it.qHundredths + 2) else it })
        assertTrue(ka.matches(p.expected, drift))
        assertFalse(ka.matches(p.expected, p.expected.copy(bands = p.expected.bands.map { if (it.index == 0) it.copy(qHundredths = it.qHundredths + 40) else it })))
        assertFalse(ka.matches(p.expected, p.expected.copy(bands = p.expected.bands.map { if (it.index == 0) it.copy(gainTenths = -29) else it })))
        // A config without the KA15 hardware flags stays exact and unpadded.
        val c = FiioCodec(ka.config.copy(fixedBandCount = false, qReadbackSlack = false))
        assertEquals(three, c.padToDeviceCount(three))
        val q = plan(c, listOf(peak().copy(q = 3.9))).expected
        assertFalse(c.matches(q, q.copy(bands = q.bands.map { it.copy(qHundredths = it.qHundredths + 1) })))
    }

    /** KA15 USER2 on the Pixel, 21.10.2026: a profile at the Q edges was written, and the DAC read back 0.10 as 0.09, 10.00 as 10.03 and 9.99 as 10.02.
     * The strict range check threw on the read, so that slot could no longer be read, switched to or restored. */
    @Test fun `KA15 reads Q just past the range edges back, matches and imports it clamped, and still refuses to write it`() {
        val ka = codec("FIIO KA15")
        val slot = ka.config.userSlots.first()
        val edges = listOf(peak(0).copy(q = 0.10), peak(1).copy(q = 10.00), peak(2).copy(q = 9.99))
        val expected = plan(ka, edges).expected
        assertEquals(listOf(10, 1000, 999), expected.bands.map { it.qHundredths })
        fun drifted(qs: List<Int>) = FiioSnapshot(expected.productName, slot, 3, expected.preampTenths,
            expected.bands.mapIndexed { i, r -> r.copy(qHundredths = qs[i]) })
        // Parses (snapshot -> parseBand) and validates (matches -> validateSnapshot).
        val read = snapshot(ka, drifted(listOf(9, 1003, 1002)))
        assertEquals(listOf(9, 1003, 1002), read.bands.map { it.qHundredths })
        assertTrue(ka.matches(expected, read))
        // The imported band sits at its edge: the intended value clamped into [0.1, 10.0].
        val imported = ka.importExact(read)
        assertEquals(listOf(0.10, 10.00, 10.00), imported.bands.map { Math.round(it.q * 100) / 100.0 })
        assertTrue(imported.bands.all { it.q in 0.1 - 1e-9..10.0 + 1e-9 })
        // The edge of what the verification accepts: 5 hundredths above 10.00, 1 below 0.10; one more is a mismatch and refused on read.
        assertEquals(1005, snapshot(ka, drifted(listOf(9, 1005, 1002))).bands[1].qHundredths)
        assertFailsWith<IllegalArgumentException> { snapshot(ka, drifted(listOf(8, 1000, 999))) }
        assertFailsWith<IllegalArgumentException> { snapshot(ka, drifted(listOf(10, 1006, 999))) }
        // The write side stays strict: Q 0.09 and 10.03 are never sent.
        assertFailsWith<IllegalArgumentException> { plan(ka, listOf(peak().copy(q = 0.09))) }
        assertFailsWith<IllegalArgumentException> { plan(ka, listOf(peak().copy(q = 10.03))) }
        // A config without the slack keeps the strict read, and no other field gets a tolerance.
        val strict = FiioCodec(ka.config.copy(qReadbackSlack = false))
        assertFailsWith<IllegalArgumentException> { snapshot(strict, drifted(listOf(9, 1000, 999))) }
        assertFailsWith<IllegalArgumentException> { snapshot(strict, drifted(listOf(10, 1003, 999))) }
        assertFailsWith<IllegalArgumentException> { snapshot(ka, drifted(listOf(10, 1000, 999)).let { s -> s.copy(bands = s.bands.map { it.copy(frequencyHz = 19) }) }) }
        // Restoring the clamped band writes registers inside the range, which then verify against the DAC's drifted read.
        val rewrite = ka.planOnExplicitSend(imported.bands, imported.preampDb, slot, explicitUserAction = true)
        assertEquals(listOf(10, 1000, 1000), rewrite.expected.bands.map { it.qHundredths })
        assertTrue(ka.matches(rewrite.expected, read))
    }

    /** The K13 R2R plays a peak at Q = register / sqrt2 (acoustic run 25.10.2026), so peaks go out x sqrt2; its shelves play the register as Q, and the KA15 preamp offset was measured on the KA15 only:
     * shelves and the preamp are raw (register = intended), and its wider -24..+12 dB range and shelf Q up to 10 stay intact. */
    @Test fun `K13 R2R encodes peak Q times sqrt2 and raw shelf Q and raw preamp`() {
        val c = codec("FIIO K13 R2R")
        assertEquals(1.0, c.config.shelfQScale)
        assertEquals(kotlin.math.sqrt(2.0), c.config.peakQScale)
        assertEquals(0.0, c.config.preampOffsetDb)
        assertFalse(c.config.shelfAlphaCompensation)
        val expected = plan(c, listOf(Band("ls", FilterType.LOW_SHELF, 80.0, 4.0, 0.71), Band("hs", FilterType.HIGH_SHELF, 1000.0, -1.2, 1.40),
            Band("p", FilterType.PEAK, 3000.0, 3.0, 3.80), Band("hs2", FilterType.HIGH_SHELF, 9000.0, 2.0, 8.0)), -4.0).expected
        assertEquals(listOf(71, 140, 537, 800), expected.bands.map { it.qHundredths }) // PK 3.80 x sqrt2 = 5.374
        assertEquals(-40, expected.preampTenths) // KA15 would write 80 here (-4 dB + 12 dB)
        assertEquals(0, plan(c, listOf(peak()), 0.0).expected.preampTenths) // KA15: 120
        // Shelf Q 8 and pregain -20 dB are legal on the K13 R2R (KA15: shelf Q <= 7.07, pregain -24..0 after its offset); a peak is held to 7.07 here, shelves are not.
        val t = DeviceTarget.K13
        assertEquals(24.0, -t.preampMin)
        val profile = Profile("p", "p", bands = listOf(Band("hs", FilterType.HIGH_SHELF, 9000.0, 2.0, 8.0)), preampDb = -20.0, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), t.issues(profile))
    }

    /** K13 R2R on the Nothing Phone, 25.10.2026 (run 2026-10-25-fiio-k13-r2r-phone-nothing): the frames were right, but the DAC read the peak registers 990 and 424
     * back as 989 and 423, so a send that had been written whole ended NOT VERIFIED. One step is inside the 1 + q/250 hundredths the config now accepts; more is still a mismatch. */
    @Test fun `K13 R2R accepts the one-step Q readback drift of the phone run and rejects a bigger one`() {
        val k = codec("FIIO K13 R2R")
        assertTrue(k.config.qReadbackSlack)
        val slot = k.config.userSlots.first()
        val bands = k.padToDeviceCount(listOf(peak(0).copy(q = 7.0), peak(1).copy(freqHz = 2000.0, q = 3.0), peak(2).copy(freqHz = 3000.0, q = 1.0)))
        val expected = plan(k, bands).expected
        assertEquals(listOf(990, 424, 141), expected.bands.take(3).map { it.qHundredths }) // PK Q x sqrt2, as the phone run wrote them
        fun drifted(vararg dq: Int) = FiioSnapshot(expected.productName, slot, expected.count, expected.preampTenths,
            expected.bands.mapIndexed { i, r -> r.copy(qHundredths = r.qHundredths + dq.getOrElse(i) { 0 }) })
        // The two readbacks of the run: 990 -> 989 and 424 -> 423, the rest exact.
        val read = snapshot(k, drifted(-1, -1, 0))
        assertEquals(listOf(989, 423, 141), read.bands.take(3).map { it.qHundredths })
        assertTrue(k.matches(expected, read))
        // The tolerance is the family's 1 + q/250: 4 at 990, 2 at 424, 1 at 141; one past it is a mismatch, and so is any other field.
        assertTrue(k.matches(expected, drifted(-4, -2, -1)))
        assertFalse(k.matches(expected, drifted(-5, 0, 0)))
        assertFalse(k.matches(expected, drifted(0, -3, 0)))
        assertFalse(k.matches(expected, drifted(0, 0, -2)))
        assertFalse(k.matches(expected, drifted(0, 0, 0).let { s -> s.copy(bands = s.bands.map { if (it.index == 0) it.copy(gainTenths = it.gainTenths + 1) else it }) }))
        // A K13 config without the slack would have refused the run's own readback.
        assertFalse(FiioCodec(k.config.copy(qReadbackSlack = false)).matches(expected, read))
    }

    /** The K13 R2R carries of the KA15 flags only what its own hardware tests showed (fixed band count 10.10.2026, Q readback drift 25.10.2026) and the slot picker. */
    @Test fun `K13 R2R has the slot picker and of the KA15 hardware flags only the fixed band count and the Q readback drift, the KA15 keeps its named picker`() {
        val k = FiioCatalog.K13; val a = FiioCatalog.KA15
        assertTrue(k.userSlotPicker && !k.userSlotNames)
        assertTrue(k.fixedBandCount)
        assertFalse(k.needsAudioStream || k.consumeWriteEchoes || k.checksumFrames)
        assertTrue(k.qReadbackSlack) // since the phone run 25.10.2026 (990 -> 989, 424 -> 423)
        assertEquals(setOf(160, 161, 162, 163, 164, 165, 166, 167, 168, 169), k.userSlots)
        assertEquals(160, DeviceTarget.K13.destinationSlot)
        assertEquals(240, k.bypassSlot); assertEquals(25, k.saveCommand); assertEquals(7, k.reportId); assertEquals(10, k.maxFilters)
        assertEquals(listOf(-24.0, 12.0, 0.1, 10.0), listOf(k.minGainDb, k.maxGainDb, k.minQ, k.maxQ))
        // KA15 as in 1.4.0: named slots, no separate picker flag, USER1-3, USER1 as the default destination
        assertTrue(a.userSlotNames && !a.userSlotPicker)
        assertTrue(a.needsAudioStream && a.consumeWriteEchoes && a.fixedBandCount && a.qReadbackSlack && a.checksumFrames)
        assertEquals(setOf(7, 8, 9), a.userSlots)
        assertEquals(7, DeviceTarget.KA15.destinationSlot)
        assertEquals(DeviceTarget.KA15, DeviceTarget.find(0x2972, 0x0104))
    }

    /** Reddit 2026-10-04: nine bands plus a tenth of zeros blocked HOLD TO SEND with no reason. The reason is now the first issue. */
    @Test fun `K13 R2R names the band and value that block a send`() {
        val t = DeviceTarget.K13
        val nine = List(9) { peak(it).copy(freqHz = 100.0 * (it + 1), gainDb = 1.0) }
        fun profile(bands: List<Band>, preamp: Double = 0.0) = Profile("p", "p", bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)
        val zero = Band("b9", FilterType.PEAK, 0.0, 0.0, 0.0)
        val issues = t.issues(profile(nine + zero))
        // the empty band is wrong twice (freq 0 and Q 0): both are listed, so fixing the freq does not hide a second block
        assertEquals("Band 10: freq 0 Hz outside 20-20000 Hz", issues.first())
        assertEquals(2, issues.size)
        assertTrue(issues[1].startsWith("Band 10: peak Q 0.00 outside 0.07-7.07"), issues[1]) // a peak's register Q is Q x sqrt2 on the K13 R2R
        assertEquals("BAND 10: FREQ 0 HZ - EDIT BAND", DeviceTarget.blockLabel(issues))
        assertEquals("BAND 1: GAIN 13 DB - EDIT BAND", DeviceTarget.blockLabel(t.issues(profile(listOf(peak().copy(gainDb = 13.0))))))
        assertEquals("BAND 1: PEAK Q 0.05", DeviceTarget.blockLabel(t.issues(profile(listOf(peak().copy(q = 0.05))))))
        assertEquals("PREAMP OUT OF RANGE", DeviceTarget.blockLabel(t.issues(profile(nine, 13.0))))
        assertEquals("BAND 2: FREQ 25000 HZ", DeviceTarget.blockLabel(t.issues(profile(listOf(peak(), peak(1).copy(freqHz = 25000.0))))))
        assertEquals("11 BANDS - MAX 10", DeviceTarget.blockLabel(t.issues(profile(List(11) { peak(it) }))))
        assertEquals("INVALID EQ - EDIT BAND", DeviceTarget.blockLabel(listOf("something else")))
        assertEquals("INVALID EQ - EDIT BAND", DeviceTarget.blockLabel(emptyList()))
        // every problem is listed, not only the first
        assertEquals(4, t.issues(profile(listOf(zero, zero.copy(id = "b10")))).size)
        // the KA15 gets the same precise wording; WalkPlay targets keep their own issue lists
        assertEquals("BAND 1: GAIN 13 DB - EDIT BAND", DeviceTarget.blockLabel(DeviceTarget.KA15.issues(profile(listOf(peak().copy(gainDb = 13.0))))))
    }

    @Test fun `a profile name in another script never becomes a USER slot name`() {
        // Tiny A 07.11.2026: the Chinese edition's default name 配置 4 renamed the owner's slot TEAPROOG to "4"
        assertFalse(FiioCodec.nameFitsSlot("配置 4"))
        assertFalse(FiioCodec.nameFitsSlot("低频增强"))
        assertFalse(FiioCodec.nameFitsSlot("Профиль 2"))
        assertTrue(FiioCodec.nameFitsSlot("TEAPROOG"))
        assertTrue(FiioCodec.nameFitsSlot("Bass boost 2"))
        assertTrue(FiioCodec.nameFitsSlot("Żółć"))
        assertEquals("ZOLC", FiioCodec.slotName("Żółć", 8))
    }

    /** LS 100 Hz +6 dB Q 3 peaks at +11.09 dB in the Q form the FiiO DACs play (+7.16 dB in the slope form): AUTO is -12 dB, and the register is that plus the DAC's own preamp offset. */
    @Test fun `FiiO AUTO uses the Q form the DAC plays - K13 R2R (offset 0) writes register minus 12, KA15 and JA11 (offset 12) write register 0`() {
        val p = Profile("p", "p", bands = listOf(Band("ls", FilterType.LOW_SHELF, 100.0, 6.0, 3.0)), preampDb = null, createdAt = 0, updatedAt = 0)
        for ((target, name, tenths) in listOf(Triple(DeviceTarget.K13, "FIIO K13 R2R", -120), Triple(DeviceTarget.KA15, "FIIO KA15", 0), Triple(DeviceTarget.FIIO_JA11, "JadeAudio JA11", 0))) {
            val c = codec(name)
            assertEquals(ShelfForm.Q, c.config.shelfForm, name)
            assertEquals(-12.0, p.effectivePreampDb(c.config.shelfForm), name)
            assertEquals(-12.0, target.shownPreamp(p), name)
            assertEquals(emptyList(), target.issues(p), name)
            val planned = c.planOnExplicitSend(p.copy(bands = c.padToDeviceCount(p.bands), preampDb = p.effectivePreampDb(c.config.shelfForm)), c.config.userSlots.first(), true)
            assertEquals(tenths, planned.expected.preampTenths, name)
            // the band registers are the same whatever the AUTO value is
            val manual = c.planOnExplicitSend(p.copy(bands = c.padToDeviceCount(p.bands), preampDb = -7.0), c.config.userSlots.first(), true)
            assertEquals(manual.expected.bands, planned.expected.bands, name)
        }
    }
}
