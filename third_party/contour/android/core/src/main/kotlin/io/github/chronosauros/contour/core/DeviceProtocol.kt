package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.devices.WalkPlayData
import io.github.chronosauros.contour.core.native.DacSetting

/**
 * Explicit allow-list of DACs. MICRO delegates verbatim to the 1.2.2 code ([ProtocolMicro], [WalkPlay]).
 * MAX (CrinEar Protocol Max, WalkPlay SchemeNo16) is supported in every build; it runs the fail-closed path of the TRN (strict replies, the TRN's register-format read, see [parseBand]) with devicePEQ's
 * write sequence and its own record (ten bands, factory flat read on a unit, shelf Q = register / sqrt2); its candidates are [ProtocolMax]'s.
 * TRN (TRN Black Pearl, also SchemeNo16) runs the same strict path with its own write sequence
 * ([WalkPlay.trnWriteSequence]), its own factory flat for the spare slots and a read that accepts what the TRN's
 * own tool can store (see [parseBand]). Its registers were written and read back from a PC on 19.10.2026.
 */
enum class DeviceProtocol private constructor(internal val data: WalkPlayData, val settings: List<DacSetting> = emptyList()) {
    // The caps, preamp policy, factory flat of the spare slots and read bounds of each protocol are its record's (devices/*.json via GeneratedDevices).
    MICRO(GeneratedDevices.CRINEAR_PROTOCOL_MICRO),
    MAX(GeneratedDevices.CRINEAR_PROTOCOL_MAX),
    // LOW PASS and HIGH PASS (native codes 4 and 5) are proven on the TRN's registers (19.10.2026) but stay out of the
    // stable build: owner decision 14.10.2026, pass filters reach stable only with branch wip/pass-filters after the
    // UMC202HD measurement. The record holds them in `peq.heldTypes`; the plain RBJ low-pass and high-pass biquads are in WalkPlay.scaledCoefficients
    // (the Micro and the Echo-A send them through the same band frame), and a review build offers them through [ProtocolTrn.PASS_FILTERS_VERIFIED] and [Proven].
    TRN(GeneratedDevices.TRN_BLACK_PEARL),
    // The Tanchjim Space II (WalkPlay SchemeNo16, found by its USB name): the Protocol Max's code and write sequence with its own record (shelf Q = register, measured), proven on a unit from the Mac and a phone
    // (13.11.2026; [WalkPlayProfiles.space2]).
    SPACE_2(GeneratedDevices.TANCHJIM_SPACE_II),
    // The EPZ TP65 (WalkPlay SchemeNo16, found by its USB name; supported since 1.8.2, tested by a TP65 owner on their unit with the tester versions 1.8.0-tp65test2 to 1.8.1-tp65test4,
    // issue #6, nothing measured by Contour): the Space II's read code with the catalog's range and types (+-10 dB; PEAK and the two shelves, each written and read back on the unit, the shelves
    // with the Space II's shelf Q convention, not measured on a TP65), no DAC settings, its own preamp read ([preampRequest]) and its own write ([writeSequence]: band frames and the RAM apply,
    // no preamp, no flash), the only frames validated on the unit (devices/epz-tp65.json). Its preamp reply header is read the Space II's lenient way.
    EPZ_TP65(GeneratedDevices.EPZ_TP65);

    /** The record's caps; the Micro's, the TRN's and the Max's follow their verification flags ([ProtocolMicro.PASS_FILTERS_VERIFIED], [ProtocolTrn.PASS_FILTERS_VERIFIED], [ProtocolMax.CANDIDATES_VERIFIED]). */
    val caps: DeviceCapabilities get() = when {
        data === GeneratedDevices.CRINEAR_PROTOCOL_MICRO -> ProtocolMicro.CAPABILITIES
        data === GeneratedDevices.TRN_BLACK_PEARL -> ProtocolTrn.CAPABILITIES
        data === GeneratedDevices.CRINEAR_PROTOCOL_MAX -> ProtocolMax.CAPABILITIES
        else -> recordCaps
    }
    private val recordCaps: DeviceCapabilities by lazy { data.caps() }

    /** True for protocols on the strict fail-closed path (MAX, TRN); the name is historical. */
    val experimental: Boolean get() = this != MICRO
    val supportsAb: Boolean get() = this == MICRO
    val preampMin: Int get() = data.preampMinDb
    /** The top of the device preamp the app takes: the record's, and on the Micro the vendor's +6 dB once [ProtocolMicro.MICRO_POSITIVE_PREAMP_VERIFIED] is true (the Echo-A has its own flag, read through [io.github.chronosauros.contour.core.native.WalkPlayProfile.preampMaxDb]). */
    val preampMax: Int get() = if (this == MICRO) ProtocolMicro.PREAMP_MAX_EFFECTIVE_DB else data.preampMaxDb

    /** Micro: HIGH SHELF gains its emulation folds into the preamp. Max has native shelves: none. */
    fun shelfOffset(bands: List<Band>): Double = if (this == MICRO) ProtocolMicro.highShelfGainSum(bands) else 0.0

    fun devicePreamp(bands: List<Band>, preampDb: Double?): Int = if (this == MICRO) {
        ProtocolMicro.devicePreamp(bands, preampDb)
    } else {
        require(preampDb == null || preampDb.isFinite())
        (if (preampDb == null) Preamp.auto(bands, caps.shelfForm) else kotlin.math.floor(preampDb + 1e-6).toInt())
            .coerceIn(preampMin, preampMax)
    }

    /**
     * Does this explicit curve-domain [preampDb] fit the device with these [bands]? Exactly the rule [plan] applies
     * (Micro: register with the high-shelf offset; Max: `floor(preamp + 1e-6)` in the policy range).
     */
    fun preampFits(bands: List<Band>, preampDb: Double): Boolean = preampDb.isFinite() && (
        if (this == MICRO) ProtocolMicro.preampFits(bands, preampDb)
        else kotlin.math.floor(preampDb + 1e-6) in preampMin.toDouble()..preampMax.toDouble())

    /** Why [preampDb] does not fit with these [bands] (null when it does): the text of the send refusal. */
    fun preampRefusal(bands: List<Band>, preampDb: Double): String? = when {
        preampFits(bands, preampDb) -> null
        this == MICRO && preampDb.isFinite() -> ProtocolMicro.preampRefusal(bands, preampDb)
        else -> "Preamp ${dbText(preampDb) { !preampFits(bands, it) }} dB outside $preampMin..$preampMax dB policy"
    }

    /**
     * Import of a file's explicit preamp: rounded DOWN to 0.1 dB (toward quieter, never louder). If that would flip
     * the planner's verdict (accepted to refused just above -30), the exact file value is kept instead. Nothing is
     * capped: a positive value stays and is refused when the device preamp would be 1 dB or more.
     */
    fun fitImportedPreamp(bands: List<Band>, preampDb: Double): Double {
        if (!preampDb.isFinite()) return if (preampDb.isNaN()) 0.0 else if (preampDb > 0) preampMax.toDouble() else preampMin.toDouble()
        val down = Preamp.floorTo(preampDb)
        return if (preampFits(bands, down) == preampFits(bands, preampDb)) down else preampDb
    }

    /**
     * The lowest 0.1 dB manual preamp whose register equals AUTO's, so AUTO to MANUAL never changes the sound
     * (AUTO itself is rounded toward quieter). Null when AUTO has no valid register.
     */
    fun manualForAuto(bands: List<Band>): Double? {
        val register = runCatching { devicePreamp(bands, null) }.getOrNull() ?: return null
        // The register tolerance (1e-6) applies before scaling to tenths: m + hs + 1e-6 >= register.
        val first = kotlin.math.ceil((register - shelfOffset(bands) - 1e-6) * 10).toLong()
        for (n in first..first + 3) {
            val m = n / 10.0
            if (kotlin.math.floor(m + shelfOffset(bands) + 1e-6).toInt() == register) return m
        }
        return null
    }

    /** AUTO's refusal for [bands] (the preamp it needs is below the floor), or null when AUTO fits or has no response. */
    fun autoRefusal(bands: List<Band>): String? {
        if (this == MICRO) return ProtocolMicro.autoRefusal(bands)
        val needed = runCatching { Preamp.auto(bands.filter { it.enabled }, caps.shelfForm) }.getOrNull() ?: return null
        return if (needed < preampMin) SendBlocked.auto(needed, preampMin) else null
    }

    /**
     * The PREAMP row speaks in the domain of the drawn curve (like squig.link): the device register minus the
     * HIGH SHELF gains the Micro emulation folds out of the curve. Display only.
     */
    fun shownPreamp(profile: Profile): Double = devicePreamp(profile.bands, profile.preampDb) - shelfOffset(profile.bands)

    /**
     * The spare slots are the record's `slots.factoryFlat`. Micro factory bytes are unchanged. Max spare slots are explicit neutral PKs, not claimed
     * factory captures. TRN spare slots are the unit's own factory flat, read from the owner's unit before any write (19.10.2026): PK, 0 dB,
     * register Q 182/256, 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz.
     */
    private fun flatBand(index: Int): WalkPlay.BandWrite = WalkPlay.flatWrite(index, data.factoryFlat[index])

    /**
     * The reports of one send and the pause after each: Micro and Max as 1.3.x, the TRN with its own sequence, the EPZ TP65 with its own ([WalkPlay.tp65WriteSequence]: band frames and
     * the RAM apply only; [current], the registers read right before the send, leaves out the bands that do not change; null sends every band). [current] is the TP65's only.
     */
    fun writeSequence(plan: DevicePlan.Ready, slot: Int, current: List<WalkPlay.Registers>? = null): List<WalkPlay.Step> =
        if (this == TRN) WalkPlay.trnWriteSequence(plan.bands, plan.preampDb.toDouble(), slot)
        else if (this == EPZ_TP65) WalkPlay.tp65WriteSequence(plan.bands, slot, current)
        else WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), slot, commit = true)

    fun flatPlan(): DevicePlan.Ready = if (this == MICRO) ProtocolMicro.flatPlan()
        else DevicePlan.Ready(List(caps.bands) { flatBand(it) }, 0)

    /** MAX VOLUME, the fixed cut under every profile ([ProtocolMicro.trimmedRegister]): the Protocol Micro only (owner 25.11.2026, Micro first). */
    val takesVolumeTrim: Boolean get() = this == MICRO

    /** [trimDb]: the DAC's MAX VOLUME, 0 on every DAC but the Micro ([takesVolumeTrim]). */
    fun plan(profile: Profile, trimDb: Int = 0): DevicePlan {
        if (this == MICRO) return ProtocolMicro.plan(profile.bands, profile.preampDb, trimDb = trimDb)
        noTrim(trimDb)
        return plan(profile.bands, profile.preampDb)
    }

    private fun noTrim(trimDb: Int) = require(trimDb == 0) { "${caps.name} has no MAX VOLUME" }

    /**
     * [caps] are the limits a band is held to: the protocol's own by default; the bench passes a widened set for a Max (LOW PASS and HIGH PASS, +-12 dB: [ProtocolMax.capabilities]) so a
     * unit can be asked about candidates the app does not offer. The Micro's planner takes its caps from its own entry point ([ProtocolMicro.plan]), so [caps] does not reach it.
     */
    fun plan(bands: List<Band>, preampDb: Double?, caps: DeviceCapabilities = this.caps): DevicePlan {
        if (this == MICRO) return ProtocolMicro.plan(bands, preampDb)
        val issues = ArrayList<String>()
        val active = bands.filter { it.enabled }
        if (active.size > caps.bands) issues += "${active.size} enabled bands; ${caps.name} has ${caps.bands}"
        bands.forEachIndexed { i, b ->
            // Even disabled input must be finite; nothing invalid can reach a write sequence.
            if (!b.freqHz.isFinite() || !b.gainDb.isFinite() || !b.q.isFinite()) issues += "Band ${i + 1}: non-finite values"
            if (b.enabled && (b.type !in caps.types || b.freqHz !in caps.freqMinHz..caps.freqMaxHz ||
                    b.gainDb !in caps.gainMinDb..caps.gainMaxDb || b.q !in caps.qRange(b.type)))
                issues += "Band ${i + 1}: outside ${caps.name} supported types/ranges"
        }
        if (preampDb != null) preampRefusal(active, preampDb)?.let { issues += it }
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        return runCatching {
            // Native shelves: HIGH SHELF goes out as HSQ (no Micro LOW SHELF + preamp emulation), no freq/Q compensation.
            // There is no exact emulation to fall back on here: the Micro's one is a low shelf of the opposite gain plus the high-shelf gain sum moved into the
            // overall gain, which a boost needs to be positive, and the preamp of these DACs stops at 0 dB (the TRN: -30..0). So a shelf whose biquad leaves
            // signed Q30 (a boost at a low corner) is refused below, before anything is sent, and the editor shows SendBlocked.band; `validate` of the bench asks this same rule.
            val writes = List(caps.bands) { i -> if (i < active.size) WalkPlay.bandWrite(i, active[i], caps) else flatBand(i) }
            // Preflight the entire payload, including native shelves, before the first USB write. A band whose
            // biquad does not fit signed Q30 (original or quantised values) is refused, never wrapped. Slots follow
            // the enabled bands in order; the editor numbers every band, enabled or not.
            val rows = bands.indices.filter { bands[it].enabled }
            val blocked = ArrayList<String>()
            writes.forEachIndexed { i, w ->
                try { WalkPlay.bandWriteReport(w, 0) } catch (_: WalkPlay.Q30Overflow) {
                    blocked += SendBlocked.band(caps.name, (rows.getOrNull(i) ?: i) + 1, active.getOrNull(i)?.type ?: FilterType.PEAK)
                }
            }
            if (blocked.isNotEmpty()) return@runCatching DevicePlan.Rejected(blocked)
            // AUTO that needs more than the device takes is refused with the number, not sent as -30.
            if (preampDb == null) {
                val needed = Preamp.auto(active, caps.shelfForm)
                if (needed < preampMin) return@runCatching DevicePlan.Rejected(listOf(SendBlocked.auto(needed, preampMin)))
            }
            val device = devicePreamp(active, preampDb)
            // The TP65's preamp write was never tried on a unit, so Contour sends none ([WalkPlay.tp65WriteSequence]): a send leaves the DAC's preamp as it is (0 dB on the
            // tested unit) and is refused when the profile asks for another one, AUTO's cut for a boost included.
            if (this == EPZ_TP65 && device != 0) return@runCatching DevicePlan.Rejected(listOf(TP65_PREAMP_REFUSAL.format(device)))
            DevicePlan.Ready(writes, device)
        }.getOrElse { DevicePlan.Rejected(listOf("Invalid EQ: ${it.message}")) }
    }

    fun matches(plan: DevicePlan.Ready, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        if (this == MICRO) ProtocolMicro.matches(plan, regs, preampDb)
        else plan.bands.size == caps.bands && ProtocolMicro.matches(plan, regs, preampDb)

    fun matches(profile: Profile, regs: List<WalkPlay.Registers>, preampDb: Int, trimDb: Int = 0): Boolean =
        if (this == MICRO) ProtocolMicro.matches(profile, regs, preampDb, trimDb)
        else (plan(profile, trimDb) as? DevicePlan.Ready)?.let { matches(it, regs, preampDb) } ?: false

    /** [trimDb]: the DAC's MAX VOLUME, taken back out of the preamp read ([ProtocolMicro.importExact]); 0 on every DAC but the Micro. */
    fun importExact(bands: List<WalkPlay.DeviceBand>, preampDb: Int, caps: DeviceCapabilities = this.caps, trimDb: Int = 0): DacImport {
        if (this == MICRO) return ProtocolMicro.importExact(bands, preampDb, trimDb = trimDb)
        noTrim(trimDb)
        return importExactTolerant(bands, preampDb, caps)
    }

    /**
     * FROM DAC on the TRN Black Pearl and the Protocol Max (the same register-format tolerance): what the DAC holds, with the stored values as they are. A band outside the editor
     * caps (the TRN's own tool stores e.g. -10.4 dB against +-10) comes in with its real value and a warning; the plan
     * stays Rejected, so sending it back stays blocked until the band is changed. A disabled slot (freq or Q 0, or
     * erased) is an empty spare and is left out wherever it stands. Anything else the planner refuses, or that cannot
     * be re-encoded to the same registers, is rejected. A band of a type this build does not offer (a LOW PASS or HIGH
     * PASS from the TRN's tool) is rejected too, but the read itself succeeded, so a send can still replace it.
     */
    private fun importExactTolerant(bands: List<WalkPlay.DeviceBand>, preampDb: Int, caps: DeviceCapabilities): DacImport {
        if (bands.size != caps.bands || bands.indices.any { bands[it].registers.index != it })
            return DacImport.Rejected(listOf("Expected all ${caps.bands} DAC slots in order"))
        if (preampDb !in preampMin..preampMax) return DacImport.Rejected(listOf("DAC preamp outside supported policy"))
        val held = bands.filter { it.enabled }
        val skipped = bands.size - held.size
        if (held.isEmpty()) return DacImport.Rejected(listOf("DAC holds no enabled band"))
        // The registers as a send would leave them: the held bands in order, then the factory flat spares.
        val held10 = List(caps.bands) { i -> held.getOrNull(i)?.registers?.copy(index = i) ?: flatBand(i).registers() }
        // Keep one editable band when the whole DAC is factory flat.
        val last = maxOf(0, held.indices.indexOfLast { held10[it] != flatBand(it).registers() })
        val nativeCodes = caps.types.map { WalkPlay.typeCode(it) }.toSet()
        val reconstructed = held.take(last + 1).mapIndexed { i, b ->
            val r = b.registers
            if (r.typeCode !in nativeCodes) return DacImport.Rejected(listOf("DAC band ${r.index + 1}: unsupported type"))
            Band("dac$i", b.type, r.freq.toDouble(), r.gain256 / 256.0, WalkPlay.intendedQ(r.q256, b.type, caps))
        }
        val eq = ImportedEq(reconstructed, preampDb.toDouble())
        val note = if (skipped == 0) "" else " $skipped disabled DAC slot${if (skipped == 1) "" else "s"} left out."
        val candidate = plan(eq.bands, eq.preampDb, caps)
        if (candidate is DevicePlan.Ready)
            return if (matches(candidate, held10, preampDb)) DacImport.Ready(eq, note.trim().ifEmpty { null })
                else DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
        val refused = (candidate as DevicePlan.Rejected).issues
        if (refused.all { SendBlocked.isBandRefusal(it) || it.endsWith(": outside ${caps.name} supported types/ranges") }) {
            val writes = List(caps.bands) { i -> if (i < reconstructed.size) WalkPlay.bandWrite(i, reconstructed[i], caps) else flatBand(i) }
            if (matches(DevicePlan.Ready(writes, preampDb), held10, preampDb))
                return DacImport.Ready(eq, refused.joinToString(" ") { if (it.endsWith(".")) it else "$it." } +
                    " Sending it back stays blocked until you change the band.$note")
        }
        return DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
    }

    // ---- receive side. MICRO: exactly the 1.2.2 predicates and parsers. MAX, TRN: strict, fail closed. ----------

    /** Max: a full 64-byte input report, read direction; never a write echo, short packet or unknown type. */
    fun isReply(buf: ByteArray, cmd: Int): Boolean = if (this == MICRO) WalkPlay.isReply(buf, cmd)
        else buf.size >= WalkPlay.REPORT_SIZE && WalkPlay.isReply(buf, cmd) && (buf[1].toInt() and 0xFF) == WalkPlay.READ

    fun isBandReply(buf: ByteArray, index: Int): Boolean = if (this == MICRO) WalkPlay.isBandReply(buf, index)
        else index in 0 until caps.bands && isReply(buf, WalkPlay.CMD_PEQ) && WalkPlay.isBandReply(buf, index)

    /** The bulk slot read: Micro accepts any PEQ reply (1.2.2); Max requires a valid band 0 reply. */
    fun isSlotReply(buf: ByteArray): Boolean = if (this == MICRO) WalkPlay.isReply(buf, WalkPlay.CMD_PEQ)
        else isBandReply(buf, 0)

    /** [caps]: as in [plan]; what a Max read may hold (types, gain, Q, frequency) follows them. */
    fun parseBand(buf: ByteArray, index: Int, caps: DeviceCapabilities = this.caps): WalkPlay.DeviceBand {
        if (this == MICRO) return WalkPlay.parseBand(buf)
        require(isBandReply(buf, index)) { "Invalid band $index reply" }
        require(buf[3].toInt() == 0 && buf[4].toInt() == 0) { "Invalid PEQ reply header" }
        val b = WalkPlay.parseBand(buf)
        // TRN and Max (the same register-format tolerance). A read reports what the DAC holds: the TRN's own tool can store values the editor refuses to send (a tester's
        // Black Pearl, 11.10.2026: 9300 Hz, Q 2.0, -10.4 dB against the +-10 dB caps) and a Max can hold +-12 dB bands or a LOW PASS (the bench's candidates, dac-lab run 09), so an enabled band is checked
        // against the register format only (frequency below the 96 kHz design rate's Nyquist, gain +-60 dB), never
        // against the caps; sending keeps the caps ([plan]). The type may be any code the unit takes (1 to 5, the
        // pass filters included): a read must not fail on what a tool wrote, or Contour could not replace it.
        // A disabled spare slot is no read failure whatever its type byte (an erased slot reads 0xFF, a zeroed one 0).
        if (b.enabled) {
            require(b.registers.typeCode in WalkPlay.TYPE_LSQ..WalkPlay.TYPE_HP) { "Unsupported ${caps.name} native type" }
            val hz = requireNotNull(data.readBoundsHz) { "devices/${data.id}.json readback.readBoundsHz" }
            val db = requireNotNull(data.readBoundsDb) { "devices/${data.id}.json readback.readBoundsDb" }
            require(b.registers.freq in hz && b.registers.q256 in 1..0xFFFF &&
                b.registers.gain256 in (db.start * 256).toInt()..(db.endInclusive * 256).toInt()) { "Malformed ${if (this == TRN) "TRN" else caps.name} registers" }
        }
        return b
    }

    fun parseSlot(buf: ByteArray, caps: DeviceCapabilities = this.caps): Int {
        if (this == MICRO) return WalkPlay.parseSlot(buf)
        require(isBandReply(buf, 0)) { "Invalid bulk slot reply" }
        parseBand(buf, 0, caps)
        return WalkPlay.parseSlot(buf) // opaque byte, echoed unchanged; no assumption about preset 101
    }

    fun parseVersion(buf: ByteArray): String {
        if (this == MICRO) return WalkPlay.parseVersion(buf)
        require(isReply(buf, WalkPlay.CMD_VERSION)) { "Invalid version reply" }
        val version = WalkPlay.parseVersion(buf)
        require(version.isNotEmpty() && version.all { it.code in 0x20..0x7E }) { "Invalid firmware version" }
        return version
    }

    /**
     * The preamp read: `80 03 00` (devicePEQ's, proven on the Micro, the Max, the TRN and the Space II). The EPZ TP65 gets `80 03 01`, the form its owner's Windows tool read on the unit
     * (reply `80 03 02 00 00`, offset 0; issue #6 attachment, 25.11.2026); `80 03 00` was never sent to a TP65.
     */
    fun preampRequest(): ByteArray = if (this == EPZ_TP65) WalkPlay.report(WalkPlay.READ, WalkPlay.CMD_GLOBAL_GAIN, 0x01) else WalkPlay.preampRequest()

    fun parsePreamp(buf: ByteArray): Int {
        if (this == MICRO) return WalkPlay.parsePreamp(buf)
        require(isReply(buf, WalkPlay.CMD_GLOBAL_GAIN)) { "Invalid preamp reply" }
        // TRN: the preamp is the int8 at d[4], read the way devicePEQ and FreeDSP-Studio read it, and the two bytes before it are
        // not checked. A tester's Black Pearl on firmware 0.5 (09.11.2026) answered `80 03 02 32 01`, and with the 0x32 every
        // read and every send stopped at "Invalid preamp header". The TRN fills that position with stale bytes in its settings
        // replies too (research/trn-settings-walkplay-2026-10-27/FINDINGS.md). The Max keeps the strict header (no unit to test).
        if (this != TRN && this != SPACE_2 && this != EPZ_TP65) require(buf[3].toInt() == 2 && buf[4].toInt() == 0) { "Invalid preamp header" }
        return WalkPlay.parsePreamp(buf)
    }

    companion object {
        /** The EPZ TP65's send refusal ([plan]) when the profile's device preamp (%d, whole dB) is not 0. */
        internal const val TP65_PREAMP_REFUSAL = "Contour does not write the EPZ TP65 preamp yet, so it stays at 0 dB; this EQ asks for %d dB. Reduce the boosts, or set the preamp to 0 dB (boosts may then clip)."

        /** Strict VID/PID pairs only. */
        fun find(vendorId: Int, productId: Int): DeviceProtocol? =
            entries.firstOrNull { it.caps.vendorId == vendorId && it.caps.productId == productId }
    }
}
