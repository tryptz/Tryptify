package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.Modifier
import kotlin.math.sqrt

/**
 * Test-only dumper: serialises what the hand-written device catalog does today into one JSON text (the golden file
 * `src/test/resources/catalog/golden.json`, see [CatalogGoldenTest]). Nothing here is production code and nothing here
 * decides a value: it only asks the catalog and writes down the answers.
 *
 * What is dumped, for all seven targets (Micro, Max, TRN, KA15, K13 R2R, Melody, Tiny A):
 *  - every field of every FiioConfig (by reflection, so a field added later shows up), every DeviceCapabilities,
 *    the DAC Settings lists (ids, placement, flags, ranges; each read decoder probed with fixed bytes), the preview maps,
 *    the header info reads;
 *  - USB identity: every VID:PID `DeviceTarget.find` knows (scan) and the identities of the generated `device_filter.xml`;
 *  - for a fixed, documented set of profiles per device (see [profiles]): `DeviceTarget.issues` texts, the block label and
 *    the headline, the preamp policy answers, and the hex of every planned report - WalkPlay `DeviceProtocol.writeSequence`
 *    (plus the Micro A/B `temporaryBandSequence`), FiiO `FiioCodec.planOnExplicitSend` frames exactly as the app sends
 *    them (bands padded with `padToDeviceCount`, preamp = `effectivePreampDb(form)`), and the import read-back of each plan;
 *  - the factory flat plans, the TRN/Max/Micro read-side bounds (one-factor probes), the FiiO Q read-back slack, the SnowSky
 *    slot texts, per-slot select/save/name frames and the settings write frames.
 *
 * Everything is sorted or in declaration order, doubles print with their full digits, every FiioCodec is created fresh
 * (its frame sequence counter is the only state), so two dumps of the same catalog are byte-identical.
 */
internal object CatalogDump {
    private val json = Json { prettyPrint = true }

    // ---- small helpers -------------------------------------------------------------------------------------------

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hex(l: List<Int>) = l.joinToString("") { "%02x".format(it) }
    private fun s(x: String): JsonElement = JsonPrimitive(x)
    private fun num(d: Double): JsonElement = if (d.isFinite()) JsonPrimitive(d) else JsonPrimitive(d.toString())
    private fun strs(items: Iterable<String>): JsonElement = JsonArray(items.map { JsonPrimitive(it) })
    private fun obj(vararg p: Pair<String, JsonElement>): JsonElement = JsonObject(linkedMapOf(*p))
    private fun arr(items: Iterable<JsonElement>): JsonElement = JsonArray(items.toList())
    private fun d(x: Double) = x.toString()

    /** A computation that may throw: the exception class and message are part of the catalog's behaviour. */
    private fun catching(f: () -> JsonElement): JsonElement =
        try { f() } catch (e: Throwable) { obj("error" to s("${e.javaClass.simpleName}: ${e.message}")) }

    private val keyOrder = Comparator<Any?> { a, b ->
        when {
            a is Int && b is Int -> a.compareTo(b)
            a is Enum<*> && b is Enum<*> -> a.ordinal.compareTo(b.ordinal)
            else -> a.toString().compareTo(b.toString())
        }
    }

    /** Generic value to JSON: primitives, enums by name, sets sorted, maps sorted by key, data classes by reflection (fields sorted by name). */
    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> num(v)
        is Enum<*> -> JsonPrimitive(v.name)
        is Set<*> -> JsonArray(v.sortedWith(keyOrder).map { toJson(it) })
        is List<*> -> JsonArray(v.map { toJson(it) })
        is Map<*, *> -> JsonObject(v.entries.sortedWith { x, y -> keyOrder.compare(x.key, y.key) }.associate { it.key.toString() to toJson(it.value) })
        is DacSetting -> dacSetting(v)
        is DacInfo -> dacInfo(v)
        is Function<*> -> JsonPrimitive("<function>")
        else -> {
            // an optional field that is null (or an optional flag that is false) is left out, so a field added later for one device (DeviceCapabilities.shelfLimits, .wrapQ30) leaves every other device's entry as it was
            val fields = v.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .filter { f -> f.name != "shelfLimits" || run { f.isAccessible = true; f.get(v) != null } }
                .filter { f -> f.name != "wrapQ30" || run { f.isAccessible = true; f.get(v) == true } }
                .filter { f -> f.name != "bypassPreampAnswersAsCount" || run { f.isAccessible = true; f.get(v) == true } }.sortedBy { it.name }
            JsonObject(fields.associate { f -> f.isAccessible = true; f.name to toJson(f.get(v)) })
        }
    }

    // ---- DAC Settings --------------------------------------------------------------------------------------------

    private fun probe(vararg v: Pair<Int, Int>): List<Pair<String, ByteArray>> = v.map { (i, x) -> "a$i=$x" to ByteArray(12).also { a -> a[i] = x.toByte() } }
    private val settingProbes: List<Pair<String, ByteArray>> =
        listOf(0, 1, 2, 3, 4, 5, 6, 7, 59, 60, 61, 119, 120, 121, 200, 255).map { x -> "a6=$x" to ByteArray(12).also { it[6] = x.toByte() } } +
            listOf(0 to 3, 3 to 0, 2 to 2, 0 to 12, 12 to 0, 255 to 255).map { (l, r) -> "a6=$l,a7=$r" to ByteArray(12).also { it[6] = l.toByte(); it[7] = r.toByte() } }
    private val infoProbes: List<Pair<String, ByteArray>> =
        listOf(Triple(0, 0, 0), Triple(1, 1, 6), Triple(1, 2, 0), Triple(2, 0, 0), Triple(14, 0, 0), Triple(255, 255, 255)).map { (x, y, z) ->
            "a6=$x,a7=$y,a8=$z" to ByteArray(12).also { it[6] = x.toByte(); it[7] = y.toByte(); it[8] = z.toByte() }
        }
    private val formatProbes = listOf(-1, 0, 1, 2, 3, 10, 14, 15, 16, 255, 10000, 10106, 10200, 30000)

    private fun dacSetting(x: DacSetting): JsonElement {
        val read = x.read
        val base = obj(
            "id" to s(x.id), "label" to s(x.label), "kind" to toJson(x.kind), "evidence" to toJson(x.evidence),
            "options" to strs(x.options), "shortOptions" to strs(x.shortOptions),
            "min" to toJson(x.min), "max" to toJson(x.max), "step" to toJson(x.step), "unit" to s(x.unit),
            "group" to toJson(x.group), "section" to toJson(x.section),
            "readCommand" to toJson(x.readCommand),
            "read" to when (read) {
                null -> JsonNull
                is SettingRead.FiioGet -> obj("type" to s("FiioGet"), "command" to toJson(read.command), "replyData" to toJson(read.replyData))
                is SettingRead.WalkPlayGet -> obj("type" to s("WalkPlayGet"), "command" to toJson(read.command))
            },
            "readProbe" to strs(settingProbes.map { (label, a) ->
                val r = when (read) {
                    null -> "no-read"
                    is SettingRead.FiioGet -> catching0 { read.decode(a).toString() }
                    is SettingRead.WalkPlayGet -> catching0 { read.decode(a).toString() }
                }
                "$label -> $r"
            }),
            "writeCommand" to toJson(x.writeCommand), "writeVerified" to toJson(x.writeVerified),
            "maxRule" to (x.maxRule?.let { obj("settingId" to s(it.settingId), "maxima" to toJson(it.maxima)) } ?: JsonNull),
            "longChoice" to toJson(x.longChoice),
        )
        // a row that makes the DAC leave the bus says so; an optional flag that is false stays out, so every other device's rows are as they were
        return if (!x.reconnectsAfterWrite) base else JsonObject((base as JsonObject) + ("reconnectsAfterWrite" to toJson(true)))
    }

    private fun catching0(f: () -> String): String = try { f() } catch (e: Throwable) { "error ${e.javaClass.simpleName}: ${e.message}" }

    private fun dacInfo(x: DacInfo): JsonElement = obj(
        "id" to s(x.id), "command" to toJson(x.command), "replyData" to toJson(x.replyData),
        "decodeProbe" to strs(infoProbes.map { (label, a) -> "$label -> ${catching0 { x.decode(a).toString() }}" }),
        "formatProbe" to strs(formatProbes.map { "$it -> ${catching0 { x.format(it) }}" }),
    )

    // ---- profile set ---------------------------------------------------------------------------------------------

    /**
     * The numbers the profile set is built from, per device. They are literals on purpose (not read from the catalog): if a
     * generated catalog ever widens or narrows a range, the "edge" profiles stay where they are and the plans change.
     * KA15, Melody, Tiny A and TRN shelf Q ceiling and K13 peak Q ceiling are 10 / sqrt(2) (register Q = Q * sqrt(2), `FiioConfig.shelfQScale`, `FiioConfig.peakQScale`, `DeviceCapabilities.shelfQScale`).
     */
    private class Spec(
        val bands: Int, val gainLo: Double, val gainHi: Double, val qLo: Double, val qHi: Double, val shelfQHi: Double,
        val freqLo: Double, val freqHi: Double, val offered: List<FilterType>, val preampLo: Double, val preampHi: Double,
    )

    private val THREE = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF)
    private val SEVEN = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.BAND_PASS, FilterType.LOW_PASS, FilterType.HIGH_PASS, FilterType.ALL_PASS)
    private val specs: Map<String, Spec> = linkedMapOf(
        "MICRO" to Spec(8, -10.0, 10.0, 0.1, 10.0, 10.0, 20.0, 20000.0, THREE, -30.0, 0.0),
        "MAX" to Spec(10, -10.0, 10.0, 0.1, 10.0, 10.0, 20.0, 20000.0, THREE, -30.0, 0.0),
        "TRN" to Spec(10, -10.0, 10.0, 0.1, 10.0, 10.0 / sqrt(2.0), 20.0, 20000.0, THREE, -30.0, 0.0),
        "KA15" to Spec(10, -12.0, 12.0, 0.1, 10.0, 10.0 / sqrt(2.0), 20.0, 20000.0, THREE, -24.0, 0.0),
        "K13" to Spec(10, -24.0, 12.0, 0.1, 10.0 / sqrt(2.0), 10.0, 20.0, 20000.0, SEVEN, -24.0, 12.0),
        "MELODY" to Spec(10, -12.0, 12.0, 0.1, 10.0, 10.0 / sqrt(2.0), 20.0, 20000.0, THREE, -24.0, 0.0),
        "TINY_A" to Spec(10, -12.0, 12.0, 0.1, 10.0, 10.0 / sqrt(2.0), 20.0, 20000.0, SEVEN, -12.0, 12.0),
    )

    private val FULL_FREQS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private val FULL_GAINS = doubleArrayOf(2.5, -1.5, 3.0, -2.0, 1.0, -3.0, 2.0, -1.0, 1.5, -2.5)
    private val FULL_QS = doubleArrayOf(0.7, 1.0, 1.4, 2.0, 0.9, 3.0, 1.1, 0.8, 1.6, 2.5)

    private fun band(i: Int, type: FilterType, f: Double, g: Double, q: Double, enabled: Boolean = true) = Band("b$i", type, f, g, q, enabled)
    private fun profile(name: String, bands: List<Band>, preamp: Double?) = Profile(name, name, bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)

    /** The documented profile set. Names say what each one probes; the same set (with the device's own numbers) is run on every device. */
    private fun profiles(sp: Spec): List<Profile> {
        val out = ArrayList<Profile>()
        fun one(name: String, type: FilterType, f: Double, g: Double, q: Double, preamp: Double?) = out.add(profile(name, listOf(band(0, type, f, g, q)), preamp))
        val pk = FilterType.PEAK
        // preamp: manual, auto, edges, outside, off the 0.1 grid
        one("one-peak-zero-manual", pk, 1000.0, 0.0, 1.0, 0.0)
        one("one-peak-boost-auto", pk, 1000.0, 3.0, 1.0, null)
        one("one-peak-cut-preamp-min", pk, 1000.0, -3.0, 1.0, sp.preampLo)
        one("preamp-above-max", pk, 1000.0, 0.0, 1.0, sp.preampHi + 1.0)
        one("preamp-below-min", pk, 1000.0, 0.0, 1.0, sp.preampLo - 1.0)
        one("preamp-just-above-max", pk, 1000.0, 0.0, 1.0, sp.preampHi + 0.1)
        one("preamp-off-grid-manual", pk, 1000.0, -2.0, 1.0, -3.47)
        // gain edges and just outside
        one("gain-max-auto", pk, 1000.0, sp.gainHi, 1.0, null)
        one("gain-min-manual0", pk, 1000.0, sp.gainLo, 1.0, 0.0)
        one("gain-over-max", pk, 1000.0, sp.gainHi + 0.1, 1.0, 0.0)
        one("gain-under-min", pk, 1000.0, sp.gainLo - 0.1, 1.0, 0.0)
        // Q edges and just outside (gain 0: no biquad overflow, the edge itself is what is probed)
        one("q-min", pk, 1000.0, 0.0, sp.qLo, 0.0)
        one("q-max", pk, 1000.0, 0.0, sp.qHi, 0.0)
        one("q-over-max", pk, 1000.0, 0.0, sp.qHi + 0.1, 0.0)
        one("q-under-min", pk, 1000.0, 0.0, sp.qLo - 0.05, 0.0)
        // frequency edges and just outside
        one("freq-min", pk, sp.freqLo, 0.0, 1.0, 0.0)
        one("freq-max", pk, sp.freqHi, 0.0, 1.0, 0.0)
        one("freq-over-max", pk, sp.freqHi + 1.0, 0.0, 1.0, 0.0)
        one("freq-under-min", pk, sp.freqLo - 1.0, 0.0, 1.0, 0.0)
        // every shape, offered or not (a refusal text is behaviour too)
        for (t in FilterType.entries) one("type-${t.name}", t, 1000.0, if (t.isPass) 0.0 else 3.0, 1.0, -3.0)
        // shelf Q ceiling (KA15: Q register = Q * sqrt(2)) and just above it
        for (t in listOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF)) {
            one("shelf-q-ceiling-${t.name}", t, 1000.0, 2.0, sp.shelfQHi, -2.0)
            one("shelf-q-over-ceiling-${t.name}", t, 1000.0, 2.0, sp.shelfQHi + 0.01, -2.0)
        }
        // pass filters keep a stored gain nobody sends
        for (t in listOf(FilterType.LOW_PASS, FilterType.ALL_PASS)) one("pass-stored-gain-${t.name}", t, 1000.0, sp.gainHi + 5.0, 0.7, 0.0)
        // high shelf (Micro emulates it as an inverted low shelf plus preamp), auto and manual
        out.add(profile("hs-plus-peak-auto", listOf(band(0, FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7), band(1, pk, 1000.0, -2.0, 1.0)), null))
        out.add(profile("hs-plus-peak-manual", listOf(band(0, FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7), band(1, pk, 1000.0, -2.0, 1.0)), -2.5))
        out.add(profile("overlapping-boosts-auto", listOf(band(0, pk, 100.0, 6.0, 0.5), band(1, pk, 120.0, 6.0, 0.5), band(2, FilterType.LOW_SHELF, 80.0, 4.0, 0.7)), null))
        // band lists: empty, one disabled, full, mixed, one too many
        out.add(profile("empty-manual0", emptyList(), 0.0))
        out.add(profile("empty-auto", emptyList(), null))
        out.add(profile("two-bands-one-disabled-auto", listOf(band(0, pk, 1000.0, 3.0, 1.0), band(1, pk, 2000.0, 6.0, 1.0, enabled = false)), null))
        out.add(profile("full-peaks-auto", List(sp.bands) { band(it, pk, maxOf(FULL_FREQS[it], sp.freqLo), FULL_GAINS[it], FULL_QS[it]) }, null))
        out.add(profile("full-peaks-manual", List(sp.bands) { band(it, pk, maxOf(FULL_FREQS[it], sp.freqLo), FULL_GAINS[it], FULL_QS[it]) }, -6.0))
        out.add(profile("full-mixed-types-manual", List(sp.bands) {
            val t = sp.offered[it % sp.offered.size]
            band(it, t, maxOf(FULL_FREQS[it], sp.freqLo), if (t.isPass) 0.0 else FULL_GAINS[it], FULL_QS[it])
        }, -6.0))
        out.add(profile("full-mixed-types-auto", List(sp.bands) {
            val t = sp.offered[(it + 1) % sp.offered.size]
            band(it, t, maxOf(FULL_FREQS[it], sp.freqLo), if (t.isPass) 0.0 else FULL_GAINS[it], FULL_QS[it])
        }, null))
        out.add(profile("one-band-too-many", List(sp.bands + 1) { band(it, pk, FULL_FREQS[it % FULL_FREQS.size] + it, 0.0, 1.0) }, 0.0))
        return out
    }

    /** Profiles whose plan is also dumped for a second slot value (WalkPlay slot byte) and, for WalkPlay, as the Micro A/B temporary sequence. */
    private val extraSequence = setOf("one-peak-boost-auto", "full-mixed-types-manual")

    // ---- WalkPlay ------------------------------------------------------------------------------------------------

    private fun regs(r: WalkPlay.Registers) = "${r.index}:${r.freq}:${r.q256}:${r.gain256}:${r.typeCode}"
    private fun write(w: WalkPlay.BandWrite) = "${w.index}:${d(w.freq)}:${d(w.gainDb)}:${d(w.q)}:${w.typeCode}"
    private fun steps(l: List<WalkPlay.Step>) = strs(l.map { "${hex(it.report)} +${it.delayAfterMs}" })

    private fun readReply(w: WalkPlay.BandWrite, slot: Int): ByteArray =
        WalkPlay.bandWriteReport(w, slot, WalkPlay.TRN_BAND_LEN).also { it[1] = WalkPlay.READ.toByte(); it[3] = 0 }

    private fun importJson(p: DeviceProtocol, plan: DevicePlan.Ready): JsonElement = catching {
        val read = plan.bands.indices.map { p.parseBand(readReply(plan.bands[it], 0), it) }
        when (val r = p.importExact(read, plan.preampDb)) {
            is DacImport.Ready -> obj("ready" to strs(r.eq.bands.map { "${it.type} ${d(it.freqHz)} ${d(it.gainDb)} ${d(it.q)} ${it.enabled}" }),
                "preampDb" to toJson(r.eq.preampDb), "warning" to toJson(r.warning))
            is DacImport.Rejected -> obj("rejected" to strs(r.issues))
        }
    }

    private fun walkplayPlan(p: DeviceProtocol, profile: Profile, extra: Boolean): JsonElement = when (val plan = p.plan(profile)) {
        is DevicePlan.Rejected -> obj("status" to s("rejected"), "issues" to strs(plan.issues))
        is DevicePlan.Ready -> catching {
            val entries = linkedMapOf<String, JsonElement>(
                "status" to s("ready"), "preampDb" to toJson(plan.preampDb),
                "bandWrites" to strs(plan.bands.map { write(it) }), "registers" to strs(plan.bands.map { regs(it.registers()) }),
                "selfMatch" to toJson(p.matches(plan, plan.bands.map { it.registers() }, plan.preampDb)),
                "sequenceSlot0" to steps(p.writeSequence(plan, 0)),
                "import" to importJson(p, plan),
            )
            if (extra) {
                entries["sequenceSlot101"] = steps(p.writeSequence(plan, 101))
                entries["temporaryBandSequenceSlot0"] = steps(WalkPlay.temporaryBandSequence(plan.bands, 0))
            }
            JsonObject(entries)
        }
    }

    private fun preampPolicy(p: DeviceProtocol, profile: Profile): JsonElement = obj(
        "devicePreamp" to catching { toJson(p.devicePreamp(profile.bands, profile.preampDb)) },
        "shownPreamp" to catching { num(p.shownPreamp(profile)) },
        "shelfOffset" to catching { num(p.shelfOffset(profile.bands)) },
        "manualForAuto" to catching { toJson(p.manualForAuto(profile.bands)) },
        "autoRefusal" to catching { toJson(p.autoRefusal(profile.bands)) },
        "explicit" to (profile.preampDb?.let { v ->
            obj("fits" to catching { toJson(p.preampFits(profile.bands, v)) }, "refusal" to catching { toJson(p.preampRefusal(profile.bands.filter { it.enabled }, v)) },
                "fitImported" to catching { num(p.fitImportedPreamp(profile.bands, v)) })
        } ?: JsonNull),
    )

    /** One-factor-at-a-time read replies around a plain band: where each protocol's strict read starts to refuse. */
    private fun readProbes(p: DeviceProtocol): JsonElement {
        fun reply(freq: Int, q256: Int, gain256: Int, type: Int): ByteArray = ByteArray(WalkPlay.REPORT_SIZE).also {
            it[0] = WalkPlay.REPORT_ID.toByte(); it[1] = WalkPlay.READ.toByte(); it[2] = WalkPlay.CMD_PEQ.toByte(); it[5] = 3
            it[28] = (freq and 255).toByte(); it[29] = ((freq shr 8) and 255).toByte()
            it[30] = (q256 and 255).toByte(); it[31] = ((q256 shr 8) and 255).toByte()
            it[32] = (gain256 and 255).toByte(); it[33] = ((gain256 shr 8) and 255).toByte()
            it[34] = type.toByte(); it[36] = 0
        }
        val probes = ArrayList<Pair<String, ByteArray>>()
        for (f in listOf(0, 19, 20, 1000, 20000, 20001, 48000, 48001, 65535)) probes += "freq=$f" to reply(f, 256, 0, 2)
        for (g in listOf(0, 2560, 2561, -2560, -2561, 15360, 15361, -15360, -15361)) probes += "gain256=$g" to reply(1000, 256, g, 2)
        for (q in listOf(0, 25, 26, 255, 256, 2560, 2561, 65535)) probes += "q256=$q" to reply(1000, q, 0, 2)
        for (t in 0..7) probes += "type=$t" to reply(1000, 256, 0, t)
        return strs(probes.map { (label, buf) ->
            "$label -> " + catching0 {
                val b = p.parseBand(buf, 3)
                "enabled=${b.enabled} type=${b.type} freq=${d(b.freqHz)} gain=${d(b.gainDb)} q=${d(b.q)} regs=${regs(b.registers)}"
            }
        })
    }

    private fun walkplayJson(name: String, p: DeviceProtocol, sp: Spec): JsonElement {
        val list = profiles(sp)
        return obj(
            "entry" to s(p.name), "experimentalStrictPath" to toJson(p.experimental), "supportsAb" to toJson(p.supportsAb),
            "preampMin" to toJson(p.preampMin), "preampMax" to toJson(p.preampMax), "settings" to strs(p.settings.map { it.id }),
            "flatPlan" to walkplayPlan(p, profile("flat", emptyList(), 0.0), true).let { _ ->
                val flat = p.flatPlan()
                obj("preampDb" to toJson(flat.preampDb), "bandWrites" to strs(flat.bands.map { write(it) }), "registers" to strs(flat.bands.map { regs(it.registers()) }),
                    "sequenceSlot0" to steps(p.writeSequence(flat, 0)), "isFlat" to toJson(p.matches(flat, flat.bands.map { it.registers() }, 0)))
            },
            "readProbes" to readProbes(p),
            "profiles" to JsonObject(list.associate { pr ->
                pr.name to obj(
                    "profile" to profileJson(pr),
                    "issues" to catching { strs(DeviceTarget.of(p).issues(pr)) },
                    "blockLabel" to catching { s(DeviceTarget.blockLabel(DeviceTarget.of(p).issues(pr))) },
                    "headline" to catching { s(DeviceTarget.of(p).invalidEqHeadline(pr)) },
                    "preampPolicy" to preampPolicy(p, pr),
                    "plan" to walkplayPlan(p, pr, pr.name in extraSequence),
                )
            }),
        )
    }

    // ---- FiiO ----------------------------------------------------------------------------------------------------

    private fun frame(f: FiioFrame) = "${f.effect} ${"%02x".format(f.reportId)} ${hex(f.bytes)}"
    private fun fiioRegs(r: FiioRegisters) = "${r.index}:${r.frequencyHz}:${r.gainTenths}:${r.qHundredths}:${r.typeCode}"
    private fun snapshot(s: FiioSnapshot) = obj("productName" to s(s.productName), "activeSlot" to toJson(s.activeSlot), "count" to toJson(s.count),
        "preampTenths" to toJson(s.preampTenths), "bands" to strs(s.bands.map { fiioRegs(it) }))

    private fun fiioPlan(c: FiioConfig, profile: Profile, slot: Int): JsonElement = catching {
        val codec = FiioCodec(c)
        val padded = codec.padToDeviceCount(profile.bands)
        val preamp = profile.effectivePreampDb(c.shelfForm)
        val plan = codec.planOnExplicitSend(profile.copy(bands = padded, preampDb = preamp), slot, true)
        obj(
            "status" to s("ready"), "targetSlot" to toJson(plan.targetSlot), "paddedBands" to toJson(padded.size), "preampDbSent" to num(preamp),
            "expected" to snapshot(plan.expected),
            "selection" to s(frame(plan.selection)), "selectionVerification" to s(frame(plan.selectionVerification)),
            "backupQueries" to strs(plan.backupQueries.map { frame(it) }),
            "writes" to strs(plan.writes.map { frame(it) }), "save" to s(frame(plan.save)),
            "verificationQueries" to strs(plan.verificationQueries.map { frame(it) }),
            "saveVerification" to toJson(plan.saveVerification),
            "delaysMs" to obj("afterCount" to toJson(plan.delayAfterCountMs), "beforeSave" to toJson(plan.delayBeforeSaveMs),
                "afterSelect" to toJson(plan.delayAfterSelectMs), "afterSave" to toJson(plan.delayAfterSaveMs)),
            "import" to catching {
                val imp = FiioCodec(c).importExact(plan.expected)
                obj("bands" to strs(imp.bands.map { "${it.type} ${d(it.freqHz)} ${d(it.gainDb)} ${d(it.q)} ${it.enabled}" }), "preampDb" to num(imp.preampDb))
            },
        )
    }

    private fun profileJson(p: Profile): JsonElement = obj(
        "name" to s(p.name),
        "bands" to strs(p.bands.map { "${it.id} ${it.type} ${d(it.freqHz)} Hz ${d(it.gainDb)} dB Q ${d(it.q)} enabled=${it.enabled}" }),
        "preampDb" to (p.preampDb?.let { num(it) } ?: JsonNull),
    )

    private fun slotText(c: FiioConfig): JsonElement {
        if (!FiioMelodyExchange.appliesTo(c)) return JsonNull
        val codec = FiioCodec(c)
        return strs(listOf(0, 6, 7, 8, 9, 10, 100, 159, 160, 161, 162, 163, 240, 255).map { slot ->
            "$slot -> ${NativeState.Fiio(codec, FiioSnapshot(c.productName, slot, 0, 0, emptyList())).slotDescription}"
        })
    }

    private fun slotFrames(c: FiioConfig): JsonElement = JsonObject(c.userSlots.sorted().associate { slot ->
        slot.toString() to catching {
            val zero = profile("slot-frames", listOf(band(0, FilterType.PEAK, 1000.0, 0.0, 1.0)), 0.0)
            val codec = FiioCodec(c)
            val plan = codec.planOnExplicitSend(zero.copy(bands = codec.padToDeviceCount(zero.bands)), slot, true)
            obj(
                "selection" to s(frame(plan.selection)), "save" to s(frame(plan.save)),
                "queryName" to catching { s(frame(FiioCodec(c).queryName(slot))) },
                "writeNameAB1" to catching { s(frame(FiioCodec(c).writeName(slot, "AB1"))) },
                "writeNameEightChars" to catching { s(frame(FiioCodec(c).writeName(slot, "ABCDEFGH"))) },
            )
        }
    })

    /** FiioCodec.matches with a Q that drifted by k hundredths: the slack rule (1 + q/250) per config. */
    private fun qSlack(c: FiioConfig): JsonElement {
        val out = ArrayList<String>()
        for (base in listOf(100, 390, 605, 1000)) for (k in 0..6) out += catching0 {
            fun snap(q: Int) = FiioSnapshot(c.productName, c.userSlots.min(), 1, 0, listOf(FiioRegisters(0, 1000, 0, q, 0)))
            val ok = FiioCodec(c).matches(snap(base), snap(minOf(base + k, 1000)))
            val ko = if (base - k >= 10) FiioCodec(c).matches(snap(base), snap(base - k)) else null
            "q=$base drift=$k up=$ok down=$ko"
        }
        return strs(out)
    }

    private fun settingFrames(c: FiioConfig): JsonElement = JsonObject(c.settings.associate { st ->
        val values = when (st.kind) {
            SettingKind.TOGGLE, SettingKind.CHOICE -> listOf(0, st.options.lastIndex, st.options.size)
            SettingKind.LEVEL -> listOf(st.min, DacWrite.hardMax(st), DacWrite.hardMax(st) + 1)
            SettingKind.BALANCE -> listOf(-st.max, 0, st.max, st.max + 1)
        }
        st.id to strs(values.map { v -> "$v -> " + catching0 { frame(FiioCodec(c).setSetting(st, v)) } })
    })

    private fun fiioJson(name: String, t: DeviceTarget, c: FiioConfig, sp: Spec): JsonElement {
        val list = profiles(sp)
        val firstSlot = t.destinationSlot!!
        return obj(
            "config" to toJson(c),
            "computed" to obj("preampMinDb" to num(c.preampMinDb), "preampMaxDb" to num(c.preampMaxDb), "shelfQMin" to num(c.shelfQMin), "shelfQMax" to num(c.shelfQMax),
                "hardwareGates" to strs(c.hardwareGates), "destinationSlot" to toJson(t.destinationSlot),
                "snowskyExchange" to toJson(FiioMelodyExchange.appliesTo(c)), "exchangeName" to s(FiioMelodyExchange.name(c)),
                "probeGapMsEffective" to toJson(ProbePlan.fiioGapMs(c)),
                "capturedRoute" to toJson(c.capture?.let { FiioCatalog.capturedRoute(it.vendorId, it.productId, it.productName)?.productName })),
            "slotText" to slotText(c),
            "slotFrames" to slotFrames(c),
            "qReadbackSlack" to qSlack(c),
            "settingWriteFrames" to settingFrames(c),
            "profiles" to JsonObject(list.associate { pr ->
                pr.name to obj(
                    "profile" to profileJson(pr),
                    "issues" to catching { strs(t.issues(pr)) },
                    "blockLabel" to catching { s(DeviceTarget.blockLabel(t.issues(pr))) },
                    "headline" to catching { s(t.invalidEqHeadline(pr)) },
                    "preampFits" to (pr.preampDb?.let { v -> catching { toJson(t.preampFits(pr.bands, v)) } } ?: JsonNull),
                    "shownPreamp" to catching { num(t.shownPreamp(pr)) },
                    "plan" to fiioPlan(c, pr, firstSlot),
                )
            }),
        )
    }

    // ---- targets and identity ------------------------------------------------------------------------------------

    private fun targetJson(name: String, t: DeviceTarget): JsonElement {
        val sp = specs.getValue(name)
        val protocol = t.walkplay
        val fiio = t.fiio
        return obj(
            "caps" to toJson(t.caps),
            "flags" to obj("stable" to toJson(t.stable), "native" to toJson(t.native), "experimental" to toJson(t.experimental), "supportsAb" to toJson(t.supportsAb),
                "preampMin" to num(t.preampMin), "preampMax" to num(t.preampMax), "preampStep" to num(t.preampStep), "destinationSlot" to toJson(t.destinationSlot),
                "settingsCount" to toJson(t.settings.size), "infoCount" to toJson(t.info.size)),
            "qRange" to JsonObject(FilterType.entries.associate { ft -> ft.name to s("${d(t.qRange(ft).start)}..${d(t.qRange(ft).endInclusive)}") }),
            "profileSpec" to obj("bands" to toJson(sp.bands), "gain" to s("${d(sp.gainLo)}..${d(sp.gainHi)}"), "q" to s("${d(sp.qLo)}..${d(sp.qHi)}"),
                "shelfQCeiling" to num(sp.shelfQHi), "freq" to s("${d(sp.freqLo)}..${d(sp.freqHi)}"), "offered" to toJson(sp.offered),
                "preamp" to s("${d(sp.preampLo)}..${d(sp.preampHi)}")),
            "walkplay" to (protocol?.let { walkplayJson(name, it, sp) } ?: JsonNull),
            "fiio" to (fiio?.let { fiioJson(name, t, it, sp) } ?: JsonNull),
        )
    }

    /** VID, PID and USB product name asked of `DeviceTarget.findDevice`: the ten identities, name misses of the two name-matched ones, and 1.5 devices that need no name. */
    private val findDeviceProbes: List<Triple<Int, Int, String?>> = listOf(
        Triple(0x3302, 0xC20F, null), Triple(0x3302, 0x43CC, null), Triple(0x3302, 0x43E8, null), Triple(0x2972, 0x0104, null), Triple(0x2972, 0x0120, null),
        Triple(0x2972, 0x0126, null), Triple(0x2972, 0x0147, null),
        Triple(0x35D8, 0x011D, "DAWN PRO2"), Triple(0x35D8, 0x011D, "DAWN PRO 2"), Triple(0x35D8, 0x011D, null), Triple(0x35D8, 0x011E, "DAWN PRO2"),
        Triple(0x31B2, 0x0111, "Kiwi Ears-Allegro Mini"), Triple(0x31B2, 0x0111, "Kiwi Ears Allegro Mini"), Triple(0x31B2, 0x0111, "Kiwi Ears-Allegro PRO"),
        Triple(0x31B2, 0x0111, null), Triple(0x31B2, 0x0112, "Kiwi Ears-Allegro Mini"),
        Triple(0x3302, 0x12C5, null), Triple(0x3302, 0x12C5, "ECHO-A"), Triple(0x3302, 0x12C6, "ECHO-A"), Triple(0x0000, 0x0000, null),
        // appended with the JCally JM12 (the same VID:PID as the Allegro Mini, told apart by the USB name)
        Triple(0x31B2, 0x0111, "KT02H20 HIFI Audio"), Triple(0x31B2, 0x0111, "KT02H20 HiFi Audio"), Triple(0x31B2, 0x0111, "KT02H20"), Triple(0x31B2, 0x0112, "KT02H20 HIFI Audio"),
        // appended with the FiiO JadeAudio JA11 (found by VID, PID and the exact USB name)
        Triple(0x2972, 0x0102, "JadeAudio JA11"), Triple(0x2972, 0x0102, "JadeAudio JA11 "), Triple(0x2972, 0x0102, "JADEAUDIO JA11"), Triple(0x2972, 0x0102, null), Triple(0x2972, 0x0103, "JadeAudio JA11"),
        // appended with the Moondrop DUSK-SP (found by VID, PID and the exact USB name; its sister cables MAY 1497 and FreeDSP 1496 are not in Contour)
        Triple(0x35D8, 0x1499, "DUSK-SP"), Triple(0x35D8, 0x1499, "DUSK-SP "), Triple(0x35D8, 0x1499, "dusk-sp"), Triple(0x35D8, 0x1499, null), Triple(0x35D8, 0x1497, "DUSK-SP"), Triple(0x35D8, 0x1499, "MAY"),
        // appended with the SnowSky RETRO NANO (VID 0A12, the generic Bluetooth-chip id) and the FiiO JIEZI (both found by VID, PID and the exact USB name)
        Triple(0x0A12, 0x4007, "RETRO NANO"), Triple(0x0A12, 0x4007, "RETRO NANO "), Triple(0x0A12, 0x4007, "Retro Nano"), Triple(0x0A12, 0x4007, null), Triple(0x0A12, 0x4005, "RETRO NANO"), Triple(0x2972, 0x4007, "RETRO NANO"),
        Triple(0x2972, 0x0151, "JadeAudio JIEZI"), Triple(0x2972, 0x0151, "JADEAUDIO JIEZI"), Triple(0x2972, 0x0151, null), Triple(0x2972, 0x0152, "JadeAudio JIEZI"), Triple(0x2972, 0x0151, "JadeAudio JA11"),
        // appended with the Kiwi Ears AD1 (3302:20E3, the WalkPlay VID, a KT register DAC) and the Tanchjim Space II (3302:4381): both found by VID, PID and the exact USB name
        Triple(0x3302, 0x20E3, "AD1"), Triple(0x3302, 0x20E3, "ad1"), Triple(0x3302, 0x20E3, null), Triple(0x3302, 0x20E4, "AD1"),
        Triple(0x3302, 0x4381, "TANCHJIM-SPACE II"), Triple(0x3302, 0x4381, "TANCHJIM-SPACE PRO"), Triple(0x3302, 0x4381, null), Triple(0x3302, 0x4380, "TANCHJIM-SPACE II"),
        // appended with the EPZ TP65 (3302:4325, an experimental tester entry found by its exact USB name)
        Triple(0x3302, 0x4325, "TP65"), Triple(0x3302, 0x4325, "tp65"), Triple(0x3302, 0x4325, null), Triple(0x3302, 0x4326, "TP65"),
    )

    private fun identity(): JsonElement {
        val vendors = listOf(0x3302, 0x2972, 0x0A12, 0x0BDA, 0x31B2, 0x152A, 0x35D8, 0x262A, 0x0000, 0xFFFF)
        val found = sortedMapOf<String, String>()
        for (vid in vendors) for (pid in 0..0xFFFF) DeviceTarget.find(vid, pid)?.let { found["%04X:%04X".format(vid, pid)] = nameOf(it) }
        for (pid in listOf(0xC20F, 0x43CC, 0x43E8, 0x0104, 0x0120, 0x0126, 0x0147, 0x12C5, 0x011D, 0x0111)) for (vid in 0..0xFFFF)
            DeviceTarget.find(vid, pid)?.let { found["%04X:%04X".format(vid, pid)] = nameOf(it) }
        val misses = listOf(0x3302 to 0x43E9, 0x3302 to 0x12C5, 0x35D8 to 0x011D, 0x31B2 to 0x0111, 0x2972 to 0x0128, 0x2972 to 0x0105, 0x2972 to 0x0127,
            0x152A to 0x88DB, 0x0A12 to 0x4005)
        val xml = File(requireNotNull(System.getProperty("contour.usbFilterXml")) { "contour.usbFilterXml is set by core/build.gradle.kts" }).readText()
        val listed = Regex("""<usb-device\s+vendor-id="(\d+)"\s+product-id="(\d+)"""").findAll(xml)
            .map { "%04X:%04X".format(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
        return obj(
            "recognised" to JsonObject(found.mapValues { JsonPrimitive(it.value) }),
            "nearMissesRecognised" to strs(misses.map { (v, p) -> "%04X:%04X -> %s".format(v, p, DeviceTarget.find(v, p)?.let { nameOf(it) }) }),
            "deviceFilterXmlEntriesInFileOrder" to strs(listed),
            "findDevice" to strs(findDeviceProbes.map { (v, p, n) -> "%04X:%04X name=%s -> %s".format(v, p, n, DeviceTarget.findDevice(v, p, n)?.let { nameOf(it) }) }),
            "walkplayConstants" to obj("vendorId" to toJson(WalkPlay.VENDOR_ID), "productId" to toJson(WalkPlay.PRODUCT_ID), "reportId" to toJson(WalkPlay.REPORT_ID),
                "reportSize" to toJson(WalkPlay.REPORT_SIZE), "bands" to toJson(WalkPlay.BANDS), "bandLen" to toJson(WalkPlay.BAND_LEN), "trnBandLen" to toJson(WalkPlay.TRN_BAND_LEN),
                "factoryFlatMicro" to strs(List(WalkPlay.BANDS) { write(WalkPlay.factoryFlat(it)) }),
                "commitSequence" to strs(WalkPlay.COMMIT_SEQUENCE.map { hex(it) })),
            "fiioConstants" to obj("pinnedCommit" to s(FiioCatalog.PINNED_COMMIT), "k13ProductId" to toJson(FiioCatalog.K13_R2R_PRODUCT_ID),
                "melodyProductId" to toJson(FiioCatalog.MELODY_PRODUCT_ID), "tinyAProductId" to toJson(FiioCatalog.TINY_A_PRODUCT_ID),
                "ruleOrder" to strs(FiioCatalog.rules.map { it.productName })),
        )
    }

    private fun nameOf(t: DeviceTarget): String = when (t) {
        DeviceTarget.MICRO -> "MICRO"; DeviceTarget.MAX -> "MAX"; DeviceTarget.TRN -> "TRN"; DeviceTarget.KA15 -> "KA15"
        DeviceTarget.K13 -> "K13"; DeviceTarget.MELODY -> "MELODY"; DeviceTarget.TINY_A -> "TINY_A"
        DeviceTarget.DAWN_PRO_2 -> "DAWN_PRO_2"; DeviceTarget.ALLEGRO_MINI -> "ALLEGRO_MINI"; DeviceTarget.JCALLY_JM12 -> "JCALLY_JM12"; DeviceTarget.ECHO_A -> "ECHO_A"; DeviceTarget.FIIO_JA11 -> "FIIO_JA11"; DeviceTarget.MOONDROP_DUSK -> "MOONDROP_DUSK"; DeviceTarget.RETRO_NANO -> "SNOWSKY_RETRO_NANO"; DeviceTarget.FIIO_JIEZI -> "FIIO_JIEZI"; DeviceTarget.KIWI_EARS_AD1 -> "KIWI_EARS_AD1"; DeviceTarget.SPACE_2 -> "TANCHJIM_SPACE_II"; DeviceTarget.EPZ_TP65 -> "EPZ_TP65"
        else -> "UNKNOWN"
    }

    // ---- the three 1.6 families (Dawn Pro 2, Allegro Mini, Echo-A) ---------------------------------------------------

    /** Literal numbers again (see [Spec]): what the three families took before their numbers came from the device records. */
    private val familySpecs: Map<String, Spec> = linkedMapOf(
        "DAWN_PRO_2" to Spec(8, -18.0, 12.0, 0.1, 10.0, 10.0, 20.0, 20000.0, listOf(FilterType.PEAK), -128.0, 0.0),
        "ALLEGRO_MINI" to Spec(5, -12.0, 12.0, 0.1, 10.0, 10.0, 40.0, 20000.0, listOf(FilterType.PEAK), 0.0, 0.0),
        "JCALLY_JM12" to Spec(5, -12.0, 12.0, 0.1, 10.0, 10.0, 20.0, 20000.0, THREE, 0.0, 0.0),
        "ECHO_A" to Spec(8, -10.0, 10.0, 0.1, 10.0, 10.0, 20.0, 20000.0, listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), -30.0, 0.0),
        "FIIO_JA11" to Spec(5, -12.0, 12.0, 0.1, 10.0, 10.0, 20.0, 20000.0, listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), -24.0, 0.0),
        "MOONDROP_DUSK" to Spec(9, -12.0, 12.0, 0.2, 10.0, 10.0, 20.0, 20000.0, THREE, 0.0, 0.0),
        "SNOWSKY_RETRO_NANO" to Spec(10, -12.0, 12.0, 0.25, 8.0, 8.0, 20.0, 20000.0, listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), -12.0, 12.0),
        "FIIO_JIEZI" to Spec(10, -12.0, 12.0, 0.1, 10.0, 10.0 / sqrt(2.0), 20.0, 20000.0, SEVEN, -12.0, 12.0),
        "KIWI_EARS_AD1" to Spec(5, -12.0, 12.0, 0.2, 10.0, 1.0, 20.0, 20000.0, THREE, 0.0, 0.0),
        "TANCHJIM_SPACE_II" to Spec(10, -12.0, 12.0, 0.1, 10.0, 10.0, 20.0, 20000.0, listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.LOW_PASS, FilterType.HIGH_PASS), -30.0, 0.0),
        "EPZ_TP65" to Spec(10, -10.0, 10.0, 0.1, 10.0, 10.0, 20.0, 20000.0, listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF), -30.0, 0.0),
    )

    private fun hexTrim(b: ByteArray): String = hex(b.copyOf(b.indexOfLast { it.toInt() != 0 } + 1))

    private fun benchResource(name: String): String =
        requireNotNull(CatalogDump::class.java.getResourceAsStream("/bench/$name")) { name }.use { it.readBytes() }.decodeToString()

    /** A bench log (`TX <report>` lines, each with its `RX <report>` answers; the real reads of the two units) as a port: it answers in log order and notes what the session sent. */
    private class ReplayPort(text: String, private val shape: NativeHidReports.Shape?, override val deviceKey: String) : NativePort {
        private class Frame(val rx: MutableList<ByteArray> = ArrayList())
        private val frames = ArrayList<Frame>()
        private var next = 0
        val sent = ArrayList<String>()
        init {
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val body = line.substringAfter(' ').split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
                when (line.substringBefore(' ')) {
                    "TX" -> frames += Frame()
                    "RX" -> frames.last().rx += body
                    else -> error("Not a bench log line: $line")
                }
            }
        }
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = requireNotNull(shape) { "no descriptor" }.payloadSize(id, kind)
        private fun take(id: Int, payload: ByteArray): Frame {
            check(next < frames.size) { "the session sends more than the log holds" }
            sent += hexTrim(byteArrayOf(id.toByte()) + payload)
            return frames[next++]
        }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { take(id, payload) }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray =
            take(id, payload).rx.filter { it[0].toInt() == id }.map { it.copyOfRange(1, it.size) }.firstOrNull { matches(it) }
                ?: throw java.io.IOException("Native request timed out or returned invalid state")
    }

    private fun familyBase(name: String, t: DeviceTarget): MutableMap<String, JsonElement> {
        val sp = familySpecs.getValue(name)
        return linkedMapOf(
            "family" to toJson(t.family),
            "caps" to toJson(t.caps),
            "flags" to obj("isFamily" to toJson(t.isFamily), "readOnly" to toJson(t.readOnly),
                "readOnlyInRelease" to toJson(Proven.asChannel(BuildChannel.RELEASE) { t.readOnly }), "readOnlyInReview" to toJson(Proven.asChannel(BuildChannel.PERF) { t.readOnly }),
                "hasPreamp" to toJson(t.hasPreamp), "preampLabel" to s(t.preampLabel),
                "stable" to toJson(t.stable), "native" to toJson(t.native), "experimental" to toJson(t.experimental), "supportsAb" to toJson(t.supportsAb),
                "preampMin" to num(t.preampMin), "preampMax" to num(t.preampMax), "preampStep" to num(t.preampStep), "destinationSlot" to toJson(t.destinationSlot),
                "settingsCount" to toJson(t.settings.size), "infoCount" to toJson(t.info.size)),
            "settings" to toJson(t.settings),
            "qRange" to JsonObject(FilterType.entries.associate { ft -> ft.name to s("${d(t.qRange(ft).start)}..${d(t.qRange(ft).endInclusive)}") }),
            "profileSpec" to obj("bands" to toJson(sp.bands), "gain" to s("${d(sp.gainLo)}..${d(sp.gainHi)}"), "q" to s("${d(sp.qLo)}..${d(sp.qHi)}"),
                "freq" to s("${d(sp.freqLo)}..${d(sp.freqHi)}"), "offered" to toJson(sp.offered), "preamp" to s("${d(sp.preampLo)}..${d(sp.preampHi)}")),
        )
    }

    private fun familyProfile(t: DeviceTarget, pr: Profile, plan: (Profile) -> JsonElement): JsonElement = obj(
        "profile" to profileJson(pr),
        "issues" to catching { strs(t.issues(pr)) },
        "blockLabel" to catching { s(DeviceTarget.blockLabel(t.issues(pr))) },
        "headline" to catching { s(t.invalidEqHeadline(pr)) },
        "preampFits" to (pr.preampDb?.let { v -> catching { toJson(t.preampFits(pr.bands, v)) } } ?: JsonNull),
        "shownPreamp" to catching { num(t.shownPreamp(pr)) },
        "plan" to catching { plan(pr) },
    )

    private fun dawnJson(t: DeviceTarget): JsonElement {
        val model = requireNotNull(t.moondrop)
        val shape = NativeHidReports.parse(benchResource("dawn-pro-2-descriptor.hex").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray())
        val port = ReplayPort(benchResource("dawn-pro-2-read-103434.log"), shape, "d")
        val snap = MoondropSession(model, port).read()
        val entries = familyBase("DAWN_PRO_2", t)
        entries["model"] = obj(
            "config" to toJson(model),
            "nativeTypes" to toJson(model.nativeTypes), "typeCodes" to toJson(MoondropCatalog.TYPES), "typesOfModel" to toJson(model.types),
            "offered" to toJson(DeviceTarget.MOONDROP_TYPES), "bandCount" to toJson(model.bandCount),
            "limits" to obj("gain" to s("${d(model.minGainDb)}..${d(model.maxGainDb)}"), "q" to s("${d(model.minQ)}..${d(model.maxQ)}"),
                "freq" to s("${d(model.minFrequencyHz)}..${d(model.maxFrequencyHz)}"), "preGain" to s("${d(model.preGainMinDb)}..${d(model.preGainMaxDb)}"),
                "globalGain" to s("${d(model.globalGainMinDb)}..${d(model.globalGainMaxDb)}")),
            "reportId" to toJson(model.reportId), "reportBytes" to toJson(model.reportBytes), "writableIndex" to toJson(model.writableIndex), "writableIndexes" to toJson(model.writableIndexes),
            "delaysMs" to obj("band" to toJson(MoondropCodec.DELAY_BAND_MS), "op" to toJson(MoondropCodec.DELAY_OP_MS), "flash" to toJson(MoondropCodec.DELAY_FLASH_MS),
                "eqIndex" to toJson(MoondropCodec.DELAY_EQ_INDEX_MS)),
            "registers" to obj("eqActive" to toJson(MoondropCodec.CMD_EQ_ACTIVE), "globalGain" to toJson(MoondropCodec.CMD_GLOBAL_GAIN),
                "preGain" to toJson(MoondropCodec.CMD_PRE_GAIN), "eqSave" to toJson(MoondropCodec.CMD_EQ_SAVE)),
            "readQueries" to strs(MoondropCodec.readQueries(model).map { hexTrim(it.payload) }),
        )
        entries["benchRead"] = obj(
            "sentFrames" to strs(port.sent),
            "activeIndex" to toJson(snap.activeIndex), "peqMode" to toJson(snap.peqMode), "globalGainDb" to num(snap.globalGain.db), "preGain" to toJson(snap.preGain?.db),
            "bands" to strs(snap.bands.map { "${it.index} bank=${it.bank} ${it.type} ${it.frequencyHz} Hz q256=${it.qQ8} gain256=${it.gainQ8} code=${it.nativeType}" }),
        )
        entries["profiles"] = JsonObject(profiles(familySpecs.getValue("DAWN_PRO_2")).associate { pr ->
            pr.name to familyProfile(t, pr) { p ->
                val planned = MoondropCodec.planFor(model, p.bands, p.effectivePreampDb(model.shelfForm))
                if (planned.issues.isNotEmpty()) obj("status" to s("rejected"), "issues" to strs(planned.issues))
                else obj("status" to s("ready"), "preGainDb" to toJson(planned.preGainDb),
                    "steps" to strs(MoondropCodec.writeSteps(model, planned.bands, planned.preGainDb).map { "${it.what}: ${hexTrim(it.payload)} +${it.delayAfterMs}" }))
            }
        })
        return JsonObject(entries)
    }

    private fun allegroJson(t: DeviceTarget): JsonElement = ktJson("ALLEGRO_MINI", t, KtMicroCatalog.models, "allegro-mini-read-232520.log")

    /** A KT Micro model: its limits and codec constants, a read replayed from [log] and the plan of every profile of [familySpecs]. */
    private fun ktJson(name: String, t: DeviceTarget, models: List<KtMicroCatalog.Model>, log: String): JsonElement {
        val model = requireNotNull(t.ktMicro)
        val port = ReplayPort(benchResource(log), null, "k")
        val session = KtMicroSession(model, port)
        val snap = session.read()
        val entries = familyBase(name, t)
        entries["model"] = obj(
            "config" to toJson(model),
            "nativeTypes" to toJson(model.nativeTypes), "bandCount" to toJson(model.bandCount),
            "limits" to obj("gain" to s("${d(model.minGainDb)}..${d(model.maxGainDb)}"), "q" to s("${d(model.minQ)}..${d(model.maxQ)}"),
                "freq" to s("${d(model.minFrequencyHz)}..${d(model.maxFrequencyHz)}")),
            "models" to strs(models.map { it.productName }),
            "codec" to obj("reportId" to toJson(KtMicroCodec.REPORT_ID), "eqEnableRegister" to toJson(KtMicroCodec.EQ_ENABLE_REGISTER), "eqOn" to toJson(KtMicroCodec.EQ_ON),
                "eqOff" to toJson(KtMicroCodec.EQ_OFF), "digitalGainRegister" to toJson(KtMicroCodec.DIGITAL_GAIN_REGISTER),
                "globalGainRegister" to toJson(KtMicroCodec.GLOBAL_GAIN_REGISTER), "save" to toJson(KtMicroCodec.SAVE)),
            "readQueries" to strs(KtMicroCodec.readQueries(model).map { hexTrim(it.payload) }),
        )
        entries["benchRead"] = obj(
            "sentFrames" to strs(port.sent),
            "eqControl" to toJson(snap.eqControl), "eqEnabled" to toJson(snap.eqEnabled),
            "bands" to strs(snap.bands.map { "${it.storedFrequencyHz} Hz gainTenths=${it.gainTenths} qMilli=${it.qMilli} type=${it.nativeType}" }),
            "digitalGain" to strs(listOf("left=${snap.digitalGain?.leftDb} right=${snap.digitalGain?.rightDb}")),
            "registerCount" to toJson(snap.registers.size),
        )
        entries["profiles"] = JsonObject(profiles(familySpecs.getValue(name)).associate { pr ->
            pr.name to familyProfile(t, pr) { p ->
                val plan = session.plan(p.copy(preampDb = 0.0), snap)
                obj("status" to s("ready"), "reconnectAfterSave" to toJson(plan.reconnectAfterSave), "expectedEqControl" to toJson(plan.expectedEqControl),
                    "frames" to strs(plan.frames.map { "${if (it.mutating) "W" else "R"} ${hexTrim(it.payload)} +${it.delayAfterMs}" }),
                    "expectedRegisters" to strs(plan.expectedRegisters.map { "${it.register}:${it.value.joinToString(",")}" }))
            }
        })
        return JsonObject(entries)
    }

    private fun echoJson(t: DeviceTarget): JsonElement {
        val e = requireNotNull(t.echo)
        val entries = familyBase("ECHO_A", t)
        entries["profileOfSession"] = obj("config" to toJson(e), "bands" to toJson(e.bands), "usbName" to toJson(e.usbName))
        entries["readRequests"] = obj("version" to s(hexTrim(WalkPlay.versionRequest())), "slot" to s(hexTrim(WalkPlay.slotRequest())),
            "bands" to strs(List(e.bands) { hexTrim(WalkPlay.bandRequest(it)) }), "preamp" to s(hexTrim(WalkPlay.preampRequest())))
        entries["profiles"] = JsonObject(profiles(familySpecs.getValue("ECHO_A")).associate { pr ->
            pr.name to familyProfile(t, pr) { _ -> s("read only: no plan is built") }
        })
        // The same profile set with the write flag on: the target's own issues and the Micro-code plan the session would write (the frames a bench write sends).
        val on = e.copy(hardwareVerified = true)
        val writable = DeviceTarget.forEcho(on)
        entries["writableProfiles"] = JsonObject(profiles(familySpecs.getValue("ECHO_A")).associate { pr ->
            pr.name to familyProfile(writable, pr) { p ->
                when (val plan = ProtocolMicro.plan(p, on.caps)) {
                    is DevicePlan.Ready -> obj("status" to s("ready"), "preampDb" to toJson(plan.preampDb),
                        "bands" to strs(plan.bands.map { hexTrim(WalkPlay.bandWriteReport(it, 0)) }))
                    is DevicePlan.Rejected -> obj("status" to s("rejected"), "issues" to strs(plan.issues))
                }
            }
        })
        return JsonObject(entries)
    }

    /** The Tanchjim Space II (WalkPlay SchemeNo16 on the Max's code, found by its USB name): the family flags and the WalkPlay block (entry, reads, flat plan, every profile's plan). */
    private fun space2Json(t: DeviceTarget): JsonElement {
        val entries = familyBase("TANCHJIM_SPACE_II", t)
        entries["walkplay"] = walkplayJson("TANCHJIM_SPACE_II", requireNotNull(t.walkplay), familySpecs.getValue("TANCHJIM_SPACE_II"))
        return JsonObject(entries)
    }

    /** The EPZ TP65 (experimental tester entry, the Space II's code with the catalog's range): the same dump as the Space II's. */
    private fun tp65Json(t: DeviceTarget): JsonElement {
        val entries = familyBase("EPZ_TP65", t)
        entries["walkplay"] = walkplayJson("EPZ_TP65", requireNotNull(t.walkplay), familySpecs.getValue("EPZ_TP65"))
        return JsonObject(entries)
    }

    /** A FiiO-family DAC found by its USB name (the JA11, the RETRO NANO, the JIEZI): the family flags and the FiiO block (config, frames of every profile, settings frames). */
    private fun fiioFamilyJson(name: String, t: DeviceTarget): JsonElement {
        val c = requireNotNull(t.fiio)
        val entries = familyBase(name, t)
        entries["fiio"] = fiioJson(name, t, c, familySpecs.getValue(name))
        return JsonObject(entries)
    }

    /**
     * The Moondrop DUSK-SP (gen 1): the limits and codec constants, the owner's read of 11.11.2026 replayed through the session (the preset and the nine bands, over the control pipe), and the plan of every profile
     * of [familySpecs]: the issues, and for a ready one the meta records and the frames of the write (every request of the 56, with its pause).
     */
    private fun duskJson(t: DeviceTarget): JsonElement {
        val model = requireNotNull(t.gen1)
        val shape = NativeHidReports.parseGen1(benchResource("dusk-descriptor.hex").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray())
        shape.qualifyGen1()
        val port = ReplayPort(benchResource("dusk-read-20261111.log"), shape, "g")
        val snap = MoondropGen1Session(model, port).read()
        val entries = familyBase("MOONDROP_DUSK", t)
        entries["model"] = obj(
            "config" to toJson(model),
            "types" to toJson(model.types), "bandCount" to toJson(model.bandCount),
            "limits" to obj("gain" to s("${d(model.minGainDb)}..${d(model.maxGainDb)}"), "q" to s("${d(model.minQ)}..${d(model.maxQ)}"), "freq" to s("${d(model.minFrequencyHz)}..${d(model.maxFrequencyHz)}")),
            "presets" to toJson(model.presetLabels), "customIndex" to toJson(model.customIndex), "stockPicker" to toJson(model.stockPicker),
            "presetBands" to JsonObject(model.presetLabels.toSortedMap().keys.mapNotNull { i -> model.presetBands(i)?.let { b -> i.toString() to strs(b.map { "${it.type} ${d(it.freqHz)} Hz ${d(it.gainDb)} dB Q ${d(it.q)}" }) } }.toMap()),
            "factoryBank" to strs(model.factoryFrequenciesHz.map { "$it Hz Q ${d(model.factoryQ)}" }),
            "pacingMs" to obj("replyWait" to toJson(model.replyWaitMs), "writeGap" to toJson(model.writeGapMs), "afterCommit" to toJson(model.afterCommitMs), "afterSelect" to toJson(model.afterSelectMs)),
            "codec" to obj("reportId" to toJson(MoondropGen1Codec.REPORT_ID), "payloadBytes" to toJson(MoondropGen1Codec.PAYLOAD_BYTES), "reportBytes" to toJson(model.reportBytes),
                "presetCommand" to toJson(MoondropGen1Codec.CMD_PRESET), "bandReadCommand" to toJson(MoondropGen1Codec.CMD_BAND_READ), "writeCommand" to toJson(MoondropGen1Codec.CMD_WRITE),
                "commit" to toJson(MoondropGen1Codec.COMMIT), "rates" to strs(MoondropGen1Codec.RATES.map { "${it.first}:${it.second}" }), "types" to toJson(MoondropGen1Codec.TYPES)),
            "readQueries" to strs(MoondropGen1Codec.readQueries(model.bandCount).map { hexTrim(it.report) }),
            "selectSteps" to JsonObject(model.presetLabels.toSortedMap().keys.associate { i -> i.toString() to strs(MoondropGen1Codec.selectSteps(i, model.afterSelectMs).map { "${hexTrim(it.report)} +${it.delayAfterMs}" }) }),
        )
        entries["benchRead"] = obj(
            "sentFrames" to strs(port.sent),
            "activeIndex" to toJson(snap.activeIndex), "onCustom" to toJson(snap.onCustom),
            "bands" to strs(snap.bands.map { "${it.index} ${it.type} ${it.freqHz} Hz q256=${it.qRaw} gain=${it.gainDb} code=${it.nativeType}" }),
        )
        entries["profiles"] = JsonObject(profiles(familySpecs.getValue("MOONDROP_DUSK")).associate { pr ->
            pr.name to familyProfile(t, pr) { p ->
                val issues = MoondropGen1Codec.issues(model, p.bands)
                if (issues.isNotEmpty()) obj("status" to s("rejected"), "issues" to strs(issues))
                else {
                    val planned = MoondropGen1Codec.plan(model, p.bands)
                    obj("status" to s("ready"),
                        "meta" to strs(planned.map { "${it.index} ${it.meta}" }),
                        "steps" to strs(MoondropGen1Codec.writeSteps(model, planned).map { "${it.what}: ${hexTrim(it.report)} +${it.delayAfterMs}" }))
                }
            }
        })
        return JsonObject(entries)
    }

    /** The families the 1.5 golden did not hold: appended after "targets", so every older section stays as it was. */
    private fun families(): JsonElement = obj(
        // The Dawn Pro 2 and the Allegro Mini are dumped as a review build sees them (their write path: plans, frames, issues); their release state is the "readOnlyInRelease"
        // flag. The Echo-A is dumped as a release sees it (read only), its writable variant is the "writableProfiles" section.
        "DAWN_PRO_2" to Proven.asChannel(BuildChannel.PERF) { dawnJson(DeviceTarget.DAWN_PRO_2) },
        "ALLEGRO_MINI" to Proven.asChannel(BuildChannel.PERF) { allegroJson(DeviceTarget.ALLEGRO_MINI) },
        // The JM12 is dumped as a review build sees it, like the Allegro Mini, from a CONSTRUCTED read (see the log's header), after the Allegro Mini.
        "JCALLY_JM12" to Proven.asChannel(BuildChannel.PERF) { ktJson("JCALLY_JM12", DeviceTarget.JCALLY_JM12, KtMicroCatalog.jm12Models, "jm12-read-constructed.log") },
        "ECHO_A" to Proven.asChannel(BuildChannel.RELEASE) { echoJson(DeviceTarget.ECHO_A) },
        // The FiiO JA11 is dumped as a review build sees it (readOnlyInRelease says what a release does; its five types are offered in every build), after the Echo-A.
        "FIIO_JA11" to Proven.asChannel(BuildChannel.PERF) { fiioFamilyJson("FIIO_JA11", DeviceTarget.FIIO_JA11) },
        // The Moondrop DUSK-SP is dumped as a review build sees it (readOnlyInRelease says what a release does), after the FiiO JA11.
        "MOONDROP_DUSK" to Proven.asChannel(BuildChannel.PERF) { duskJson(DeviceTarget.MOONDROP_DUSK) },
        // The SnowSky RETRO NANO and the FiiO JIEZI (step 1, nothing measured on a unit): dumped as a review build sees them (a release has them read only), after the DUSK-SP.
        "SNOWSKY_RETRO_NANO" to Proven.asChannel(BuildChannel.PERF) { fiioFamilyJson("SNOWSKY_RETRO_NANO", DeviceTarget.RETRO_NANO) },
        "FIIO_JIEZI" to Proven.asChannel(BuildChannel.PERF) { fiioFamilyJson("FIIO_JIEZI", DeviceTarget.FIIO_JIEZI) },
        // The Kiwi Ears AD1 and the Tanchjim Space II (read on a unit, nothing written): dumped as a review build sees them. The AD1's read is the JM12's constructed log: the unit answered the register reads with the same values.
        "KIWI_EARS_AD1" to Proven.asChannel(BuildChannel.PERF) { ktJson("KIWI_EARS_AD1", DeviceTarget.KIWI_EARS_AD1, KtMicroCatalog.ad1Models, "jm12-read-constructed.log") },
        "TANCHJIM_SPACE_II" to Proven.asChannel(BuildChannel.PERF) { space2Json(DeviceTarget.SPACE_2) },
        // The EPZ TP65 (experimental tester entry, nothing measured on a Contour unit): dumped as a review build sees it, after the Space II.
        "EPZ_TP65" to Proven.asChannel(BuildChannel.PERF) { tp65Json(DeviceTarget.EPZ_TP65) },
    )

    /** The whole dump as pretty JSON text, LF line endings, one trailing newline. */
    fun dump(): String {
        val targets = linkedMapOf(
            "MICRO" to DeviceTarget.MICRO, "MAX" to DeviceTarget.MAX, "TRN" to DeviceTarget.TRN, "KA15" to DeviceTarget.KA15,
            "K13" to DeviceTarget.K13, "MELODY" to DeviceTarget.MELODY, "TINY_A" to DeviceTarget.TINY_A,
        )
        val root = obj(
            "about" to s("Golden snapshot of the hand-written device catalog (see CatalogDump.kt). Regenerated only on a deliberate catalog change: see devices/README.md."),
            "format" to toJson(1),
            "identity" to identity(),
            "targets" to JsonObject(targets.mapValues { (name, t) -> targetJson(name, t) }),
            "families" to families(),
        )
        return json.encodeToString(JsonElement.serializer(), root).replace("\r\n", "\n") + "\n"
    }
}
