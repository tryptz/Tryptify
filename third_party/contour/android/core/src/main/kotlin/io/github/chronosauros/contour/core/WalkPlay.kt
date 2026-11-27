package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.devices.FlatBand
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WalkPlay HID PEQ protocol, SchemeNo11 (CrinEar Protocol Micro).
 *
 * A 1:1 port of `research/protocol/walkplay.py`, itself a port of devicePEQ's walkplayHidHandler.js
 * (0BSD, Jerome O'Flaherty, commit 0617f382) verified bit-exact against the device - except the SchemeNo11
 * frequency/Q compensation of that commit, which is left out: frequency and Q go on the wire exactly as the
 * user set them, as CrinEar's own tool (devicePEQ v0.20 on graph.hangout.audio) writes and reads them.
 * With the compensation a band set to 6900 Hz here read back as 7058 Hz there.
 * Every buffer here is a full 64-byte report with the report ID at [0], so `buf[n] == d[n - 1]`
 * in PROTOCOL.md's WebHID offsets. JS semantics are kept: `Math.round` = floor(x + 0.5),
 * `>> 0` truncation, int32 wrap.
 */
object WalkPlay {
    // Identity, band count, report id and design rate are the records' (devices/crinear-protocol-micro.json, the report id and design rate shared by
    // every WalkPlay record); the generator checks that the WalkPlay records agree on both.
    const val VENDOR_ID: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_VENDOR_ID
    const val PRODUCT_ID: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_PRODUCT_ID
    const val REPORT_ID: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_REPORT_ID
    const val REPORT_SIZE: Int = 64            // report ID + 63 payload bytes
    const val READ: Int = 0x80
    const val WRITE: Int = 0x01
    const val END: Int = 0x00
    const val CMD_GLOBAL_GAIN: Int = 0x03
    const val CMD_PEQ: Int = 0x09
    const val CMD_VERSION: Int = 0x0C
    const val BANDS: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_BANDS
    const val DESIGN_FS: Double = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_DESIGN_RATE_HZ     // the biquad design rate

    const val TYPE_LSQ: Int = 1
    const val TYPE_PK: Int = 2
    const val TYPE_HSQ: Int = 3
    const val TYPE_LP: Int = 4
    const val TYPE_HP: Int = 5

    // ---- JS number semantics ---------------------------------------------------------------------

    /** JS `Math.round`. */
    fun jsRound(x: Double): Double = floor(x + 0.5)

    /** JS ToInt32 (`x >> 0`, `x & 0xFF` operands) for |x| < 2^63: truncate, then wrap modulo 2^32. */
    fun toInt32(x: Double): Int = x.toLong().toInt()

    fun typeCode(type: FilterType): Int = when (type) {
        FilterType.LOW_SHELF -> TYPE_LSQ
        FilterType.PEAK -> TYPE_PK
        FilterType.HIGH_SHELF -> TYPE_HSQ
        FilterType.LOW_PASS -> TYPE_LP
        FilterType.HIGH_PASS -> TYPE_HP
        // WalkPlay has no band-pass or all-pass code: refuse by name, never fall back to a peak.
        FilterType.BAND_PASS, FilterType.ALL_PASS ->
            throw IllegalArgumentException("${type.name} is not supported by WalkPlay DACs")
    }

    /** devicePEQ convertToFilterType: unknown codes read as PK. */
    fun filterType(code: Int): FilterType = when (code) {
        TYPE_LSQ -> FilterType.LOW_SHELF
        TYPE_HSQ -> FilterType.HIGH_SHELF
        TYPE_LP -> FilterType.LOW_PASS
        TYPE_HP -> FilterType.HIGH_PASS
        else -> FilterType.PEAK
    }

    // ---- reports ---------------------------------------------------------------------------------

    /** 64-byte output report: report ID, then [payload], zero padded. */
    fun report(vararg payload: Int): ByteArray {
        require(payload.size <= REPORT_SIZE - 1)
        val buf = ByteArray(REPORT_SIZE)
        buf[0] = REPORT_ID.toByte()
        payload.forEachIndexed { i, v -> buf[i + 1] = v.toByte() }
        return buf
    }

    fun versionRequest(): ByteArray = report(READ, CMD_VERSION, END)
    /** Bulk PEQ read (getCurrentSlot): the reply is band 0's packet, the slot byte at buf[36]. */
    fun slotRequest(): ByteArray = report(READ, CMD_PEQ, END)
    fun bandRequest(index: Int): ByteArray = report(READ, CMD_PEQ, 0x00, 0x00, index, END)
    fun preampRequest(): ByteArray = report(READ, CMD_GLOBAL_GAIN, END)

    private fun u8(buf: ByteArray, i: Int): Int = buf[i].toInt() and 0xFF
    private fun u16(buf: ByteArray, i: Int): Int = u8(buf, i) or (u8(buf, i + 1) shl 8)

    /** True for an input report 0x4B answering [cmd] (devicePEQ matches on d[1]). */
    fun isReply(buf: ByteArray, cmd: Int): Boolean =
        buf.size >= 3 && u8(buf, 0) == REPORT_ID && u8(buf, 2) == cmd

    /** Band reply for [index]; the bulk slot read answers with band 0 too. */
    fun isBandReply(buf: ByteArray, index: Int): Boolean =
        isReply(buf, CMD_PEQ) && buf.size >= 37 && u8(buf, 5) == index

    /** VERSION reply: ASCII at d[3..5], e.g. "0.2". */
    fun parseVersion(buf: ByteArray): String {
        require(buf.size >= 7) { "version reply too short: ${buf.size}" }
        return String(buf.copyOfRange(4, 7), Charsets.US_ASCII).trimEnd('\u0000')
    }

    fun parseSlot(buf: ByteArray): Int {
        require(buf.size >= 37) { "slot reply too short: ${buf.size}" }
        return u8(buf, 36)
    }

    /** Global gain reply: int8 dB at d[4]. */
    fun parsePreamp(buf: ByteArray): Int {
        require(buf.size >= 6) { "preamp reply too short: ${buf.size}" }
        return buf[5].toInt()
    }

    /** Raw registers of one band, as the device stores them. [gain256] is signed. */
    data class Registers(val index: Int, val freq: Int, val q256: Int, val gain256: Int, val typeCode: Int)

    /** A decoded band reply: registers plus their values in Hz / dB / Q, as CrinEar's tool shows them. */
    data class DeviceBand(
        val registers: Registers,
        val slotByte: Int,
        val biquad: ByteArray,
        val enabled: Boolean,
        val type: FilterType,
        val freqHz: Double,
        val gainDb: Double,
        val q: Double,
    )

    /** parse_filter / parseFilterPacket. */
    fun parseBand(buf: ByteArray): DeviceBand {
        require(buf.size >= 37) { "band reply too short: ${buf.size}" }
        val index = u8(buf, 5)
        val freqRaw = u16(buf, 28)
        val qRaw = u16(buf, 30)
        var gainRaw = u16(buf, 32)
        if (gainRaw > 32767) gainRaw -= 65536
        val typeCode = u8(buf, 34)
        val type = filterType(typeCode)
        val gain = jsRound(gainRaw / 256.0 * 100) / 100
        val qStored = jsRound(qRaw / 256.0 * 100) / 100
        val freq = freqRaw.toDouble()
        val q = qStored
        val uninit = freqRaw == 0xFFFF && qRaw == 0xFFFF && gainRaw == -1
        val disabled = uninit || freqRaw == 0 || freqRaw == 0xFFFF || q == 0.0
        return DeviceBand(
            registers = Registers(index, freqRaw, qRaw, gainRaw, typeCode),
            slotByte = u8(buf, 36),
            biquad = buf.copyOfRange(8, 28),
            enabled = !disabled,
            type = type,
            freqHz = freq,
            gainDb = gain,
            q = q,
        )
    }

    // ---- writes ------------------------------------------------------------------------------------

    /**
     * One band as it goes on the wire: the biquad uses [freq] and [q] unrounded, the metadata fields get
     * `trunc(freq)`, `round(q * qScale * 256)`, `round(gain * 256)`. [q] is the intended Q: [qScale] is the factor between it and the Q register
     * (the TRN's shelves play the register divided by sqrt(2), `DeviceCapabilities.shelfQScale`), and everything that derives coefficients stays on [q].
     * [rateHz] is the rate the biquad is designed for (`DeviceCapabilities.coefficientRateHz`).
     */
    data class BandWrite(
        val index: Int, val freq: Double, val gainDb: Double, val q: Double, val typeCode: Int,
        val qScale: Double = 1.0, val rateHz: Double = DESIGN_FS,
        /** The biquad is sent wrapped to int32 when it overflows signed Q30 ([DeviceCapabilities.wrapQ30]) instead of being refused. */
        val wrapQ30: Boolean = false,
    ) {
        /** The Q register value this write stores. */
        internal val qRegister: Int get() = toInt32(jsRound(q * qScale * 256)) and 0xFFFF

        /** The registers the device will hold after this write (for the read-back comparison). */
        fun registers(): Registers {
            var g = toInt32(jsRound(gainDb * 256)) and 0xFFFF
            if (g > 32767) g -= 65536
            return Registers(index, toInt32(freq) and 0xFFFF, qRegister, g, typeCode)
        }
    }

    /** Factory flat band of [index] on the Micro: byte-identical to the device's own (the record's `slots.factoryFlat`: PK, 0 dB, Q 0.75, 31 / 62 / ... / 4000 Hz). */
    fun factoryFlat(index: Int): BandWrite = flatWrite(index, GeneratedDevices.CRINEAR_PROTOCOL_MICRO.factoryFlat[index])

    /** A flat band (peak at 0 dB) as a record gives it. */
    internal fun flatWrite(index: Int, flat: FlatBand): BandWrite = BandWrite(index, flat.freqHz.toDouble(), 0.0, flat.q, TYPE_PK)

    /** A user band -> its wire values, unchanged (no SchemeNo11 compensation, see the class comment); a pass filter has no gain, so 0 goes out. */
    fun bandWrite(index: Int, band: Band): BandWrite =
        BandWrite(index, band.freqHz, if (band.type.isPass) 0.0 else band.gainDb, band.q, typeCode(band.type))

    /** [bandWrite] for a DAC with measured conventions: the Q register scale of the band's type and the coefficient rate of [caps]. */
    fun bandWrite(index: Int, band: Band, caps: DeviceCapabilities): BandWrite =
        bandWrite(index, band).copy(qScale = caps.qScale(band.type), rateHz = caps.coefficientRateHz, wrapQ30 = caps.wrapQ30)

    /**
     * The decoded band of a read-back, in the intended domain: the register Q divided by the scale of its type. The inverse of [bandWrite]'s Q register
     * (the editor and every comparison work on intended values; the registers stay as read).
     */
    fun intendedQ(q256: Int, type: FilterType, caps: DeviceCapabilities): Double = q256 / 256.0 / caps.qScale(type)

    /** A band whose biquad does not fit signed Q30: refused, never wrapped into a different filter. */
    class Q30Overflow(message: String) : IllegalArgumentException(message)

    private val COEFFICIENT_NAMES = arrayOf("b0", "b1", "b2", "-a1", "-a2")

    /** The first of the five [scaled] values that [computeIir] could not store as an int32 after its rounding. */
    private fun q30Violation(scaled: DoubleArray): String? {
        scaled.forEachIndexed { i, v ->
            val k = if (i < 3) jsRound(v) else -jsRound(-v)
            // Compared as Double, never through toInt32; false for NaN and infinity too.
            if (!(k >= Int.MIN_VALUE.toDouble() && k <= Int.MAX_VALUE.toDouble())) {
                return "Coefficient ${COEFFICIENT_NAMES[i]}=$k not representable as signed Q30"
            }
        }
        return null
    }

    /** Null when the biquad for these values fits signed Q30 exactly as [computeIir] writes it; else why not. */
    fun q30Violation(freq: Double, gainDb: Double, q: Double, typeCode: Int, rateHz: Double = DESIGN_FS): String? =
        q30Violation(scaledCoefficients(freq, gainDb, q, typeCode, rateHz))

    /**
     * The largest shelf Q (two decimals, as a Q is written) for which the shelf biquad stays a real filter at every gain up to [maxAbsGainDb]. The shelf formula takes the
     * square root of `(A + 1/A) * (1/Q - 1) + 2`, which turns negative for a large Q and a large gain: the coefficients are then not numbers ("non-finite biquad coefficients").
     * The vendor's table says Q 0.1-10 for a shelf too, but its own maths (the code here is the vendor's computeIIRFilter) has no answer for the corner; the refusal is right and
     * the table is what overstates. Null when no Q is ruled out (gain 0).
     */
    fun shelfQCeiling(maxAbsGainDb: Double): Double? {
        val a = sqrt(10.0.pow(abs(maxAbsGainDb) / 20))
        val spread = a + 1 / a
        if (spread <= 2.0 + 1e-12) return null
        var q = floor(100.0 / (1 - 2 / spread)) / 100.0 // (spread)(1/q - 1) + 2 >= 0  <=>  1/q >= 1 - 2/spread
        while (spread * (1 / q - 1) + 2 < 0) q -= 0.01
        return q
    }

    /** [q30Violation] for the values the registers of [w] would hold, i.e. what a read-back decodes (the Q back in the intended domain, see [BandWrite.qScale]). */
    fun q30ViolationQuantized(w: BandWrite): String? {
        val r = w.registers()
        return q30Violation(r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0 / w.qScale, r.typeCode, w.rateHz)
    }

    /** computeIIRFilter before rounding: 2^30 x [b0, b1, b2, -a1, -a2] / a0, RBJ at [rateHz] (the vendor's tool designs for [DESIGN_FS]). */
    private fun scaledCoefficients(freq: Double, gainDb: Double, q: Double, typeCode: Int, rateHz: Double): DoubleArray {
        val a = sqrt(10.0.pow(gainDb / 20))
        val w0 = freq * 6.283185307179586 / rateHz
        val s = sin(w0)
        val c = cos(w0)
        val alpha = s / (2 * q)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (typeCode == TYPE_LSQ || typeCode == TYPE_HSQ) {
            val sa = (s / 2) * sqrt((a + 1 / a) * (1 / q - 1) + 2)
            val k = 2 * sqrt(a) * sa
            if (typeCode == TYPE_LSQ) {
                b0 = a * ((a + 1) - (a - 1) * c + k); b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - k); a0 = (a + 1) + (a - 1) * c + k
                a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - k
            } else {
                b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k
                a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k
            }
        } else if (typeCode == TYPE_LP || typeCode == TYPE_HP) {
            // Plain RBJ low-pass / high-pass (no gain term), the project's intent for these codes (DeviceProtocol).
            // UNVERIFIED on hardware: devicePEQ sends the PK coefficients for codes 4 and 5; bench test T07 decides.
            if (typeCode == TYPE_LP) {
                b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2
            } else {
                b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2
            }
            a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
        } else { // PK
            b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
        }
        val scale = 1073741824.0
        return doubleArrayOf(
            b0 / a0 * scale, b1 / a0 * scale, b2 / a0 * scale,
            -a1 / a0 * scale, -a2 / a0 * scale,
        )
    }

    /**
     * computeIIRFilter: 5 x int32 LE, Q30 - b0, b1, b2, -a1, -a2 normalised by a0, RBJ at [rateHz]. The vendor's tool designs for 96 kHz ([DESIGN_FS], the default); the Micro's
     * and the Echo-A's DSP runs the coefficients at 93 750 Hz, so their writes design for that (`peq.coefficientRateHz`, carried by [BandWrite.rateHz]).
     */
    fun computeIir(freq: Double, gainDb: Double, q: Double, typeCode: Int, rateHz: Double = DESIGN_FS, wrapQ30: Boolean = false): ByteArray {
        val values = scaledCoefficients(freq, gainDb, q, typeCode, rateHz)
        require(values.all { it.isFinite() }) { "non-finite biquad coefficients" }
        // [wrapQ30]: devicePEQ's quantizer rounds and its byte split (`value >> (8 * i)`) takes the low 32 bits, so an overflowing coefficient goes out wrapped; only a DAC that plays the registers asks for it
        if (!wrapQ30) q30Violation(values)?.let { throw Q30Overflow(it) }
        val out = ByteArray(20)
        values.forEachIndexed { i, v ->
            val n = toInt32(if (i < 3) jsRound(v) else -jsRound(-v))
            out[i * 4] = (n and 0xFF).toByte()
            out[i * 4 + 1] = ((n shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((n shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((n shr 24) and 0xFF).toByte()
        }
        return out
    }

    /** The length byte of a filter write on Micro and Max (devicePEQ). The TRN Black Pearl takes [TRN_BAND_LEN]. */
    const val BAND_LEN: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_BAND_LEN
    const val TRN_BAND_LEN: Int = GeneratedDevices.TRN_BLACK_PEARL_BAND_LEN

    /** Filter write `01 09 18 00 i 00 00` + biquad + freq + q + gain + type + 00 + slot + 00 ([lenByte] 0x21 for the TRN). */
    fun bandWriteReport(w: BandWrite, slot: Int, lenByte: Int = BAND_LEN): ByteArray {
        val biquad = computeIir(w.freq, w.gainDb, w.q, w.typeCode, w.rateHz, w.wrapQ30)
        if (!w.wrapQ30) q30ViolationQuantized(w)?.let { throw Q30Overflow(it) }
        // The frequency register is truncated here; FreeDSP-Studio rounds. For whole Hz (every band the editor
        // makes) they agree, so Contour's rule stays as it is for every DAC.
        val f = toInt32(w.freq) and 0xFFFF
        val q = w.qRegister
        val g = toInt32(jsRound(w.gainDb * 256)) and 0xFFFF
        val payload = IntArray(7 + 20 + 10)
        intArrayOf(WRITE, CMD_PEQ, lenByte, 0x00, w.index, 0x00, 0x00).copyInto(payload)
        biquad.forEachIndexed { i, b -> payload[7 + i] = b.toInt() and 0xFF }
        intArrayOf(f and 0xFF, f shr 8, q and 0xFF, q shr 8, g and 0xFF, g shr 8, w.typeCode, 0x00, slot, END)
            .copyInto(payload, 27)
        return report(*payload)
    }

    /** Global gain write `01 03 02 00 g`, g = round(dB) as int8. */
    fun preampWriteReport(db: Double): ByteArray = report(WRITE, CMD_GLOBAL_GAIN, 0x02, 0x00, toInt32(jsRound(db)) and 0xFF)

    /** devicePEQ commit after the filter writes - PERSISTS to flash. */
    val COMMIT_SEQUENCE: List<ByteArray> = listOf(
        report(WRITE, 0x05, END),
        report(WRITE, 0x17, END),
        report(WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF, END), // TEMP_WRITE
        report(WRITE, 0x01, 0x01, END),                         // FLASH_EQ enable + persist
    )
    private val COMMIT_DELAYS_MS = longArrayOf(20, 20, 50, 0)

    /** One report and the pause after it. */
    class Step(val report: ByteArray, val delayAfterMs: Long)

    /** Changed bands only, then TEMP_WRITE: RAM-only, no preamp, ramp or flash commit. */
    fun temporaryBandSequence(bands: List<BandWrite>, slot: Int): List<Step> =
        bands.map { Step(bandWriteReport(it, slot), 20) } +
            Step(report(WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF, END), 0)

    /**
     * `write_state(...)` in research/protocol/write_test.py: every band with 20 ms after it, 100 ms more,
     * the preamp and 50 ms, then (when [commit]) the commit sequence with devicePEQ's delays; 200 ms at the end.
     */
    fun writeSequence(bands: List<BandWrite>, preampDb: Double, slot: Int, commit: Boolean): List<Step> {
        val steps = ArrayList<Step>()
        bands.forEachIndexed { i, b -> steps += Step(bandWriteReport(b, slot), if (i == bands.lastIndex) 120 else 20) }
        if (!commit) {
            steps += Step(preampWriteReport(preampDb), 50 + 200)
            return steps
        }
        steps += Step(preampWriteReport(preampDb), 50)
        COMMIT_SEQUENCE.forEachIndexed { i, r ->
            steps += Step(r, COMMIT_DELAYS_MS[i] + if (i == COMMIT_SEQUENCE.lastIndex) 200 else 0)
        }
        return steps
    }

    /**
     * The TRN Black Pearl's write: `write_bank` of FreeDSP-Studio (MIT, (c) 2026 EffectiveEquivalent, dsp2.rs, commit 1b42f63),
     * proven on the owner's unit from the PC on 19.10.2026. Every band with length byte 0x21 and 25 ms after it, the
     * coefficient commit (50 ms), the preamp (50 ms), `01 01 00` (EQ flash, 200 ms), `01 04 00` (offset and preamp flash, 200 ms).
     * Not devicePEQ's commit: no `01 05`, `01 17` and no `01 01 01`. Only the TRN gets this sequence.
     */
    fun trnWriteSequence(bands: List<BandWrite>, preampDb: Double, slot: Int): List<Step> =
        bands.map { Step(bandWriteReport(it, slot, TRN_BAND_LEN), 25) } + listOf(
            Step(report(WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF), 50),
            Step(preampWriteReport(preampDb), 50),
            Step(report(WRITE, 0x01, END), 200),
            Step(report(WRITE, 0x04, END), 200),
        )

    /** The RAM apply `01 0A 04 00 00 FF FF` (devicePEQ's TEMP_WRITE), the only frame after the band writes that the EPZ TP65 gets. */
    val TP65_APPLY: ByteArray get() = report(WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF, END)

    /**
     * The EPZ TP65's write (tester builds, https://github.com/Chronosauros/contour/issues/6): only the two frame shapes its owner validated on the unit (Windows, firmware 0.4,
     * issue attachment of 25.11.2026) - band writes `01 09 18 00 i 00 00` and the RAM apply [TP65_APPLY]. A band whose registers equal [current] (the full read right before the
     * send) is not sent, so an untouched band keeps its own bytes and a one-band edit is one band frame, as on the unit. 20 ms after each band (120 ms after the last), the
     * Space II's pacing; 250 ms after the apply before the read-back. No preamp write, no `01 05`, `01 17` or flash `01 01 01`: none of them was tried on a TP65.
     * [WalkPlayGuard][io.github.chronosauros.contour.core.native.WalkPlayGuard] refuses every other frame for the TP65 whoever builds it.
     */
    fun tp65WriteSequence(bands: List<BandWrite>, slot: Int, current: List<Registers>?): List<Step> {
        val changed = bands.filter { b -> current?.getOrNull(b.index) != b.registers() }
        return changed.mapIndexed { i, b -> Step(bandWriteReport(b, slot), if (i == changed.lastIndex) 120 else 20) } + Step(TP65_APPLY, 250)
    }
}
