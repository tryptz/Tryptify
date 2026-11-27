package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.isShelf
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure JVM framing of the native Moondrop protocol (Dawn Pro 2). Payloads EXCLUDE the report ID; the transport owns descriptor
 * discovery and padding. The read half is advBeta's MoondropCodec (devicePEQ 0617f382 moondropUsbHidHandler.js, and the Dawn Pro 2
 * read of 21.10.2026). The write half is the manufacturer's tool (MOONDROP Hub Beta 1.6.5, "Write Cfg", captured on the owner's unit
 * 24.10.2026: `01 0F 01 07`, eight band frames, `01 0A 04 00 00 FF FF`, `01 23`, `01 03`, `01 01 00`; see [writeSteps]) and FreeDSP-Studio dsp2.rs (MIT, 1b42f63;
 * same band frame and delays), which agree with devicePEQ on the band layout.
 *
 * Writes only ever go to the model's writable bank. Frames this object never builds: `01 05 ..` (flash clear), `01 FF ..`
 * (firmware update), `01 04 00` (offset save). A global gain (0x03) write exists only as [gainSteps] (never with `01 04 00`: the manufacturer's tool
 * sends it only with persist on, and its own apply flow passes persist off for both gains and saves with `01 01 00`), and every frame a session sends
 * for it goes through [requireSendable].
 */
object MoondropCodec {
    const val REPORT_ID = MoondropCatalog.REPORT_ID
    private const val READ = 0x80
    private const val WRITE = 0x01
    // The registers, delays and design rate the record carries (devices/moondrop-dawn-pro-2.json via GeneratedDevices); the band update (0x09), coefficient
    // commit (0x0A) and version (0x0C) commands are protocol and stay here.
    const val CMD_EQ_SAVE = GeneratedDevices.MOONDROP_DAWN_PRO_2_CMD_EQ_SAVE
    const val CMD_GLOBAL_GAIN = GeneratedDevices.MOONDROP_DAWN_PRO_2_CMD_GLOBAL_GAIN
    const val CMD_EQ_UPDATE = 0x09
    const val CMD_EQ_COEFF = 0x0A
    const val CMD_VERSION = 0x0C
    const val CMD_EQ_ACTIVE = GeneratedDevices.MOONDROP_DAWN_PRO_2_CMD_EQ_ACTIVE
    const val CMD_PRE_GAIN = GeneratedDevices.MOONDROP_DAWN_PRO_2_CMD_PRE_GAIN
    const val DELAY_BAND_MS = GeneratedDevices.MOONDROP_DAWN_PRO_2_DELAY_BAND_MS
    const val DELAY_OP_MS = GeneratedDevices.MOONDROP_DAWN_PRO_2_DELAY_OP_MS
    const val DELAY_FLASH_MS = GeneratedDevices.MOONDROP_DAWN_PRO_2_DELAY_FLASH_MS
    const val DELAY_EQ_INDEX_MS = GeneratedDevices.MOONDROP_DAWN_PRO_2_DELAY_EQ_INDEX_MS
    private const val DESIGN_FS = GeneratedDevices.MOONDROP_DAWN_PRO_2_DESIGN_RATE_HZ
    // The gaps MOONDROP Hub's Write Cfg leaves between its frames (capture 24.10.2026, ms): after the index switch, between bands, after the commit, after each gain.
    // A write waits the longer of these and the model's own delay.
    private const val HUB_SELECT_MS = 160L
    private const val HUB_BAND_MS = 35L
    private const val HUB_COMMIT_MS = 60L
    private const val HUB_GAIN_MS = 65L

    /** Payload bytes after the report ID: a band write (`01 09 21 00 i ...`, [bandPayload]) and the least a band reply must hold (coefficients, registers and the bank byte). */
    const val BAND_PAYLOAD_BYTES = 37
    const val MINIMUM_INPUT_PAYLOAD_BYTES = 36

    enum class Kind { SLOT, BAND, GLOBAL_GAIN, PRE_GAIN, VERSION }
    class Frame internal constructor(val kind: Kind, bytes: ByteArray, val index: Int? = null) {
        private val bytes = bytes.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
        val reportId: Int get() = REPORT_ID
        val mutating: Boolean get() = false
        val minimumDescriptorOutputPayloadBytes: Int get() = bytes.size
    }
    data class Reply(val deviceKey: String, val reportId: Int, val payload: ByteArray)
    data class RegisterValue(val kind: Kind, val index: Int?, val bytes: List<Int>)
    data class RawBand internal constructor(
        val index: Int,
        val frequencyHz: Int,
        val qQ8: Int,
        val gainQ8: Int,
        val nativeType: Int,
        val bank: Int,
        /** Signed little-endian Q30 words in source order [b0,b1,b2,-a1,-a2], NOT normalized. */
        val coefficientsQ30: List<Int>,
        val coefficientBytes: List<Int>,
    ) {
        val q: Double get() = qQ8 / 256.0
        val gainDb: Double get() = gainQ8 / 256.0
        val type: FilterType? get() = MoondropCatalog.TYPES[nativeType]
    }
    /** A Q8.8 register read as dB: [observedDb] is the raw value / 256, not a proven effective level. */
    data class Gain(val rawQ8: Int) { val db: Double get() = rawQ8 / 256.0 }
    class Snapshot internal constructor(
        val model: MoondropCatalog.Model,
        val deviceKey: String,
        val bands: List<RawBand>,
        /** Every full response, including the two bracket EQ index reads, reserved bytes and padding. */
        val registers: List<RegisterValue>,
        /** Register 0x0F, EQ_ACTIVE: the active EQ index. The manufacturer's "PEQ mode" is this index being one of the model's writable ones. */
        val activeIndex: Int,
        /** Register 0x03 (DAC offset): the manufacturer's GLOBAL GAIN, -18..0 dB. */
        val globalGain: Gain,
        /** Register 0x23, the manufacturer's PRE-GAIN (the preamp, -128..0 dB); null when it was not read or did not answer. */
        val preGain: Gain?,
    ) {
        val peqMode: Boolean get() = model.isWritable(activeIndex)
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    fun slotQuery(): Frame = Frame(Kind.SLOT, byteArrayOf(READ.toByte(), CMD_EQ_ACTIVE.toByte(), 0))
    fun versionQuery(): Frame = Frame(Kind.VERSION, byteArrayOf(READ.toByte(), CMD_VERSION.toByte()))
    fun globalGainQuery(): Frame = Frame(Kind.GLOBAL_GAIN, byteArrayOf(READ.toByte(), CMD_GLOBAL_GAIN.toByte()))
    fun preGainQuery(): Frame = Frame(Kind.PRE_GAIN, byteArrayOf(READ.toByte(), CMD_PRE_GAIN.toByte(), 0))
    fun bandQuery(index: Int): Frame {
        require(index in 0..7) { "Native Moondrop has eight band indices" }
        return Frame(Kind.BAND, byteArrayOf(READ.toByte(), CMD_EQ_UPDATE.toByte(), 24, 0, index.toByte(), 0), index)
    }
    /** What MDHUB's `readFirmwareVersion` decodes (hub.js `Wh`): the bytes after the three-byte header (`80 0C len`) with the zeros dropped, as UTF-8; [raw] is the whole reply payload. No capture of a Dawn Pro 2 reply exists yet. */
    data class Firmware(val text: String, val raw: List<Int>)

    /** Sequence: EQ index, ALL eight bands in order, global gain, EQ index again. */
    fun readQueries(model: MoondropCatalog.Model): List<Frame> =
        listOf(slotQuery()) + (0 until model.bandCount).map { bandQuery(it) } + listOf(globalGainQuery(), slotQuery())

    private fun correlated(reply: Reply, deviceKey: String, inputPayloadBytes: Int, minimum: Int, opcode: Int): ByteArray {
        require(deviceKey.isNotBlank() && reply.deviceKey == deviceKey) { "Wrong Moondrop connection/deviceKey" }
        require(reply.reportId == REPORT_ID) { "Wrong Moondrop report ID; payload must exclude ID" }
        require(inputPayloadBytes >= minimum && reply.payload.size == inputPayloadBytes) {
            "Wrong Moondrop payload length; require exact descriptor input length and complete fields"
        }
        val p = reply.payload.copyOf()
        require(u(p[0]) == READ && u(p[1]) == opcode) { "Wrong native Moondrop read ID/opcode" }
        return p
    }
    fun parseSlot(reply: Reply, deviceKey: String, inputPayloadBytes: Int): Int {
        val p = correlated(reply, deviceKey, inputPayloadBytes, 4, CMD_EQ_ACTIVE)
        require(p[2] == 0.toByte()) { "Wrong native EQ index header" }
        return u(p[3])
    }
    fun parseGain(reply: Reply, deviceKey: String, inputPayloadBytes: Int, opcode: Int): Gain {
        require(opcode == CMD_GLOBAL_GAIN || opcode == CMD_PRE_GAIN)
        val p = correlated(reply, deviceKey, inputPayloadBytes, 5, opcode)
        return Gain(s16(p, 3))
    }
    /** The firmware version reply (`80 0C ..`), decoded the way MDHUB does. The layout is MDHUB's, not proven on a unit: the caller shows [Firmware.raw] too. */
    fun parseVersion(reply: Reply, deviceKey: String, inputPayloadBytes: Int): Firmware {
        val p = correlated(reply, deviceKey, inputPayloadBytes, 4, CMD_VERSION)
        val text = String(p.copyOfRange(3, p.size).filter { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        return Firmware(text, p.map { u(it) })
    }
    fun parseBand(reply: Reply, deviceKey: String, model: MoondropCatalog.Model, index: Int, expectedBank: Int, inputPayloadBytes: Int): RawBand {
        require(index in 0 until model.bandCount && expectedBank in 0..255) { "Invalid band index/expected bank" }
        val p = correlated(reply, deviceKey, inputPayloadBytes, 36, CMD_EQ_UPDATE)
        require(u(p[2]) == 24 && p[3] == 0.toByte() && u(p[4]) == index &&
            p[5] == 0.toByte() && p[6] == 0.toByte()) { "Native header/index echo missing or out of order" }
        require(u(p[35]) == expectedBank) { "Band bank ${u(p[35])} does not match the active EQ index $expectedBank" }
        val frequency = u16(p, 27)
        val q = s16(p, 29) // Preserve signed Q8, never turn negative values into unsigned Q.
        val gain = s16(p, 31)
        val type = u(p[33])
        require(type == 0 || type in model.nativeTypes) { "Unknown native type $type" }
        if (type != 0) require(frequency in 11..23999 && q > 0) { "Invalid native band, no disabled/flat filling" }
        return RawBand(index, frequency, q, gain, type, u(p[35]),
            (0..4).map { s32(p, 7 + it * 4) }, (7..26).map { u(p[it]) })
    }
    /** Exact ordered completion only. No partially initialized snapshot or fabricated flat bands can escape. [preGain] is the
     * optional extra read (0x23) after the ordered ones. */
    fun snapshot(model: MoondropCatalog.Model, deviceKey: String, replies: List<Reply>, inputPayloadBytes: Int, preGain: Reply? = null): Snapshot {
        val queries = readQueries(model)
        require(replies.size == queries.size) { "Moondrop snapshot needs every ordered response including both EQ index reads" }
        val slot = parseSlot(replies.first(), deviceKey, inputPayloadBytes)
        val after = parseSlot(replies.last(), deviceKey, inputPayloadBytes)
        require(slot == after) { "Active EQ index changed during read" }
        val bands = (0 until model.bandCount).map { i -> parseBand(replies[i + 1], deviceKey, model, i, slot, inputPayloadBytes) }
        val global = parseGain(replies[model.bandCount + 1], deviceKey, inputPayloadBytes, CMD_GLOBAL_GAIN)
        val pre = preGain?.let { parseGain(it, deviceKey, inputPayloadBytes, CMD_PRE_GAIN) }
        val registers = queries.zip(replies).map { (frame, reply) -> RegisterValue(frame.kind, frame.index, reply.payload.map { u(it) }) } +
            listOfNotNull(preGain?.let { RegisterValue(Kind.PRE_GAIN, null, it.payload.map { b -> u(b) }) })
        return Snapshot(model, deviceKey, bands, registers, slot, global, pre)
    }

    // ---- writes --------------------------------------------------------------------------------------------------------

    /** One report to send (payload without the ID) and the pause after it. */
    class WriteStep(val payload: ByteArray, val delayAfterMs: Long, val what: String) {
        override fun toString() = "$what: " + payload.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    }
    /** One band as it goes on the wire; [coefficients] are the 20 biquad bytes (b0, b1, b2, -a1, -a2, int32 LE, Q30, 96 kHz). */
    class BandWrite(val index: Int, val frequencyHz: Int, val qQ8: Int, val gainQ8: Int, val nativeType: Int, val coefficients: ByteArray) {
        init {
            require(index in 0..7 && frequencyHz in 1..65535 && qQ8 in 1..32767 && gainQ8 in Short.MIN_VALUE..Short.MAX_VALUE)
            require(nativeType in MoondropCatalog.TYPES) { "Native type $nativeType is not one the manufacturer's tool writes" }
            require(coefficients.size == 20)
        }
    }

    /** [band] as the manufacturer's tool writes it: integer Hz, Q and gain as signed Q8.8, the biquad computed for 96 kHz. The
     * pass filters are the plain RBJ low-pass and high-pass; peak and shelves are [WalkPlay.computeIir] (devicePEQ's, shelf slope
     * form). [coefficients] given = restore a band byte for byte as it was read. A shelf's Q register is the intended Q times [shelfQScale] (the unit plays the register divided by it,
     * [MoondropCatalog.Model.shelfQScale]); the biquad stays on the intended Q, as the register is what the DSP plays (measured 25.10.2026) and the coefficients are the vendor tool's design. */
    fun bandWrite(index: Int, band: Band, coefficients: ByteArray? = null, shelfQScale: Double = 1.0): BandWrite {
        val code = nativeCode(band.type)
        val freq = WalkPlay.toInt32(WalkPlay.jsRound(band.freqHz))
        val q8 = WalkPlay.toInt32(WalkPlay.jsRound(band.q * (if (band.type.isShelf) shelfQScale else 1.0) * 256))
        val g8 = if (band.type == FilterType.LOW_PASS || band.type == FilterType.HIGH_PASS) 0 else WalkPlay.toInt32(WalkPlay.jsRound(band.gainDb * 256))
        // The biquad uses the values as the user set them (as WalkPlay.bandWriteReport does); the registers get the rounded ones.
        return BandWrite(index, freq, q8, g8, code, coefficients ?: biquad(band.type, band.freqHz, band.gainDb, band.q))
    }
    /** The unit's own default band frequencies (bank 9 on the owner's unit, 21.10.2026). */
    private val DEFAULT_FREQS = intArrayOf(20, 60, 200, 600, 1400, 5400, 14000, 20000)
    private const val NEUTRAL_Q = 0.707
    /** A band that does nothing: PK, 0 dB, Q 0.707, at the unit's default frequency for [index]. */
    fun neutralBand(index: Int): BandWrite =
        bandWrite(index, Band("neutral$index", FilterType.PEAK, DEFAULT_FREQS[index].toDouble(), 0.0, NEUTRAL_Q))

    /** A write of [bands] to the writable bank as the bench plans it. [issues] empty = [bands] (all eight) can be sent. */
    class Planned(val bands: List<BandWrite>, val preGainDb: Double?, val issues: List<String>)

    /** Checks [bands] against [model] (count, type, frequency, gain (not for LP and HP), Q, pre-gain, Q30 range) without throwing and
     * builds the eight band writes: a disabled band or a missing one is neutral; [coefficients] (band index to its 20 bytes) restore a
     * band byte for byte as it was read. */
    fun planFor(model: MoondropCatalog.Model, bands: List<Band>, preGainDb: Double?, coefficients: Map<Int, ByteArray> = emptyMap()): Planned {
        val issues = ArrayList<String>()
        if (bands.size > model.bandCount) issues += "${model.productName} has ${model.bandCount} bands; ${bands.size} given, no truncation"
        if (preGainDb != null && !(preGainDb.isFinite() && preGainDb in model.preGainMinDb..model.preGainMaxDb))
            issues += "Pre-gain $preGainDb dB outside ${model.preGainMinDb}..${model.preGainMaxDb} dB"
        val writes = List(model.bandCount) { i ->
            val b = bands.getOrNull(i)
            if (b == null || !b.enabled) neutralBand(i)
            else {
                val n = i + 1
                val pass = b.type == FilterType.LOW_PASS || b.type == FilterType.HIGH_PASS
                if (b.type !in model.types) issues += "Band $n: ${b.type} is not offered for ${model.productName}"
                if (!(b.freqHz.isFinite() && b.freqHz in model.minFrequencyHz..model.maxFrequencyHz)) issues += "Band $n: freq ${b.freqHz} Hz outside ${model.minFrequencyHz}-${model.maxFrequencyHz} Hz"
                if (!pass && !(b.gainDb.isFinite() && b.gainDb in model.minGainDb..model.maxGainDb)) issues += "Band $n: gain ${b.gainDb} dB outside ${model.minGainDb}..${model.maxGainDb} dB"
                val scale = if (b.type.isShelf) model.shelfQScale else 1.0
                if (!(b.q.isFinite() && b.q in model.minQ / scale..model.maxQ / scale)) issues += "Band $n: Q ${b.q} outside ${model.minQ / scale}-${model.maxQ / scale}"
                runCatching { bandWrite(i, b, coefficients[i], model.shelfQScale) }.getOrElse { e -> issues += "Band $n: ${e.message ?: "cannot be encoded"}"; neutralBand(i) }
            }
        }
        return Planned(writes, preGainDb, issues)
    }
    /** The Q a band of [type] plays at, from its register [qRegister]: a shelf's register is the intended Q times the model's scale. */
    fun intendedQ(model: MoondropCatalog.Model, type: FilterType?, qRegister: Double): Double = if (type?.isShelf == true) qRegister / model.shelfQScale else qRegister
    fun nativeCode(type: FilterType): Int = MoondropCatalog.TYPES.entries.single { it.value == type }.key
    private fun biquad(type: FilterType, freq: Double, gainDb: Double, q: Double): ByteArray = when (type) {
        FilterType.LOW_PASS, FilterType.HIGH_PASS -> {
            val w0 = freq * 2 * PI / DESIGN_FS
            val c = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val (b0, b1) = if (type == FilterType.LOW_PASS) (1 - c) / 2 to (1 - c) else (1 + c) / 2 to -(1 + c)
            pack(doubleArrayOf(b0 / a0, b1 / a0, b0 / a0, 2 * c / a0, -(1 - alpha) / a0))
        }
        else -> WalkPlay.computeIir(freq, gainDb, q, nativeCode(type))
    }
    private fun pack(values: DoubleArray): ByteArray {
        val out = ByteArray(20)
        values.forEachIndexed { i, v ->
            val scaled = v * 1073741824.0
            val k = if (i < 3) WalkPlay.jsRound(scaled) else -WalkPlay.jsRound(-scaled)
            require(k >= Int.MIN_VALUE.toDouble() && k <= Int.MAX_VALUE.toDouble()) { "Coefficient $i=$k not representable as signed Q30" }
            val n = k.toLong().toInt()
            for (b in 0..3) out[i * 4 + b] = ((n shr (8 * b)) and 0xFF).toByte()
        }
        return out
    }

    /** `01 09 21 00 i 00 00` + biquad + freq + Q8 + gain Q8 + type + 00 + bank (+ 00): [WalkPlay.bandWriteReport] with length 0x21. */
    fun bandPayload(w: BandWrite, bank: Int): ByteArray {
        require(bank in 0..255)
        val p = ByteArray(BAND_PAYLOAD_BYTES)
        byteArrayOf(WRITE.toByte(), CMD_EQ_UPDATE.toByte(), 0x21, 0, w.index.toByte(), 0, 0).copyInto(p)
        w.coefficients.copyInto(p, 7)
        val f = w.frequencyHz and 0xFFFF; val q = w.qQ8 and 0xFFFF; val g = w.gainQ8 and 0xFFFF
        intArrayOf(f and 255, f shr 8, q and 255, q shr 8, g and 255, g shr 8, w.nativeType, 0, bank, 0)
            .forEachIndexed { i, v -> p[27 + i] = v.toByte() }
        return p
    }
    /** `01 0A 04 00 00 FF FF` (EQ_COEFF, the manufacturer's enableAllPEQ): latches the bands just written. */
    fun commitPayload(): ByteArray = enableAllPeqPayload(0)

    /**
     * `01 0A 04 <index> 00 FF FF`: MDHUB's `enableAllPEQ` (hub.js hb@583877): `sendCommand(OP_WRITE, EQ_COEFF, 4, ...)` with the first data byte `og.INDEX`, a constant 0 in
     * the bundle (`og={INDEX:0}`, the same constant the band frames carry after their length byte), then 00 FF FF. So MDHUB's frame is [commitPayload]; any other [index] is a variant
     * of this tool's own, for the bench to try (`enable-peq --index N`), not a frame MDHUB sends. Built only for that command (see [requireSendable] `enableIndex`).
     */
    fun enableAllPeqPayload(index: Int): ByteArray {
        require(index in 0..255) { "EQ index $index out of range" }
        return byteArrayOf(WRITE.toByte(), CMD_EQ_COEFF.toByte(), 4, index.toByte(), 0, 0xFF.toByte(), 0xFF.toByte())
    }
    /** The frame of `enable-peq`: [enableAllPeqPayload] (50 ms) and, with [persist], `01 01 00` (200 ms). The active EQ index is not touched. */
    fun enableAllPeqSteps(index: Int, persist: Boolean): List<WriteStep> =
        listOf(WriteStep(enableAllPeqPayload(index), DELAY_OP_MS, "enable all PEQ (EQ_COEFF, index byte $index)")) +
            if (persist) listOf(WriteStep(savePayload(), DELAY_FLASH_MS, "save EQ to flash")) else emptyList()
    /** `01 23 02 lo hi`: PRE-GAIN, signed Q8.8 dB, clamped to the model's range. */
    fun preGainPayload(model: MoondropCatalog.Model, db: Double): ByteArray {
        require(db.isFinite() && db in model.preGainMinDb..model.preGainMaxDb) { "Pre-gain $db dB outside ${model.preGainMinDb}..${model.preGainMaxDb} dB" }
        val v = WalkPlay.toInt32(WalkPlay.jsRound(db * 256)) and 0xFFFF
        return byteArrayOf(WRITE.toByte(), CMD_PRE_GAIN.toByte(), 2, (v and 255).toByte(), (v shr 8).toByte())
    }
    /** `01 01 00` (EQ_SAVE_FLASH). */
    fun savePayload(): ByteArray = byteArrayOf(WRITE.toByte(), CMD_EQ_SAVE.toByte(), 0)
    /** `01 0F 01 i` (EQ_ACTIVE): switches the active EQ index; it takes effect after [DELAY_EQ_INDEX_MS]. */
    fun selectPayload(index: Int): ByteArray {
        require(index in 0..255)
        return byteArrayOf(WRITE.toByte(), CMD_EQ_ACTIVE.toByte(), 1, index.toByte())
    }

    /**
     * The writes of one send, frame for frame what MOONDROP Hub's "Write Cfg" sends (capture 24.10.2026, a flat EQ): `01 0F 01 07` (the index switch, sent without any
     * requirement that it takes: the owner's unit ignores it and stays on index 9, and the bands of the active bank take the write), every band tagged with the writable bank
     * (`07`), the commit, the pre-gain when [preGainDb] is given, the global gain register with [globalGainRawQ8] (the value read just before, so it is written back
     * unchanged; the Hub writes it too, and this unit ignores it), `01 01 00`. The pacing is the longer of the Hub's gaps and the model's delays. The offset save `01 04 00`
     * is not sent: the manufacturer's write does not use it. Whether it landed is decided by reading the bands back, not by the EQ index.
     */
    fun writeSteps(model: MoondropCatalog.Model, bands: List<BandWrite>, preGainDb: Double?, globalGainRawQ8: Int = 0): List<WriteStep> {
        require(bands.size == model.bandCount && bands.indices.all { bands[it].index == it }) { "A Moondrop write covers all ${model.bandCount} bands in order" }
        val steps = ArrayList<WriteStep>()
        steps += WriteStep(selectPayload(model.writableIndex), maxOf(DELAY_EQ_INDEX_MS, HUB_SELECT_MS), "select EQ index ${model.writableIndex} (as the Hub; the unit may ignore it)")
        bands.forEach { steps += WriteStep(bandPayload(it, model.writableIndex), maxOf(DELAY_BAND_MS, HUB_BAND_MS), "write band ${it.index}") }
        steps += WriteStep(commitPayload(), maxOf(DELAY_OP_MS, HUB_COMMIT_MS), "commit bands (EQ_COEFF)")
        if (preGainDb != null) steps += WriteStep(preGainPayload(model, preGainDb), maxOf(DELAY_OP_MS, HUB_GAIN_MS), "write pre-gain $preGainDb dB")
        steps += WriteStep(globalGainRawPayload(globalGainRawQ8), maxOf(DELAY_OP_MS, HUB_GAIN_MS), "write global gain back as read (raw Q8.8 $globalGainRawQ8)")
        steps += WriteStep(savePayload(), DELAY_FLASH_MS, "save EQ to flash")
        return steps
    }
    /** `01 03 02 lo hi` for a raw Q8.8 value as a read returned it ([Gain.rawQ8]): the global gain written back unchanged. */
    fun globalGainRawPayload(rawQ8: Int): ByteArray {
        val v = rawQ8 and 0xFFFF
        return byteArrayOf(WRITE.toByte(), CMD_GLOBAL_GAIN.toByte(), 2, (v and 255).toByte(), (v shr 8).toByte())
    }
    /** Select [index] as the active EQ: `01 0F 01 i`, 150 ms, then (when [persist]) `01 01 00`, 200 ms (FreeDSP-Studio set_preset). */
    fun selectSteps(index: Int, persist: Boolean): List<WriteStep> =
        listOf(WriteStep(selectPayload(index), DELAY_EQ_INDEX_MS, "select EQ index $index")) +
            if (persist) listOf(WriteStep(savePayload(), DELAY_FLASH_MS, "save EQ index to flash")) else emptyList()

    // ---- the two gains (Pre Gain 0x23, Global Gain 0x03) ----------------------------------------------------------------------------------------------------

    /** The gain registers of the manufacturer's tool: PRE-GAIN (-128..0 dB, `01 23 02 lo hi`) and GLOBAL GAIN (the DAC offset, -18..0 dB, `01 03 02 lo hi`), both signed Q8.8 on the 0.1 dB grid. */
    enum class GainRegister(val command: Int, val what: String) { PRE(CMD_PRE_GAIN, "pre-gain"), GLOBAL(CMD_GLOBAL_GAIN, "global gain") }

    /** The range of [register] on [model], in dB. */
    fun gainRange(model: MoondropCatalog.Model, register: GainRegister): ClosedFloatingPointRange<Double> = when (register) {
        GainRegister.PRE -> model.preGainMinDb..model.preGainMaxDb
        GainRegister.GLOBAL -> model.globalGainMinDb..model.globalGainMaxDb
    }
    /** [db] on the tool's 0.1 dB grid (the step both gains have), refused when it is not finite or outside the model's range. */
    fun gainOnGrid(model: MoondropCatalog.Model, register: GainRegister, db: Double): Double {
        val range = gainRange(model, register)
        require(db.isFinite() && db in range) { "${register.what.replaceFirstChar { it.uppercase() }} $db dB outside ${range.start}..${range.endInclusive} dB" }
        return WalkPlay.jsRound(db * 10) / 10.0
    }
    /** The signed Q8.8 register value of [db] (rounded to 0.1 dB first), as the read returns it ([Gain.rawQ8]). */
    fun gainRawQ8(model: MoondropCatalog.Model, register: GainRegister, db: Double): Int = WalkPlay.toInt32(WalkPlay.jsRound(gainOnGrid(model, register, db) * 256))
    /** `01 cmd 02 lo hi`: the gain write of the manufacturer's tool. */
    fun gainPayload(model: MoondropCatalog.Model, register: GainRegister, db: Double): ByteArray {
        val v = gainRawQ8(model, register, db) and 0xFFFF
        return byteArrayOf(WRITE.toByte(), register.command.toByte(), 2, (v and 255).toByte(), (v shr 8).toByte())
    }
    /**
     * One gain written: the frame (50 ms) and, with [persist], `01 01 00` (200 ms). The manufacturer's tool saves a gain with `01 04 00` only when its
     * persist flag is on, and no caller found in its bundle turns that on, so this never builds `01 04 00`: whether `01 01 00` keeps a gain over a power cycle is
     * for the bench to prove (a read after replugging the DAC). [persist] saves the active bank, so a session only asks for it on the writable index.
     */
    fun gainSteps(model: MoondropCatalog.Model, register: GainRegister, db: Double, persist: Boolean = false): List<WriteStep> {
        val grid = gainOnGrid(model, register, db)
        val steps = arrayListOf(WriteStep(gainPayload(model, register, db), DELAY_OP_MS, "write ${register.what} $grid dB (0x%02x)".format(register.command)))
        if (persist) steps += WriteStep(savePayload(), DELAY_FLASH_MS, "save EQ to flash (persistence of the gain unproven)")
        return steps
    }

    /**
     * The frames a session of this object may send, as an allow-list: reads `80 0F`, `80 09`, `80 03`, `80 23`, `80 0C`; writes `01 09` (a band), `01 0A`
     * (the commit, exact; with [enableIndex] also `01 0A 04 <enableIndex> 00 FF FF`, the `enable-peq` command's frame), `01 23` and `01 03` (a gain, length 2), `01 0F`
     * (the index, length 1), `01 01 00` (the EQ save, exact). Everything else is refused with its reason, the flash clear (`01 05`), the firmware entry (`01 FF`) and the offset
     * save (`01 04`) by name, in every mode.
     */
    fun requireSendable(payload: ByteArray, enableIndex: Int? = null) {
        require(payload.size >= 2) { "A Moondrop report is at least two bytes" }
        val op = u(payload[0]); val cmd = u(payload[1])
        if (op == READ) {
            require(cmd in setOf(CMD_EQ_ACTIVE, CMD_EQ_UPDATE, CMD_GLOBAL_GAIN, CMD_PRE_GAIN, CMD_VERSION)) { "Read 0x%02x is not one the app sends".format(cmd) }
            return
        }
        require(op == WRITE) { "Report starts with 0x%02x, not a Moondrop read or write".format(op) }
        when (cmd) {
            0x05 -> throw IllegalArgumentException("01 05 is the flash clear: never sent")
            0xFF -> throw IllegalArgumentException("01 FF is the firmware update entry: never sent")
            0x04 -> throw IllegalArgumentException("01 04 is the offset save: never sent")
            CMD_EQ_UPDATE -> require(payload.size == BAND_PAYLOAD_BYTES && u(payload[2]) == 0x21) { "An unknown band write" }
            CMD_EQ_COEFF -> require(payload.contentEquals(commitPayload()) || (enableIndex != null && payload.contentEquals(enableAllPeqPayload(enableIndex)))) { "An unknown commit" }
            CMD_PRE_GAIN, CMD_GLOBAL_GAIN -> require(payload.size == 5 && u(payload[2]) == 2) { "An unknown gain write" }
            CMD_EQ_ACTIVE -> require(payload.size == 4 && u(payload[2]) == 1) { "An unknown index write" }
            CMD_EQ_SAVE -> require(payload.contentEquals(savePayload())) { "An unknown save" }
            else -> throw IllegalArgumentException("Write 0x%02x is not one the app sends".format(cmd))
        }
    }

    /** The registers a write leaves in the bank the unit is on: what the read-back must show. [bank] is the active EQ index the rows were read on (a read row carries the bank of the
     * active index, not the `07` tag the write frames carry: the owner's unit takes a write tagged 07 into bank 9). */
    fun matches(expected: List<BandWrite>, bank: Int, actual: List<RawBand>): List<String> {
        val out = ArrayList<String>()
        val a = actual.associateBy { it.index }
        for (e in expected) {
            val r = a[e.index] ?: run { out += "band ${e.index} missing"; null } ?: continue
            if (r.nativeType != e.nativeType) out += "band ${e.index} type ${e.nativeType} -> ${r.nativeType}"
            if (r.frequencyHz != e.frequencyHz) out += "band ${e.index} freq ${e.frequencyHz} -> ${r.frequencyHz}"
            if (r.qQ8 != e.qQ8) out += "band ${e.index} Q ${e.qQ8} -> ${r.qQ8}"
            if (r.gainQ8 != e.gainQ8) out += "band ${e.index} gain ${e.gainQ8} -> ${r.gainQ8}"
            if (r.bank != bank) out += "band ${e.index} bank $bank -> ${r.bank}"
            if (r.coefficientBytes.map { it.toByte() } != e.coefficients.toList()) out += "band ${e.index} coefficients differ"
        }
        return out
    }

    private fun u(b: Byte) = b.toInt() and 255
    private fun u16(p: ByteArray, i: Int) = u(p[i]) or (u(p[i + 1]) shl 8)
    private fun s16(p: ByteArray, i: Int) = u16(p, i).toShort().toInt()
    private fun s32(p: ByteArray, i: Int) = u(p[i]) or (u(p[i + 1]) shl 8) or (u(p[i + 2]) shl 16) or (u(p[i + 3]) shl 24)
}
