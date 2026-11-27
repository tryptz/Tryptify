package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import kotlin.math.floor

/** Pure-JVM KT register framing, ported from advBeta's KtMicroCodec (devicePEQ 0617f382 ktmicroUsbHidHandler.js L43-107, 181-307
 * and the Allegro Mini capture). Payloads EXCLUDE the report ID. No USB I/O, no implicit enable, no clear (0x43) except [compileClear] with a [KtMicroResetTicket], no preamp
 * (digital DAC gain 0x66 is never written; the analog gain 0x3B only by [compileGlobalGain], never with an EQ write), no firmware or reset command.
 */
object KtMicroCodec {
    // Report id, payload size, read op, SAVE and the registers below are the record's (devices/kiwi-ears-allegro-mini.json via GeneratedDevices); the write op
    // (the record still holds it as "unknown") and the channel-flags register are protocol and stay here.
    const val REPORT_ID = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_REPORT_ID
    const val PAYLOAD_BYTES = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_PAYLOAD_BYTES
    const val READ = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_READ_OP
    const val WRITE = 0x57
    const val SAVE = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_SAVE_COMMAND
    const val EQ_ENABLE_REGISTER = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_EQ_ENABLE_REGISTER
    const val EQ_OFF = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_EQ_OFF
    const val EQ_ON = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_EQ_ON
    const val DIGITAL_GAIN_REGISTER = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_DIGITAL_GAIN_REGISTER
    const val CHANNEL_FLAGS_REGISTER = 0x01
    /** The vendor tool's Global Gain (the analog DAC gain): read by the EQ session, written only by [compileGlobalGain]; its layout is not proven on hardware. */
    const val GLOBAL_GAIN_REGISTER = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_GLOBAL_GAIN_REGISTER
    val UNKNOWN_GAIN_WRITE_BLOCKER: String get() = if (Proven.channel == BuildChannel.PERF) "KT send blocked: channel layout is unknown (no valid 0x01 reply), and 0x66 does not prove zero digital DAC gain on every possible channel." else "Can't send: Contour can't tell how this DAC's channels are set up."
    /** The vendor's clear-flash command ([KtMicroReset.COMMAND]); built by [compileClear] only. */
    private const val CLEAR = KtMicroReset.COMMAND
    /** The only filter the vendor tool offers for this chip (native type 0); 3 and 4 are shelves another tool may have stored. */
    private val READ_TYPES = setOf(0, 3, 4)

    class Frame internal constructor(bytes: ByteArray, val mutating: Boolean,
                                     val delayAfterMs: Int = 0, val expectsReply: Boolean = false) {
        private val bytes = bytes.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
        val reportId: Int get() = REPORT_ID
    }
    data class Reply(val deviceKey: String, val reportId: Int, val payload: ByteArray)
    data class RegisterValue(val register: Int, val value: List<Int>) {
        init { require(register in 0..255 && value.size == 4 && value.all { it in 0..255 }) }
    }
    data class RawBand(val index: Int, val gainTenths: Int, val storedFrequencyHz: Int,
                       val qMilli: Int, val nativeType: Int, val qReservedByte: Int = 0) {
        init {
            require(index in 0..4 && gainTenths in Short.MIN_VALUE..Short.MAX_VALUE)
            require(storedFrequencyHz in 0..65535 && qMilli in 0..65535 && qReservedByte in 0..255)
            require(nativeType in READ_TYPES) { "Unknown KT native type $nativeType" }
        }
    }
    data class DigitalGain(val leftDb: Double, val rightDb: Double?) {
        val commonDb: Double? get() = leftDb.takeIf { rightDb == null || rightDb == it }
        val isZero: Boolean get() = leftDb == 0.0 && (rightDb == null || rightDb == 0.0)
        fun describe(): String = if (rightDb == null) "digital DAC gain $leftDb dB (single DAC; read-only)"
            else "digital DAC gain L $leftDb dB / R $rightDb dB (read-only)"
    }
    class Snapshot internal constructor(val model: KtMicroCatalog.Model, val deviceKey: String,
        val bands: List<RawBand>, val registers: List<RegisterValue>, val eqControl: Int,
        val digitalGain: DigitalGain?) {
        val eqEnabled: Boolean get() = eqControl == EQ_ON
        // With no flags, both candidate channel bytes must be zero before a send can be safe.
        val hasZeroDigitalGain: Boolean get() = digitalGain?.isZero ?: registers.singleOrNull {
            it.register == DIGITAL_GAIN_REGISTER
        }?.value?.take(2)?.all { it == 0 } ?: false
        val writeBlocker: String? get() = if (digitalGain == null && !hasZeroDigitalGain) UNKNOWN_GAIN_WRITE_BLOCKER else null
    }
    class Plan internal constructor(val model: KtMicroCatalog.Model, val deviceKey: String,
        val frames: List<Frame>, val expectedRegisters: List<RegisterValue>,
        val expectedBands: List<RawBand>, val expectedEqControl: Int,
        /** A Global Gain write ([compileGlobalGain]): the 0x3B value the read after it must show (not part of [expectedRegisters]: the EQ read does not include 0x3B). */
        val expectedGlobalGain: RegisterValue? = null) {
        /** True when the plan ends at SAVE and the DAC leaves the bus after it (no SAVE: the write stays in RAM and is read back in the same session). */
        val reconnectAfterSave: Boolean get() = model.reconnectAfterSave && frames.any { (it.payload[4].toInt() and 255) == SAVE }
    }

    private fun packet(reg: Int, cmd: Int, value: List<Int> = listOf(0, 0, 0, 0)): ByteArray {
        require(reg in 0..255 && value.size == 4 && value.all { it in 0..255 })
        return byteArrayOf(reg.toByte(), 0, 0, 0, cmd.toByte(), 0,
            value[0].toByte(), value[1].toByte(), value[2].toByte(), value[3].toByte())
    }
    fun readRegister(register: Int): Frame = Frame(packet(register, READ,
        if (register == EQ_ENABLE_REGISTER) listOf(3, 0, 0, 0) else listOf(0, 0, 0, 0)),
        mutating = false, expectsReply = true)
    /** Required EQ state: the enable register, both registers of every band, the digital DAC gain. */
    fun readQueries(model: KtMicroCatalog.Model): List<Frame> =
        listOf(readRegister(model.registers.enableRegister)) + model.registers.bands.flatMap {
            listOf(readRegister(it.gainFrequency), readRegister(it.qType))
        } + readRegister(model.registers.digitalGainRegister)

    /** Caller binds pending request and response to the SAME connection (deviceKey). */
    fun parseRegister(reply: Reply, deviceKey: String, register: Int, command: Int = READ): RegisterValue {
        require(reply.deviceKey == deviceKey && reply.reportId == REPORT_ID) { "Wrong KT device/report ID" }
        require(command == READ || command == WRITE) { "Only correlated register responses" }
        val p = reply.payload
        require(p.size >= PAYLOAD_BYTES && u(p[0]) == register && u(p[4]) == command) { "Wrong KT register/command/length" }
        require(listOf(1, 2, 3, 5).all { p[it] == 0.toByte() }) { "Malformed KT header" }
        return RegisterValue(register, (6..9).map { u(p[it]) })
    }
    fun parseEqControl(value: RegisterValue, map: KtMicroCatalog.RegisterMap = KtMicroCatalog.kt0211): Int {
        require(value.register == map.enableRegister && value.value[0] in setOf(EQ_OFF, EQ_ON)) { "Unqualified KT EQ enable value" }
        return value.value[0]
    }
    fun parseDigitalGain(value: RegisterValue, singleDac: Boolean = false): DigitalGain {
        require(value.register == DIGITAL_GAIN_REGISTER)
        fun db(at: Int) = value.value[at].toByte().toInt() * 0.5
        return DigitalGain(db(0), if (singleDac) null else db(1))
    }
    fun checkWriteAck(reply: Reply, deviceKey: String, register: Int, map: KtMicroCatalog.RegisterMap) {
        val value = parseRegister(reply, deviceKey, register, WRITE).value
        require(value == listOf(map.writeAck, 0, 0, 0)) {
            "KT write not acknowledged at 0x${register.toString(16)}: expected 0x${map.writeAck.toString(16)}, received ${value.joinToString { it.toString(16) }}; send stopped"
        }
    }
    fun decodeBand(index: Int, map: KtMicroCatalog.RegisterMap, gf: RegisterValue, qt: RegisterValue): RawBand {
        require(index in map.bands.indices)
        require(gf.register == map.bands[index].gainFrequency && qt.register == map.bands[index].qType)
        val gain = le(gf.value, 0).let { if (it > 32767) it - 65536 else it }
        return RawBand(index, gain, le(gf.value, 2), le(qt.value, 0), qt.value[2], qt.value[3])
    }
    /** Low-level roundtrip preserves all four register bytes, including reserved capture values. */
    fun encodeRawBand(map: KtMicroCatalog.RegisterMap, band: RawBand): List<RegisterValue> {
        val pair = map.bands[band.index]
        return listOf(RegisterValue(pair.gainFrequency, word(band.gainTenths) + word(band.storedFrequencyHz)),
            RegisterValue(pair.qType, word(band.qMilli) + listOf(band.nativeType, band.qReservedByte)))
    }
    fun snapshot(model: KtMicroCatalog.Model, deviceKey: String, replies: List<Reply>): Snapshot {
        val expected = readQueries(model).map { u(it.payload[0]) }.toSet()
        val flagsReplies = replies.filter { it.payload.firstOrNull()?.let(::u) == CHANNEL_FLAGS_REGISTER }
        val requiredReplies = replies.filterNot { it in flagsReplies }
        require(requiredReplies.size == expected.size) { "KT snapshot needs every register exactly once" }
        val values = requiredReplies.map {
            require(it.payload.isNotEmpty())
            val reg = u(it.payload[0]); require(reg in expected)
            parseRegister(it, deviceKey, reg)
        }
        require(values.map { it.register }.toSet() == expected) { "Missing/duplicate KT register" }
        // Flags are optional metadata, absent from the shipped-device captures. Invalid flags
        // cannot invalidate the required EQ/gain registers or establish a channel layout.
        val flags = flagsReplies.singleOrNull()?.let { runCatching { parseRegister(it, deviceKey, CHANNEL_FLAGS_REGISTER) }.getOrNull() }
        val byReg = values.associateBy { it.register }
        val bands = model.registers.bands.mapIndexed { i, pair ->
            decodeBand(i, model.registers, byReg.getValue(pair.gainFrequency), byReg.getValue(pair.qType))
        }
        return Snapshot(model, deviceKey, bands, (values + listOfNotNull(flags)).sortedBy { it.register },
            parseEqControl(byReg.getValue(model.registers.enableRegister), model.registers),
            flags?.let { parseDigitalGain(byReg.getValue(DIGITAL_GAIN_REGISTER), it.value[1] and 2 != 0) })
    }

    /** Full preflight before ANY frame is returned. [eqEnabled] false writes the EQ switch off (register 0x24 = 2), true on (3); null leaves the switch as
     * [before] found it, so no frame touches register 0x24 (the app's send: the switch on the DAC page is the only way to change it). The bands
     * are written either way. A preamp is not supported: only 0 dB is accepted, never silently dropped. */
    fun compile(profile: Profile, before: Snapshot, eqEnabled: Boolean? = true): Plan {
        val model = before.model
        require(before.writeBlocker == null) { requireNotNull(before.writeBlocker) }
        require(model in KtMicroCatalog.allModels) { "Unqualified KT model" }
        val eqControl = when (eqEnabled) { true -> EQ_ON; false -> EQ_OFF; null -> before.eqControl }
        require(before.eqControl in setOf(EQ_OFF, EQ_ON)) { "Only validated KT EQ enable values (2 off, 3 custom)" }
        require(profile.bands.size <= model.bandCount) { "KT supports ${model.bandCount} bands; no truncation" }
        profile.bands.forEachIndexed { i, b -> validateBand(model, b, i + 1) }
        val requestedPregain = profile.effectivePreampDb(model.shelfForm)
        require(requestedPregain.isFinite() && requestedPregain == 0.0) { "${model.productName} has no preamp: only 0 dB is accepted (its Global Gain is the analog DAC gain, not written)" }
        require(before.hasZeroDigitalGain) { "Nonzero device digital DAC gain is read-only; cannot realize requested 0 dB" }
        val bands = List(model.bandCount) { i ->
            val b = profile.bands.getOrNull(i)
            if (b == null || !b.enabled) RawBand(i, 0, 100, 1000, 0)
            else RawBand(i, tenthsDown(b.gainDb), quantize(model.registerFrequency(b.freqHz)), quantize(b.q * registerQScale(model, b.type) * 1000), typeCode(model, b.type))
        }
        val encoded = bands.flatMap { encodeRawBand(model.registers, it) }
        val enableValue = enableTail(model, before)
        val writes = mutableListOf<Frame>()
        if (before.eqControl != eqControl) writes += Frame(packet(EQ_ENABLE_REGISTER, WRITE, listOf(eqControl) + enableValue),
            mutating = true, expectsReply = true)
        writes += encoded.map { Frame(packet(it.register, WRITE, it.value), mutating = true, expectsReply = true) }
        writes += Frame(packet(0, SAVE), mutating = true, delayAfterMs = 1000)
        val expected = before.registers.map { reg ->
            encoded.singleOrNull { it.register == reg.register }
                ?: if (reg.register == EQ_ENABLE_REGISTER) RegisterValue(EQ_ENABLE_REGISTER, listOf(eqControl) + reg.value.drop(1)) else reg
        }.sortedBy { it.register }
        return Plan(model, before.deviceKey, writes.toList(), expected, bands, eqControl)
    }

    /** Only the EQ switch (register 0x24) and SAVE: the bands on the DAC stay as they are. Refused when the switch already is where [eqEnabled]
     * puts it (nothing to write), with the same blockers as [compile]. */
    fun compileSwitch(before: Snapshot, eqEnabled: Boolean): Plan {
        val model = before.model
        require(before.writeBlocker == null) { requireNotNull(before.writeBlocker) }
        require(model in KtMicroCatalog.allModels) { "Unqualified KT model" }
        require(before.eqControl in setOf(EQ_OFF, EQ_ON)) { "Only validated KT EQ enable values (2 off, 3 custom)" }
        val eqControl = if (eqEnabled) EQ_ON else EQ_OFF
        require(before.eqControl != eqControl) { "The EQ on ${model.productName} is already ${if (eqEnabled) "ON" else "OFF"}" }
        val enableValue = enableTail(model, before)
        val writes = listOf(
            Frame(packet(EQ_ENABLE_REGISTER, WRITE, listOf(eqControl) + enableValue), mutating = true, expectsReply = true),
            Frame(packet(0, SAVE), mutating = true, delayAfterMs = 1000),
        )
        val expected = before.registers.map { reg ->
            if (reg.register == EQ_ENABLE_REGISTER) RegisterValue(EQ_ENABLE_REGISTER, listOf(eqControl) + reg.value.drop(1)) else reg
        }.sortedBy { it.register }
        return Plan(model, before.deviceKey, writes, expected, before.bands, eqControl)
    }

    /** Bytes 1-3 a switch write sends: what the read held (echoed, Allegro Mini), or zeros where they are device status ([KtMicroCatalog.Model.eqEnableStatusBytes], JM12). */
    private fun enableTail(model: KtMicroCatalog.Model, before: Snapshot): List<Int> =
        if (model.eqEnableStatusBytes) listOf(0, 0, 0) else before.registers.single { it.register == EQ_ENABLE_REGISTER }.value.drop(1)

    /** Does [read] hold what [e] expects? Register 0x24 of a model whose bytes 1-3 are status is compared on byte 0 (the switch) only. */
    fun sameRegister(model: KtMicroCatalog.Model, e: RegisterValue, read: RegisterValue): Boolean =
        if (e.register == EQ_ENABLE_REGISTER && model.eqEnableStatusBytes) e.value[0] == read.value[0] else e.value == read.value

    /** The vendor tool's Global Gain steps (register 0x3B, `DacAnaGain_ALL`): 1-15 = -18..+3 dB in 1.5 dB, 13 = 0 dB (the default). Step 0 has no dB in the tool: never written. */
    const val GLOBAL_GAIN_STEP_MIN = 1
    const val GLOBAL_GAIN_STEP_MAX = 15
    const val GLOBAL_GAIN_STEP_DEFAULT = 13
    /** The dB of Global Gain [step] (-18.0 at step 1); refuses step 0 and anything outside 1-15. */
    fun globalGainDb(step: Int): Double {
        requireGlobalGainStep(step)
        return -18.0 + (step - GLOBAL_GAIN_STEP_MIN) * 1.5
    }
    fun requireGlobalGainStep(step: Int) {
        require(step in GLOBAL_GAIN_STEP_MIN..GLOBAL_GAIN_STEP_MAX) {
            if (step == 0) "Global Gain step 0 is unknown in the vendor tool: never written" else "Global Gain step $step is outside $GLOBAL_GAIN_STEP_MIN..$GLOBAL_GAIN_STEP_MAX"
        }
    }
    /** The step a 0x3B read holds, or null when its bytes are not `[step, 0, 0, 0]` with the step in 1-15 (the layout is not proven on hardware; anything else is not written over). */
    fun globalGainStep(value: RegisterValue): Int? {
        if (value.register != GLOBAL_GAIN_REGISTER) return null
        return value.value[0].takeIf { it in GLOBAL_GAIN_STEP_MIN..GLOBAL_GAIN_STEP_MAX && value.value.drop(1).all { b -> b == 0 } }
    }

    /**
     * Only the Global Gain (register 0x3B) to [step], and (with [save]) SAVE: the bands and the EQ switch stay. The vendor tool writes the step as a
     * little-endian 32-bit number: `3b 00 00 00 57 00 s 00 00 00` (the step index being that number is an inference to prove on the bench). Refused before any
     * frame exists: step 0 or outside 1-15, a 0x3B [current] read that is not a plain step (the layout would then be wrong), the same step already set,
     * and the blockers of [compile]. [current] is what the DAC reads now, so the restore is the write of its step. Without [save] the write stays in RAM and
     * the session reads 0x3B back at once; with it the DAC leaves the bus and the read after it is back decides ([Plan.expectedGlobalGain]).
     */
    fun compileGlobalGain(before: Snapshot, current: RegisterValue, step: Int, save: Boolean = true): Plan {
        val model = before.model
        require(model.globalGainUserSteps != null) { "${model.productName} offers no Global Gain: register 0x3B is never written for it" }
        requireGlobalGainStep(step)
        require(before.writeBlocker == null) { requireNotNull(before.writeBlocker) }
        require(model in KtMicroCatalog.allModels) { "Unqualified KT model" }
        val now = requireNotNull(globalGainStep(current)) {
            "Global Gain (0x3B) reads ${hexBytes(current.value)}, not a step 1-15: the layout is not the one Contour knows, nothing written"
        }
        require(now != step) { "Global Gain on ${model.productName} is already step $step" }
        val value = listOf(step, 0, 0, 0)
        val writes = mutableListOf(Frame(packet(GLOBAL_GAIN_REGISTER, WRITE, value), mutating = true, expectsReply = true))
        if (save) writes += Frame(packet(0, SAVE), mutating = true, delayAfterMs = 1000)
        return Plan(model, before.deviceKey, writes, before.registers.sortedBy { it.register }, before.bands, before.eqControl, RegisterValue(GLOBAL_GAIN_REGISTER, value))
    }

    /**
     * The CLEAR frame (`00 00 00 00 43 00 00 00 00 00`, the vendor's flash clear) from [ticket], which it spends: the only place in :core that builds it. The DAC is expected to echo
     * it (see [parseClearAck]); there is no SAVE after it.
     */
    internal fun compileClear(ticket: KtMicroResetTicket): Frame {
        ticket.spend()
        return Frame(packet(0, CLEAR), mutating = true, expectsReply = true)
    }

    /** True when [payload] is the DAC's answer to CLEAR: register 0 and command 0x43 with a zero header (the match devicePEQ's `sendCommandWithResponse` makes). */
    fun isClearReply(payload: ByteArray): Boolean =
        payload.size >= PAYLOAD_BYTES && u(payload[0]) == 0 && u(payload[4]) == CLEAR && listOf(1, 2, 3, 5).all { payload[it] == 0.toByte() }

    /** The ack value of a CLEAR reply: byte 6, which the vendor tool waits to see as 3 (`writeAck`); anything else is reported, not trusted. */
    fun clearAck(payload: ByteArray, map: KtMicroCatalog.RegisterMap = KtMicroCatalog.kt0211): Boolean {
        require(isClearReply(payload)) { "Not a CLEAR reply" }
        return u(payload[6]) == map.writeAck
    }

    /**
     * The writes that put the backup's EQ back after a CLEAR: for every register of [KtMicroReset.restorable] (the EQ switch and the ten band registers) that [after] holds
     * differently from [backup], the backup's own four bytes (acknowledged, in register order), then SAVE. Null = nothing differs, nothing to write. No profile is rebuilt: the
     * bytes are what the DAC itself returned, so a band another tool stored (a shelf, a Q reserved byte) is restored byte for byte, and the blockers of [compile] (which guard
     * a NEW EQ) do not apply. The DAC leaves the bus after SAVE ([Plan.reconnectAfterSave]); the read after it must show [Plan.expectedRegisters] (every restorable register of the backup).
     */
    fun compileRestore(backup: Snapshot, after: Snapshot): Plan? {
        val model = backup.model
        require(model in KtMicroCatalog.allModels && after.model == model) { "Unqualified KT model" }
        val restorable = KtMicroReset.restorable(model)
        val have = after.registers.associateBy { it.register }
        val want = backup.registers.filter { it.register in restorable }.sortedBy { it.register }
        require(want.size == restorable.size) { "The backup lacks a register the restore writes" }
        val differing = want.filter { have[it.register]?.value != it.value }
        if (differing.isEmpty()) return null
        val writes = differing.map { Frame(packet(it.register, WRITE, it.value), mutating = true, expectsReply = true) } +
            Frame(packet(0, SAVE), mutating = true, delayAfterMs = 1000)
        return Plan(model, after.deviceKey, writes, want, backup.bands, backup.eqControl)
    }

    /** [mismatches] and the Global Gain: when [plan] wrote it, the 0x3B value read after must be the one written ([globalGain] null = it was not read). */
    fun mismatches(plan: Plan, back: Snapshot, globalGain: RegisterValue?): List<String> {
        val out = ArrayList(mismatches(plan, back))
        plan.expectedGlobalGain?.let { e ->
            if (globalGain == null) out += "register 0x%02x: not read back".format(e.register)
            else if (globalGain.value != e.value) out += "register 0x%02x: wrote %s, read %s".format(e.register, hexBytes(e.value), hexBytes(globalGain.value))
        }
        return out
    }

    /** What the read after the DAC is back must show ([Plan.expectedRegisters], [Plan.expectedEqControl]) against [back]: one line per register that differs; empty = verified. */
    fun mismatches(plan: Plan, back: Snapshot): List<String> {
        val out = ArrayList<String>()
        val have = back.registers.associateBy { it.register }
        plan.expectedRegisters.forEach { e ->
            val r = have[e.register]
            if (r == null) out += "register 0x%02x: not read back".format(e.register)
            else if (!sameRegister(plan.model, e, r)) out += "register 0x%02x: wrote %s, read %s".format(e.register, hexBytes(e.value), hexBytes(r.value))
        }
        if (back.eqControl != plan.expectedEqControl) out += "EQ switch: wrote ${plan.expectedEqControl}, read ${back.eqControl}"
        return out
    }
    private fun hexBytes(v: List<Int>) = v.joinToString(" ") { "%02x".format(it) }

    /** Why [profile] cannot be written to [model] (empty = it can): the same rules as [compile]'s preflight, without a snapshot and
     * without throwing. A missing preamp counts as 0 dB (the DAC has none). */
    fun issues(model: KtMicroCatalog.Model, profile: Profile): List<String> {
        val out = ArrayList<String>()
        if (profile.bands.size > model.bandCount) out += "KT supports ${model.bandCount} bands; no truncation"
        profile.bands.forEachIndexed { i, b -> runCatching { validateBand(model, b, i + 1) }.exceptionOrNull()?.let { out += it.message ?: "Band ${i + 1} refused" } }
        val pre = profile.preampDb ?: 0.0
        if (!(pre.isFinite() && pre == 0.0)) out += "${model.productName} has no preamp: only 0 dB is accepted (its Global Gain is the analog DAC gain, not written)"
        return out
    }
    private fun validateBand(model: KtMicroCatalog.Model, b: Band, n: Int) {
        require(b.type in model.nativeTypes) { "Band $n: ${model.productName} takes ${typeList(model)} only" }
        require(b.freqHz.isFinite() && b.freqHz in model.minFrequencyHz..model.maxFrequencyHz) { "Band $n: freq ${b.freqHz} Hz outside ${model.minFrequencyHz}-${model.maxFrequencyHz} Hz" }
        // a shelf may have narrower limits than a peak (record peq.shelfGainDb, peq.shelfQ: what the unit was measured to play)
        val gains = model.gainRange(b.type)
        require(b.gainDb.isFinite() && b.gainDb in gains) { "Band $n: gain ${b.gainDb} dB outside ${gains.start}..${gains.endInclusive} dB" + limitNote(model, b.type) }
        // the register Q of a shelf is the intended Q x the model's shelf scale (1.0 on the JM12), so the intended range is the register range divided by it
        val scale = registerQScale(model, b.type)
        val qs = model.qRange(b.type)
        require(b.q.isFinite() && b.q * scale in model.minQ..model.maxQ && b.q in qs.start..qs.endInclusive) { "Band $n: Q ${b.q} outside ${qs.start}-${qs.endInclusive}" + limitNote(model, b.type) }
    }
    /** Said after a refusal that comes from a shelf's own limits rather than the device's. */
    private fun limitNote(model: KtMicroCatalog.Model, type: FilterType) =
        if (type != FilterType.PEAK && model.shelfLimits != null) " (a shelf on the ${model.name} is limited to what was measured)" else ""
    /** "PEAK bands" for a model that offers PEAK only (the Allegro Mini), otherwise the offered types by name ("PEAK, LOW_SHELF and HIGH_SHELF bands"). */
    private fun typeList(model: KtMicroCatalog.Model): String {
        val names = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF).filter { it in model.nativeTypes }.map { it.name }
        return (if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()) + " bands"
    }
    /** The register Q of a band is its intended Q times this: the model's shelf scale for a shelf, 1 for a peak. */
    private fun registerQScale(model: KtMicroCatalog.Model, type: FilterType): Double = if (type == FilterType.PEAK) 1.0 else model.shelfQScale
    /** Band gain in tenths of a dB, rounded DOWN (a cut never gets shallower, a boost never grows); on-grid values unchanged. */
    private fun tenthsDown(gainDb: Double): Int {
        require(gainDb.isFinite() && gainDb * 10 in -32768.0..65535.0)
        return floor(gainDb * 10 + 1e-6).toInt()
    }
    private fun quantize(v: Double): Int {
        require(v.isFinite() && v in -32768.0..65535.0)
        return floor(v + 0.5).toInt() // source Math.round, including negative half-ties
    }
    fun type(code: Int): FilterType = when (code) {
        0 -> FilterType.PEAK; 3 -> FilterType.LOW_SHELF; 4 -> FilterType.HIGH_SHELF
        else -> throw IllegalArgumentException("Unknown KT native type $code")
    }
    /** The native type code of [type] on [model]: PEAK 0, LOW_SHELF 3, HIGH_SHELF 4, and only what the model offers. */
    fun typeCode(model: KtMicroCatalog.Model, type: FilterType): Int = when {
        type !in model.nativeTypes -> throw IllegalArgumentException("Unsupported KT type $type")
        type == FilterType.PEAK -> 0
        type == FilterType.LOW_SHELF -> 3
        type == FilterType.HIGH_SHELF -> 4
        else -> throw IllegalArgumentException("Unsupported KT type $type")
    }
    private fun u(b: Byte) = b.toInt() and 255
    private fun le(v: List<Int>, at: Int) = v[at] or (v[at + 1] shl 8)
    private fun word(v: Int) = listOf(v and 255, (v ushr 8) and 255)
}
