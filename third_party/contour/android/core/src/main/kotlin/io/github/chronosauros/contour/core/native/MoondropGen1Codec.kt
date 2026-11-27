package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.Dsp
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ShelfForm
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * The frames of a Moondrop gen-1 cable (DUSK-SP) and how its replies read. Pure JVM; the transport is the port's (control pipe: every request is SET_REPORT(Output, id 1) and then
 * GET_REPORT(Input, id 1), 62 bytes each way; see `research/protocol/PROTOCOL.md`, "Moondrop gen 1").
 *
 * A frame is 62 bytes with the report ID: `01 00 0d 00 CMD FLAG 00 23 2d b3`, then int32 little-endian dwords from byte 10, zero padding. [Frame.payload] is the 61 bytes after the ID
 * (what [NativePort] carries); every offset below is of the WIRE report, ID first, as the protocol notes count them ([at] and [dword] take such an offset into a payload).
 *
 *  - read the active preset: CMD 0x5a FLAG 1 [0x5a]; the reply has byte 4 = 0x5a, byte 5 bit 7 set and the index at dword 14;
 *  - read band n (1-9): CMD 0xdd FLAG 1 [n]; the reply has byte 4 = 0xdd, byte 5 bit 7 set, n at dword 14, freq (Hz, int32) at 18, Q x 256 at 22, type at 26 (0 PK, 1 low shelf, 2 high shelf)
 *    and the gain as a signed int24 in WHOLE dB at 30..32; nothing after byte 33, so the coefficients cannot be read back;
 *  - select preset idx: CMD 0x5a FLAG 0 [0x5a, idx], then CMD 0xdc FLAG 0 [0xff], then read the preset;
 *  - write the Custom bank: CMD 0x5a FLAG 0 [0x5a, 0]; for each band CMD 0xdc FLAG 0 [0, i, freq, round(Q x 256), type, gain24]; for each band and each of the five rates CMD 0xdc FLAG 0
 *    [code, i, 3, b0, b1, b2, -a1, -a2] (the biquad over a0, x 2^22); CMD 0xdc FLAG 0 [0xff]; then read it back. 56 requests.
 *
 * The coefficients carry the exact gain; the meta record holds the gain rounded to whole dB (round half away from zero, like Rust's `f64::round` in FreeDSP-Studio), Q x 256 and integer Hz, and it
 * is the label every tool (and Contour's read-back check) compares. There is no preamp register, and Contour offers none.
 */
object MoondropGen1Codec {
    const val REPORT_ID = 1
    /** The bytes after the report ID: 61. */
    const val PAYLOAD_BYTES = 61
    const val CMD_PRESET = 0x5A
    const val CMD_BAND_READ = 0xDD
    const val CMD_WRITE = 0xDC
    const val FLAG_READ = 1
    const val FLAG_WRITE = 0
    /** The commit code of CMD 0xdc (`[0xff]`): latches the bank the frames before it wrote. */
    const val COMMIT = 0xFF
    /** The coefficient sets of a band, by the code the frame carries and the sample rate the coefficients are designed for. */
    val RATES: List<Pair<Int, Double>> = listOf(4 to 44100.0, 5 to 48000.0, 6 to 96000.0, 7 to 192000.0, 8 to 384000.0)
    /** The `3` between the band number and the coefficients of a coefficient frame: five coefficients (b0 b1 b2 -a1 -a2). */
    const val COEFFICIENT_MARKER = 3
    private const val SCALE = 4_194_304.0 // 2^22
    /** The native type codes of the meta record. */
    const val TYPE_PEAK = 0
    const val TYPE_LOW_SHELF = 1
    const val TYPE_HIGH_SHELF = 2
    val TYPES: Map<Int, FilterType> = mapOf(TYPE_PEAK to FilterType.PEAK, TYPE_LOW_SHELF to FilterType.LOW_SHELF, TYPE_HIGH_SHELF to FilterType.HIGH_SHELF)

    /** Rust's `f64::round`: half away from zero (Kotlin's `roundToInt` and [io.github.chronosauros.contour.core.WalkPlay.jsRound] round halves up, so -2.5 would be -2). */
    fun roundAway(x: Double): Long {
        val t = truncate(x)
        return (if (abs(x - t) >= 0.5) t + sign(x) else t).toLong()
    }

    // ---- frames ------------------------------------------------------------------------------------------------------------------------------

    enum class Kind { READ_PRESET, READ_BAND, WRITE }

    /** One request: [payload] is the 61 bytes after the report ID; [what] is for logs; [delayAfterMs] is the pause before the next request; [band] is the 1-based band of a band read. */
    class Frame(val kind: Kind, val payload: ByteArray, val what: String, val delayAfterMs: Long = 0, val band: Int? = null) {
        /** The full report with its ID (62 bytes). */
        val report: ByteArray get() = byteArrayOf(REPORT_ID.toByte()) + payload
    }

    /** The payload of a frame: `00 0d 00 CMD FLAG 00 23 2d b3` and the dwords from wire byte 10 (payload byte 9). */
    fun payload(cmd: Int, flag: Int, vararg dwords: Int): ByteArray {
        require(dwords.size * 4 <= PAYLOAD_BYTES - 9) { "Too many dwords for one report" }
        val p = ByteArray(PAYLOAD_BYTES)
        byteArrayOf(0x00, 0x0D, 0x00, cmd.toByte(), flag.toByte(), 0x00, 0x23, 0x2D, 0xB3.toByte()).copyInto(p)
        dwords.forEachIndexed { i, d -> for (k in 0 until 4) p[9 + i * 4 + k] = (d ushr (8 * k)).toByte() }
        return p
    }

    /** Byte [wire] of the report (ID counted as byte 0) taken from a payload. */
    fun at(payload: ByteArray, wire: Int): Int = payload[wire - 1].toInt() and 255

    /** The int32 at wire offset [wire] (little endian). */
    fun dword(payload: ByteArray, wire: Int): Int = at(payload, wire) or (at(payload, wire + 1) shl 8) or (at(payload, wire + 2) shl 16) or (at(payload, wire + 3) shl 24)

    fun presetQuery(): Frame = Frame(Kind.READ_PRESET, payload(CMD_PRESET, FLAG_READ, CMD_PRESET), "read active preset")
    fun bandQuery(n: Int): Frame {
        require(n in 1..9) { "Band $n is not 1-9" }
        return Frame(Kind.READ_BAND, payload(CMD_BAND_READ, FLAG_READ, n), "read band $n", band = n)
    }

    /** The reads of a whole state: the active preset, then the bands 1 to [bands]. */
    fun readQueries(bands: Int): List<Frame> = listOf(presetQuery()) + List(bands) { bandQuery(it + 1) }

    /**
     * Select preset [index] without the read after it: `5a 00 [5a, idx]` ALONE, followed by [afterSelectMs]. No commit: the commit stores the Custom bank and restarts the cable onto preset 1, so a
     * select followed by it never survives; without it the cable switches at once, stays on the bus and keeps the choice (in RAM) until the next restart or plug-in (runs 2026-11-11-moondrop-dusk-04-mac).
     */
    fun selectSteps(index: Int, afterSelectMs: Long): List<Frame> {
        require(index in 0..255) { "Preset index $index out of range" }
        return listOf(Frame(Kind.WRITE, payload(CMD_PRESET, FLAG_WRITE, CMD_PRESET, index), "select preset $index", afterSelectMs))
    }

    // ---- bands -------------------------------------------------------------------------------------------------------------------------------

    /** What the meta record of a band holds: the whole-dB gain and the integers the label carries. */
    data class Meta(val freqHz: Int, val qRaw: Int, val type: Int, val gainDb: Int)

    /** One band as it is written: the intended filter, its meta record and the five coefficient sets ([RATES] order; b0 b1 b2 -a1 -a2, x 2^22). */
    class BandWrite(val index: Int, val band: Band, val meta: Meta, val coefficients: List<IntArray>) {
        override fun toString() = "band $index $meta"
    }

    /** The coefficients of [band] at [fs]: FreeDSP-Studio's `compute_biquad` (RBJ, shelf slope S = Q, over a0, x 2^22, rounded half away from zero), through [Dsp.biquad] in the SLOPE form: the bytes go to the cable, which plays them, so the form is the cable's and never a choice of the display. */
    fun coefficients(band: Band, fs: Double): IntArray {
        val q = Dsp.biquad(band, ShelfForm.SLOPE, fs)
        val raw = doubleArrayOf(q.b0, q.b1, q.b2, -q.a1, -q.a2)
        return IntArray(5) { i ->
            val v = raw[i] * SCALE
            require(v.isFinite() && abs(v) < Int.MAX_VALUE.toDouble()) { "Coefficient ${i + 1} does not fit 32 bits" }
            roundAway(v).toInt()
        }
    }

    fun meta(band: Band): Meta {
        val type = TYPES.entries.firstOrNull { it.value == band.type }?.key ?: throw IllegalArgumentException("${band.type.name} is not a gen-1 filter type")
        return Meta(roundAway(band.freqHz).toInt(), roundAway(band.q * 256.0).toInt(), type, roundAway(band.gainDb).toInt())
    }

    /** The band a position holds when Contour does not use it: the factory Custom bank (PEAK, 0 dB, Q 1.0 at the position's frequency). */
    fun neutralBand(model: MoondropGen1Catalog.Model, position: Int): Band =
        Band("neutral$position", FilterType.PEAK, model.factoryFrequenciesHz[position].toDouble(), 0.0, model.factoryQ)

    /** The bands as [write] sends them: nine, in order; a band that is missing or disabled is the neutral band of its position. [bands] must have passed [issues]. */
    fun plan(model: MoondropGen1Catalog.Model, bands: List<Band>): List<BandWrite> {
        require(bands.size <= model.bandCount) { "${bands.size} bands; ${model.name} has ${model.bandCount}" }
        return List(model.bandCount) { i ->
            val b = bands.getOrNull(i)?.takeIf { it.enabled } ?: neutralBand(model, i)
            BandWrite(i + 1, b, meta(b), RATES.map { (_, fs) -> coefficients(b, fs) })
        }
    }

    /** Why [bands] cannot be sent to [model] (empty = they can): the ranges of the record, and a shelf whose gain and Q have no real response (the RBJ slope form has a ceiling on Q). */
    fun issues(model: MoondropGen1Catalog.Model, bands: List<Band>): List<String> {
        if (bands.size > model.bandCount) return listOf("${bands.size} bands; ${model.name} accepts ${model.bandCount}; no truncation")
        val found = ArrayList<String>()
        bands.forEachIndexed { i, b ->
            if (!b.enabled) return@forEachIndexed
            val n = i + 1
            if (b.type !in model.types) { found += "Band $n: type unsupported"; return@forEachIndexed }
            if (!b.freqHz.isFinite() || b.freqHz !in model.minFrequencyHz..model.maxFrequencyHz) found += "Band $n: freq ${num(b.freqHz)} Hz outside ${num(model.minFrequencyHz)}-${num(model.maxFrequencyHz)} Hz"
            if (!b.gainDb.isFinite() || b.gainDb !in model.minGainDb..model.maxGainDb) found += "Band $n: gain ${num(b.gainDb)} dB outside ${num(model.minGainDb)}..${num(model.maxGainDb)} dB"
            if (!b.q.isFinite() || b.q !in model.minQ..model.maxQ) found += "Band $n: Q %.2f outside %.2f-%.2f".format(java.util.Locale.ROOT, b.q, model.minQ, model.maxQ)
        }
        if (found.isNotEmpty()) return found
        // the filters themselves: no real response (a shelf with a Q above its gain's ceiling), or coefficients that do not fit 32 bits
        bands.forEachIndexed { i, b ->
            if (!b.enabled) return@forEachIndexed
            for ((_, fs) in RATES) {
                val why = runCatching { coefficients(b, fs) }.exceptionOrNull()?.message ?: continue
                found += "Band ${i + 1}: ${if (b.type.isShelfType) "shelf gain/Q has no real response" else why}"
                break
            }
        }
        return found
    }

    private val FilterType.isShelfType get() = this == FilterType.LOW_SHELF || this == FilterType.HIGH_SHELF
    private fun num(x: Double): String = if (x.isFinite() && x == Math.rint(x) && abs(x) < 1e9) x.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, x)

    /**
     * The whole write: the select of the Custom bank, nine meta records, 45 coefficient sets, the commit. 56 requests, never faster than the record's pacing. The commit stores the bank to flash and RESTARTS the
     * cable (it leaves the bus ~650 ms after the commit's reply and is back 0.5-0.7 s later, on preset 1): the write ends there and is proven by what [MoondropGen1Session.verifyAfterReturn] reads once it is back.
     */
    fun writeSteps(model: MoondropGen1Catalog.Model, planned: List<BandWrite>): List<Frame> {
        require(planned.size == model.bandCount) { "${planned.size} planned bands; ${model.name} has ${model.bandCount}" }
        val gap = model.writeGapMs
        val steps = ArrayList<Frame>()
        steps += Frame(Kind.WRITE, payload(CMD_PRESET, FLAG_WRITE, CMD_PRESET, model.customIndex), "select the Custom EQ", gap)
        for (b in planned) steps += Frame(Kind.WRITE, payload(CMD_WRITE, FLAG_WRITE, 0, b.index, b.meta.freqHz, b.meta.qRaw, b.meta.type, gain24(b.meta.gainDb)), "band ${b.index} meta", gap)
        for (b in planned) RATES.forEachIndexed { r, (code, fs) ->
            val c = b.coefficients[r]
            steps += Frame(Kind.WRITE, payload(CMD_WRITE, FLAG_WRITE, code, b.index, COEFFICIENT_MARKER, c[0], c[1], c[2], c[3], c[4]), "band ${b.index} coefficients ${fs.toInt()} Hz", gap)
        }
        steps += Frame(Kind.WRITE, payload(CMD_WRITE, FLAG_WRITE, COMMIT), "commit", model.afterCommitMs)
        return steps
    }

    /** The gain of a meta record as the 24-bit two's complement the dword carries. */
    fun gain24(gainDb: Int): Int {
        require(gainDb in -0x800000..0x7FFFFF) { "Gain $gainDb does not fit 24 bits" }
        return if (gainDb < 0) 0x1000000 + gainDb else gainDb
    }

    // ---- replies -----------------------------------------------------------------------------------------------------------------------------

    /** The reply of a request is accepted when it is a whole report; that is all a write acknowledgement is checked for (whether the cable echoes the command is not known). */
    fun isWholeReply(payload: ByteArray): Boolean = payload.size == PAYLOAD_BYTES

    /** The reply to the active-preset read: byte 4 = 0x5a, byte 5 bit 7 set. */
    fun isPresetReply(payload: ByteArray): Boolean = isWholeReply(payload) && at(payload, 4) == CMD_PRESET && at(payload, 5) and 0x80 != 0

    /** The reply to the read of band [n]: byte 4 = 0xdd, byte 5 bit 7 set, and the band number at dword 14 is [n] (a stale reply of another band is not the answer). */
    fun isBandReply(payload: ByteArray, n: Int): Boolean = isWholeReply(payload) && at(payload, 4) == CMD_BAND_READ && at(payload, 5) and 0x80 != 0 && dword(payload, 14) == n

    fun parsePreset(payload: ByteArray): Int {
        require(isPresetReply(payload)) { "Not a Moondrop gen-1 preset reply" }
        return dword(payload, 14)
    }

    /** One band as the cable reports it: the meta record only ([RawBand.gainDb] whole dB; the coefficients are not readable). */
    data class RawBand(val index: Int, val freqHz: Int, val qRaw: Int, val nativeType: Int, val gainDb: Int) {
        val q: Double get() = qRaw / 256.0
        val type: FilterType? get() = TYPES[nativeType]
        val meta: Meta get() = Meta(freqHz, qRaw, nativeType, gainDb)
    }

    fun parseBand(payload: ByteArray, n: Int): RawBand {
        require(isBandReply(payload, n)) { "Not the reply to a read of band $n" }
        var g = at(payload, 30) or (at(payload, 31) shl 8) or (at(payload, 32) shl 16)
        if (g and 0x800000 != 0) g -= 0x1000000
        return RawBand(n, dword(payload, 18), dword(payload, 22), dword(payload, 26), g)
    }

    /** What one read of the cable holds: the active preset index and the nine meta records of the Custom bank. */
    class Snapshot(val model: MoondropGen1Catalog.Model, val activeIndex: Int, val bands: List<RawBand>) {
        /** The cable plays its Custom EQ; on a firmware preset the bands read are the Custom bank, not what plays. */
        val onCustom: Boolean get() = activeIndex == model.customIndex
    }

    /** The meta records of [planned] against what the cable reports: empty = every band's type, Hz, Q x 256 and whole-dB gain read back as written (the coefficients cannot be read). */
    fun mismatches(planned: List<BandWrite>, activeIndex: Int, bands: List<RawBand>, customIndex: Int): List<String> {
        val out = ArrayList<String>()
        if (activeIndex != customIndex) out += "the cable is on preset $activeIndex, not the Custom EQ ($customIndex)"
        val have = bands.associateBy { it.index }
        for (b in planned) {
            val r = have[b.index]
            if (r == null) { out += "band ${b.index} missing"; continue }
            if (r.meta != b.meta) out += "band ${b.index} ${describe(b.meta)} -> ${describe(r.meta)}"
        }
        return out
    }

    private fun describe(m: Meta) = "${TYPES[m.type]?.name ?: "type ${m.type}"} ${m.freqHz} Hz ${m.gainDb} dB Q ${"%.3f".format(java.util.Locale.ROOT, m.qRaw / 256.0)}"

    /** The requests of a send up to the commit, for its progress: the read before and the 56 requests of the write. What follows the restart (the RAM select and the read) is [plannedRequestsAfterReturn]. */
    fun plannedRequests(model: MoondropGen1Catalog.Model): Int = readQueries(model.bandCount).size + 1 + model.bandCount + model.bandCount * RATES.size + 1

    /** The RAM select of the Custom EQ and the read of the preset and the nine bands that prove a write once the cable is back. */
    fun plannedRequestsAfterReturn(model: MoondropGen1Catalog.Model): Int = 1 + readQueries(model.bandCount).size

    /** The frame that restarts the cable: the commit of a write (a `dc 00 [ff]`). */
    fun isRestartingCommit(payload: ByteArray): Boolean = at(payload, 4) == CMD_WRITE && at(payload, 5) == FLAG_WRITE && dword(payload, 10) == COMMIT

    /** Frames that must never leave the app: any CMD other than the select/read/write ones above, any band outside 1-9, a commit code other than 0xff. Applied to every payload before it goes out. */
    fun requireSendable(payload: ByteArray) {
        require(payload.size == PAYLOAD_BYTES && at(payload, 1) == 0 && at(payload, 2) == 0x0D && at(payload, 3) == 0 && at(payload, 6) == 0 && at(payload, 7) == 0x23 && at(payload, 8) == 0x2D && at(payload, 9) == 0xB3) { "Not a Moondrop gen-1 frame" }
        val cmd = at(payload, 4); val flag = at(payload, 5)
        when {
            cmd == CMD_PRESET && flag == FLAG_READ -> require(dword(payload, 10) == CMD_PRESET) { "Preset read with another argument" }
            cmd == CMD_PRESET && flag == FLAG_WRITE -> require(dword(payload, 10) == CMD_PRESET && dword(payload, 14) in 0..5) { "Preset select out of range" }
            cmd == CMD_BAND_READ && flag == FLAG_READ -> require(dword(payload, 10) in 1..9) { "Band read out of range" }
            cmd == CMD_WRITE && flag == FLAG_WRITE -> {
                val code = dword(payload, 10)
                require(code == COMMIT || (code == 0 && dword(payload, 14) in 1..9) || (RATES.any { it.first == code } && dword(payload, 14) in 1..9 && dword(payload, 18) == COEFFICIENT_MARKER)) { "Write code $code is not a band, a coefficient set or the commit" }
            }
            else -> throw IllegalArgumentException("Command 0x%02x flag %d is not sent to a gen-1 cable".format(cmd, flag))
        }
    }

}
