package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.isPass
import io.github.chronosauros.contour.core.isShelf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Logical HID payloads only: report ID, descriptor size and padding belong to transport.
 * Source: devicePEQ@0617f382e76629792a5933e6933e4b396a756a93,
 * devicePEQ/fiioUsbHidHandler.js L321-535; compensation.js L418-442.
 * Replies contain opaque sequence/checksum bytes and sometimes stale trailing report data.
 * Neither is a proven acknowledgement, checksum algorithm, or persistence guarantee.
 */
enum class FiioEffect { READ_ONLY, SELECT_ACTIVE_USER_BANK, SELECT_BYPASS_SLOT, WRITE_ACTIVE_BANK, SAVE_USER_BANK, RENAME_USER_BANK, WRITE_SETTING, FACTORY_RESET }
data class FiioFrame(val reportId: Int, val bytes: List<Int>, val effect: FiioEffect) {
    fun payload(): ByteArray = bytes.map { it.toByte() }.toByteArray()
}
data class FiioRegisters(val index: Int, val frequencyHz: Int, val gainTenths: Int, val qHundredths: Int, val typeCode: Int)
data class FiioSnapshot(val productName: String, val activeSlot: Int, val count: Int, val preampTenths: Int, val bands: List<FiioRegisters>)
data class FiioExactEq(val bands: List<Band>, val preampDb: Double)
enum class FiioSaveVerification { POST_SAVE_READ_REQUIRED, RECONNECT_AND_READ_REQUIRED }
/** The selection barrier MUST complete before writes: select -> query slot -> complete backup.
 * No mutation on connect, automatic pull, local picker change, or plan compilation.
 * Caller must correlate report ID, command AND index; sequential queries are recommended.
 * Abort on timeout, duplicate/conflicting response, slot drift, or incomplete read.
 */
data class FiioWritePlan(
    val productName: String,
    val targetSlot: Int,
    val expected: FiioSnapshot,
    val selection: FiioFrame,
    val selectionVerification: FiioFrame,
    val backupQueries: List<FiioFrame>,
    val writes: List<FiioFrame>,
    val save: FiioFrame,
    val verificationQueries: List<FiioFrame>,
    val saveVerification: FiioSaveVerification,
    val delayAfterCountMs: Int = 100,
    val delayBeforeSaveMs: Int = 100,
    // KA15 stops answering for a while after a bank switch and after a flash save (200 ms was too short).
    val delayAfterSelectMs: Int = 300,
    val delayAfterSaveMs: Int = 800,
)
sealed interface FiioVerification {
    data object RegistersMatchPersistenceUnproven : FiioVerification
    data class Pending(val reason: String) : FiioVerification
    data class Mismatch(val reason: String) : FiioVerification
}

class FiioCodec(val config: FiioConfig) {
    companion object {
        const val BAND = 0x15
        const val SLOT = 0x16
        const val PREAMP = 0x17
        const val COUNT = 0x18
        const val NAME = 0x30
        const val NAME_LENGTH = 7
        private const val NAME_REPLY_LEN = 8
        private const val NAME_REPLY_DATA = 10
        /** A USER slot name the DAC is known to take: A-Z and 0-9 only (all the KA15 capture shows, all the FiiO web app
         * allows on the SnowSky DACs, which store it upper-case), at most [length] (KA15 7, SnowSky 8). */
        fun slotName(text: String, length: Int = NAME_LENGTH): String =
            java.text.Normalizer.normalize(text.replace('ł', 'l').replace('Ł', 'L'), java.text.Normalizer.Form.NFD)
                .uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(length)
        /** [text] can name a USER slot: every letter is Latin (accents are dropped by [slotName]). A name in another script
         * (Chinese, Cyrillic...) would lose its letters - "配置 4" would become "4" over the slot's own name - so it is not written. */
        fun nameFitsSlot(text: String): Boolean = text.none { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }
        /** SnowSky name reply ([FiioConfig.nameBySlotId]): LEN says 9, the frame carries the slot id plus a 9-byte field
         * (8 name bytes, then one stale byte), like the KA15's LEN 8 with IDX + 9 bytes. */
        private const val SLOT_NAME_REPLY_LEN = 9
        private fun u(b: Byte) = b.toInt() and 255
        private fun word(p: ByteArray, offset: Int) = (u(p[offset]) shl 8) or u(p[offset + 1])
        private fun signed(p: ByteArray, offset: Int) = word(p, offset).toShort().toInt()
        // JS Math.round tie direction, not Kotlin bankers rounding.
        private fun quantize(v: Double, scale: Int) = floor(v * scale + 0.5).toInt()
        /** Tenths of a dB (pregain, band gain) rounded DOWN: quieter, never louder; a value already on the 0.1 dB grid is unchanged. */
        private fun tenthsDown(v: Double) = floor(v * 10 + 1e-6).toInt()
    }

    init {
        require(config.reportId in 1..255 && config.maxFilters in 1..255)
        require(config.saveCommand == 0x19) // 0x21 is RESET_TO_FACTORY in FiiO's own command table
        require(config.minGainDb.isFinite() && config.maxGainDb.isFinite() && config.minGainDb <= config.maxGainDb)
        require(config.minQ.isFinite() && config.maxQ.isFinite() && config.minQ > 0 && config.minQ <= config.maxQ)
        require(config.minFrequencyHz >= 1 && config.maxFrequencyHz <= 65535 && config.minFrequencyHz <= config.maxFrequencyHz)
        // Any shape may have a type byte; which ones a device gets is decided in its FiioConfig, only with captured bytes
        // (BP 3, LP 4, HP 5, AP 6 beside PK 0, LS 1, HS 2: FiiO web app on the Tiny A and K13 R2R, 14.10.2026, FINDINGS.md).
        require(config.typeCodes.isNotEmpty() && config.typeCodes.values.toSet().size == config.typeCodes.size)
        require(config.typeCodes.values.all { it in 0..255 })
        require(config.userSlots.all { it in 0..255 && it in config.declaredWritableSlots && it !in config.stockSlots && it != config.bypassSlot })
        require(config.shelfQScale.isFinite() && config.shelfQScale > 0) { "Invalid shelf Q scale" }
        require(config.shelfQScale == 1.0 || !config.shelfAlphaCompensation) { "Shelf Q scale and shelf slope compensation are exclusive" }
        require(config.peakQScale.isFinite() && config.peakQScale > 0) { "Invalid peak Q scale" }
        require(config.peakQScale == 1.0 || !config.peakingGainCompensation) { "Peak Q scale and peak gain compensation are exclusive" }
        require(config.preampOffsetDb.isFinite() && abs(config.preampOffsetDb * 10 - offsetTenths) < 1e-9) { "Preamp offset must be whole 0.1 dB" }
    }
    /** The preamp in intended tenths as it is sent: rounded DOWN to 0.1 dB, or to a whole dB where the DAC keeps whole dB only ([FiioConfig.preampWholeDb]). */
    private fun preampTenths(v: Double): Int = if (config.preampWholeDb) floor(v + 1e-6).toInt() * 10 else tenthsDown(v)
    /** [FiioConfig.preampOffsetDb] in register tenths: register = intended tenths + offset. */
    private val offsetTenths: Int get() = Math.round(config.preampOffsetDb * 10).toInt()
    /** The intended (acoustic) preamp for a preamp register, see [FiioConfig.preampOffsetDb]. */
    fun intendedPreampDb(registerTenths: Int): Double = (registerTenths - offsetTenths) / 10.0

    private var sequence = 0
    private fun frame(cmd: Int, data: List<Int>, effect: FiioEffect, forceChecksum: Boolean = false): FiioFrame {
        require(data.all { it in 0..255 })
        val header = if (effect == FiioEffect.READ_ONLY) listOf(0xBB, 0x0B) else listOf(0xAA, 0x0A)
        if (!config.checksumFrames && !forceChecksum) return FiioFrame(config.reportId, header + listOf(0, 0, cmd, data.size) + data + listOf(0, 0xEE), effect)
        val body = header + listOf(0, sequence++ and 255, cmd, data.size) + data
        return FiioFrame(config.reportId, body + listOf(crc8Maxim(body), 0xEE), effect)
    }
    /** CRC-8/MAXIM (poly 0x31 reflected, init 0), as the official web app computes it. */
    private fun crc8Maxim(bytes: List<Int>): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor b
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc
    }
    /** A settings read (DAC Settings page): `bb 0b 00 SEQ cmd 00 CRC ee` with the sequence byte and CRC the FiiO web app sends, on every
     * config. Read-only by construction: the header is the get header and no data goes with it. Never a mutation. */
    fun querySetting(cmd: Int): FiioFrame {
        require(cmd in 0..255 && cmd != 0x21) { "Not a settings read: $cmd" }
        return frame(cmd, emptyList(), FiioEffect.READ_ONLY, forceChecksum = true)
    }
    /**
     * A DAC Settings write: `aa 0a 00 SEQ cmd LEN data CRC ee`, the sequence byte and CRC of the settings reads, one data byte (the option or the level)
     * or two for the balance (left, right attenuation). The only builder of a settings SET: [DacWrite.check] refuses first (a command that is not in this
     * model's list, USB AUDIO, the never-send list, a value out of range), so those frames cannot exist. Nothing sends it from here.
     */
    fun setSetting(setting: DacSetting, value: Int): FiioFrame {
        val cmd = DacWrite.check(config, setting, value)
        return frame(cmd, DacWrite.payload(setting, value), FiioEffect.WRITE_SETTING, forceChecksum = true)
    }
    /**
     * The factory reset, `aa 0a 00 SEQ 21 01 00 CRC ee`: the ONLY builder of command 0x21 (see [FiioReset]). It needs the [ticket] that
     * [FiioResetFlow.confirm] issues after a complete backup and the owner's consent, refuses every model [FiioReset] does not offer (Melody, Tiny A, KA15), and spends the
     * ticket, so one confirmation is one frame. Nothing sends it from here.
     */
    internal fun resetFrame(ticket: FiioResetTicket): FiioFrame {
        require(FiioReset.offered(config)) { "${config.productName} has no factory reset in Contour" }
        ticket.spend(config)
        return frame(FiioReset.COMMAND, FiioReset.DATA, FiioEffect.FACTORY_RESET, forceChecksum = true)
    }
    /** The logical reply to a [querySetting]: `bb 0b`, [cmd], at least [minData] data bytes (`LEN`), `ee` after the CRC. Stale bytes after it are dropped. */
    fun settingReply(payload: ByteArray, cmd: Int, minData: Int, crcOptional: Boolean = false): ByteArray {
        require(payload.size >= 7 + minData) { "Short setting reply" }
        require(u(payload[0]) == 0xBB && u(payload[1]) == 0x0B) { "Wrong reply header/direction" }
        require(u(payload[4]) == cmd) { "Wrong command" }
        val len = u(payload[5])
        require(len >= minData && payload.size >= len + 7) { "Wrong data length" }
        // The firmware read (0x0B) answers `bb 0b SEQ cmd 03 major minor patch ee`: no CRC byte before the terminator.
        if (crcOptional && u(payload[len + 6]) == 0xEE && (payload.size < len + 8 || u(payload[len + 7]) != 0xEE)) return payload.copyOf(len + 7)
        require(payload.size >= len + 8 && u(payload[len + 7]) == 0xEE) { "Missing reply terminator" }
        return payload.copyOf(len + 8)
    }
    fun querySlot() = frame(SLOT, emptyList(), FiioEffect.READ_ONLY)
    fun queryCount() = frame(COUNT, emptyList(), FiioEffect.READ_ONLY)
    fun queryPreamp() = frame(PREAMP, emptyList(), FiioEffect.READ_ONLY)
    fun queryBand(index: Int): FiioFrame {
        require(index in 0 until config.maxFilters) { "Filter index out of range" }
        return frame(BAND, listOf(index), FiioEffect.READ_ONLY)
    }
    /** Active bank only. No slot argument: upstream ignores it and cannot read inactive banks. */
    fun activeReadHeaderQueries() = listOf(querySlot(), queryCount(), queryPreamp())
    fun activeBandQueries(count: Int): List<FiioFrame> {
        validateCount(count)
        return List(count) { queryBand(it) }
    }
    /** Explicit full active-bank read recipe including an ending slot guard. */
    fun activeReadQueries(count: Int) = activeReadHeaderQueries() + activeBandQueries(count) + querySlot()

    private fun requireUserSlot(slot: Int) {
        require(config.codecBlockers.isEmpty()) { config.codecBlockers.joinToString("; ") }
        require(slot in config.userSlots) { "Not a proven writable user slot: $slot" }
    }
    /** Only on an explicit user action (HOLD TO SEND, a tap on a USER slot) or the background scan the app starts on connect
     * (SnowSky Melody, Tiny A, K13 R2R; never the KA15), NEVER on an ordinary read. */
    fun selectUserSlot(slot: Int): FiioFrame {
        requireUserSlot(slot)
        return frame(SLOT, listOf(slot), FiioEffect.SELECT_ACTIVE_USER_BANK)
    }
    /**
     * The same select frame (command [SLOT]) with the model's bypass / Close EQ number ([FiioConfig.bypassSlot]: K13 R2R, Melody and Tiny A 240, KA15 10). Only to put a DAC back on
     * the slot it was found on, which can be the bypass: no write path ([planOnExplicitSend], [writesAfterSelection], the name and save frames) ever takes the bypass, and nothing
     * in the app calls this. The factory presets are not selectable here.
     */
    fun selectBypassSlot(): FiioFrame {
        require(config.codecBlockers.isEmpty()) { config.codecBlockers.joinToString("; ") }
        val slot = requireNotNull(config.bypassSlot) { "${config.productName} has no bypass slot" }
        return frame(SLOT, listOf(slot), FiioEffect.SELECT_BYPASS_SLOT)
    }
    private fun nameIndex(slot: Int): Int {
        require(config.userSlotNames) { "${config.productName}: USER names are not proven" }
        requireUserSlot(slot)
        return if (config.nameBySlotId) slot else config.userSlots.sorted().indexOf(slot)
    }
    private val nameReplyLen get() = if (config.nameBySlotId) SLOT_NAME_REPLY_LEN else NAME_REPLY_LEN
    fun queryName(slot: Int) =frame(NAME, listOf(nameIndex(slot)), FiioEffect.READ_ONLY)
    /** Always the full name field, NUL padded ([FiioConfig.nameLength]: KA15 and K13 7 bytes, SnowSky 8): a shorter write leaves bytes of the field to the DAC. KA15: the old
     * name's tail. SnowSky: the DAC keeps a byte 0x44 at field index 5 that it never clears (phone runs 25.10.2026: TINYA written as 5 bytes read back TINYAD, MELA
     * as `MELA 00 44 00 00`, on slots that held ORIGIN and DUSK; names of 6 and more bytes read back exact), so a 5-letter name showed a sixth letter. */
    fun writeName(slot: Int, name: String): FiioFrame {
        require(name.isNotEmpty() && name == slotName(name, config.nameLength)) { "Unsupported USER name: $name" }
        val pad = List(config.nameLength - name.length) { 0 }
        return frame(NAME, listOf(nameIndex(slot)) + name.map { it.code } + pad, FiioEffect.RENAME_USER_BANK)
    }
    /** KA15: the reply says LEN 8 but carries IDX plus a 9-byte field (CRC-checked on the Pixel, 04.10.2026); only its
     * first 7 bytes are the name - after a save the last two held stale report bytes (`ab ee`).
     * SnowSky: LEN 9, the slot id and the 8-byte name field. */
    fun parseName(payload: ByteArray, slot: Int): String {
        reply(payload, NAME, NAME_REPLY_DATA, nameReplyLen)
        require(u(payload[6]) == nameIndex(slot)) { "Uncorrelated USER name index" }
        return (7 until 7 + config.nameLength).map { u(payload[it]) }.takeWhile { it != 0 }
            .filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
    }
    private fun validateCount(count: Int) { require(count in 0..config.maxFilters) { "Invalid active count: $count" } }
    private fun range(value: Double, min: Double, max: Double, field: String) {
        require(value.isFinite() && value in min..max) { "$field outside [$min, $max]: $value" }
    }

    /** Strict logical reply, excluding report ID and padding. Opaque bytes 2/3 and trailer are
     * preserved by the caller's capture but not interpreted (non-zero in genuine captures).
     */
    private fun reply(payload: ByteArray, cmd: Int, dataLength: Int, lenField: Int = dataLength) {
        require(payload.size == dataLength + 8) { "Wrong logical reply length" }
        require(u(payload[0]) == 0xBB && u(payload[1]) == 0x0B) { "Wrong reply header/direction" }
        require(u(payload[4]) == cmd && u(payload[5]) == lenField) { "Wrong command/data length" }
        require(u(payload.last()) == 0xEE) { "Missing reply terminator" }
    }
    /** Explicit transport adapter: descriptor-derived actualPayloadSize, not a family default.
     * Validate the logical header/length/terminator before discarding opaque report tail.
     * Captures demonstrate non-zero stale tails, so a zero-padding requirement would be wrong.
     */
    fun logicalReply(reportId: Int, payload: ByteArray, actualPayloadSize: Int): ByteArray {
        require(reportId == config.reportId && payload.size == actualPayloadSize && actualPayloadSize >= 8)
        val cmd = u(payload[4])
        val n = when (cmd) { SLOT, COUNT -> 1; PREAMP -> 2; BAND -> 8; NAME -> NAME_REPLY_DATA; config.saveCommand -> 1; else -> throw IllegalArgumentException("Unsupported reply command: $cmd") }
        require(payload.size >= n + 8) { "Short transport payload" }
        val logical = payload.copyOf(n + 8)
        reply(logical, cmd, n, if (cmd == NAME) nameReplyLen else n)
        return logical
    }
    fun parseSlot(payload: ByteArray): Int { reply(payload, SLOT, 1); return u(payload[6]) }
    /**
     * The one non-0x17 answer to a preamp query that [FiioConfig.bypassPreampAnswersAsCount] allows: a logical COUNT frame (command 0x18, one data byte) whose data byte is 0, while the DAC is on
     * its bypass slot. True = "no EQ active"; false for every other reply, so a config without the flag never accepts it.
     */
    fun isBypassNoEqReply(payload: ByteArray, activeSlot: Int): Boolean = config.bypassPreampAnswersAsCount && activeSlot == config.bypassSlot &&
        runCatching { reply(payload, COUNT, 1); u(payload[6]) == 0 }.getOrDefault(false)
    /**
     * A bypass read without band queries ([FiioConfig.bypassPreampAnswersAsCount], the DAC said "no EQ active"): the slot, the filter count the DAC reported, preamp register 0 and
     * inactive zero records, which is what a read of any DAC's Close EQ keeps (see [snapshotBody]); import, writes and verification stay strict on them.
     */
    fun bypassSnapshot(slotReply: ByteArray, countReply: ByteArray, closingSlotReply: ByteArray): FiioSnapshot {
        val slot = parseSlot(slotReply)
        require(slot == parseSlot(closingSlotReply)) { "Active bank changed during read" }
        require(config.bypassPreampAnswersAsCount && slot == config.bypassSlot) { "Not a bypass read of ${config.productName}" }
        val count = parseCount(countReply)
        return FiioSnapshot(config.productName, slot, count, 0, List(count) { FiioRegisters(it, 0, 0, 0, 0) })
    }
    fun parseCount(payload: ByteArray): Int {
        reply(payload, COUNT, 1)
        return u(payload[6]).also { validateCount(it) }
    }
    fun parsePreamp(payload: ByteArray): Int {
        reply(payload, PREAMP, 2)
        return signed(payload, 6).also { range(it / 10.0, config.minGainDb, config.maxGainDb, "Pregain") }
    }
    fun parseBand(payload: ByteArray, expectedIndex: Int, activeCount: Int): FiioRegisters {
        validateCount(activeCount)
        require(expectedIndex in 0 until activeCount)
        reply(payload, BAND, 8)
        require(u(payload[6]) == expectedIndex) { "Uncorrelated band index" }
        return FiioRegisters(expectedIndex, word(payload, 9), signed(payload, 7), word(payload, 11), u(payload[13])).also { validateRegisters(it, readback = true) }
    }
    /** A save response only echoes the slot: not evidence of flash persistence. */
    fun parseSaveSlot(payload: ByteArray, expectedSlot: Int): Int {
        requireUserSlot(expectedSlot)
        reply(payload, config.saveCommand, 1)
        return u(payload[6]).also { require(it == expectedSlot) { "Save slot mismatch" } }
    }
    /** [readback]: the registers come from the DAC. On a [FiioConfig.qReadbackSlack] config their Q may sit just outside [minQ, maxQ] by the
     * same drift [matches] accepts ([qSlack]): the DAC stores Q 0.10 as 0.09, 10.00 as 10.03 and 9.99 as 10.02 (KA15 USER2, Pixel, 21.10.2026),
     * and a strict check there locked every read of that slot. Written registers ([compileBand]) and configs without the slack stay strict. */
    private fun validateRegisters(r: FiioRegisters, readback: Boolean = false) {
        require(r.index in 0 until config.maxFilters)
        require(r.typeCode in config.typeCodes.values) { "Unsupported native filter type ${r.typeCode}; never coerce to PK" }
        range(r.frequencyHz.toDouble(), config.minFrequencyHz.toDouble(), config.maxFrequencyHz.toDouble(), "Frequency")
        range(r.gainTenths / 10.0, config.minGainDb, config.maxGainDb, "Gain")
        if (readback && config.qReadbackSlack && r.qHundredths in minQHundredths - qSlack(minQHundredths)..maxQHundredths + qSlack(maxQHundredths)) return
        range(r.qHundredths / 100.0, config.minQ, config.maxQ, "Native Q")
    }
    /** Allowed Q readback drift in hundredths for a register Q of [qHundredths] ([FiioConfig.qReadbackSlack] configs only): 1 + q/250. */
    private fun qSlack(qHundredths: Int) = 1 + qHundredths / 250
    /** The Q range as whole register hundredths, rounded inwards. */
    private val minQHundredths: Int get() = Math.ceil(config.minQ * 100 - 1e-9).toInt()
    private val maxQHundredths: Int get() = floor(config.maxQ * 100 + 1e-9).toInt()
    /** Registers equal, or on a [FiioConfig.qReadbackSlack] config differing only by the Q drift [qSlack] allows ([expected] is the side the slack is counted from). */
    private fun registersMatch(expected: FiioRegisters, actual: FiioRegisters): Boolean =
        if (!config.qReadbackSlack) expected == actual
        else expected.copy(qHundredths = 0) == actual.copy(qHundredths = 0) && abs(expected.qHundredths - actual.qHundredths) <= qSlack(expected.qHundredths)
    /** Complete active snapshot. Opening and closing slot queries must agree. No missing preamp
     * or count defaults, duplicate index overwrite, or inference of max capacity from active count.
     */
    fun snapshot(slotReply: ByteArray, countReply: ByteArray, preampReply: ByteArray,
                 bandReplies: List<ByteArray>, closingSlotReply: ByteArray): FiioSnapshot {
        val slot = parseSlot(slotReply)
        require(slot == parseSlot(closingSlotReply)) { "Active bank changed during read" }
        return snapshotBody(slot, countReply, preampReply, bandReplies)
    }
    /** Melody and Tiny A USER reads have no closing slot check: band/preamp traffic makes 0x16 stale.
     * A write caller must supply the slot queried immediately after SELECT or SAVE.
     * An ordinary read may instead carry 7..9 (USER bank whose number is unknown). */
    fun melodyUserSnapshot(slotReply: ByteArray, countReply: ByteArray, preampReply: ByteArray,
                           bandReplies: List<ByteArray>): FiioSnapshot {
        require(FiioMelodyExchange.appliesTo(config))
        val slot = parseSlot(slotReply)
        require(slot in config.userSlots || slot in 7..9)
        return snapshotBody(slot, countReply, preampReply, bandReplies)
    }
    private fun snapshotBody(slot: Int, countReply: ByteArray, preampReply: ByteArray,
                             bandReplies: List<ByteArray>): FiioSnapshot {
        val count = parseCount(countReply)
        require(bandReplies.size == count) { "Incomplete/extra filters" }
        val indices = bandReplies.map { require(it.size >= 7); u(it[6]) }
        require(indices.toSet() == (0 until count).toSet() && indices.distinct().size == count) { "Duplicate/missing/out-of-range band indices" }
        // Close EQ plays no filters, and its records are not editable filters: the Melody returns ten all-zero records,
        // the KA15 (Pixel 13.10.2026) ten records with 0 Hz. When they do not parse, bypass keeps inactive zero records
        // so the DAC still reads as Close EQ; import, writes and verification stay strict.
        val parsed = runCatching { bandReplies.map { parseBand(it, u(it[6]), count) } }
        val bands = (if (parsed.isFailure && slot == config.bypassSlot && count == config.maxFilters) bandReplies.map {
            reply(it, BAND, 8)
            FiioRegisters(u(it[6]), 0, 0, 0, 0)
        } else parsed.getOrThrow()).sortedBy { it.index }
        return FiioSnapshot(config.productName, slot, count, parsePreamp(preampReply), bands)
    }
    private fun validateSnapshot(s: FiioSnapshot) {
        require(s.productName == config.productName)
        require(s.activeSlot in 0..255)
        validateCount(s.count)
        require(s.bands.size == s.count && s.bands.map { it.index }.toSet() == (0 until s.count).toSet())
        s.bands.forEach { validateRegisters(it, readback = true) }
        range(s.preampTenths / 10.0, config.minGainDb, config.maxGainDb, "Pregain")
    }
    private fun amplitude(gain: Double) = 10.0.pow(abs(gain) / 40.0)
    private fun nativeQ(type: FilterType, q: Double, gain: Double): Double {
        if (type == FilterType.PEAK && config.peakingGainCompensation) return q * amplitude(gain)
        if (type.isShelf && config.shelfAlphaCompensation) {
            val a = amplitude(gain)
            val radicand = (a + 1 / a) * (1 / q - 1) + 2
            require(radicand.isFinite() && radicand > 0) { "Unrepresentable shelf slope" }
            return 1 / sqrt(radicand)
        }
        // Shelves and peaks have their measured register scale; pass filters: the Q as it is
        return if (type.isShelf) q * config.shelfQScale else if (type == FilterType.PEAK) q * config.peakQScale else q
    }
    private fun intendedQ(type: FilterType, q: Double, gain: Double): Double {
        if (type == FilterType.PEAK && config.peakingGainCompensation) return q / amplitude(gain)
        if (type.isShelf && config.shelfAlphaCompensation) {
            val a = amplitude(gain)
            val denominator = 1 + (1 / (q * q) - 2) / (a + 1 / a)
            require(denominator.isFinite() && denominator > 0) { "Unsupported stored shelf slope" }
            return 1 / denominator
        }
        return if (type.isShelf) q / config.shelfQScale else if (type == FilterType.PEAK) q / config.peakQScale else q
    }
    private fun compileBand(band: Band, index: Int): FiioRegisters {
        val code = config.typeCodes[band.type] ?: throw IllegalArgumentException("Unsupported filter type ${band.type}")
        range(band.freqHz, config.minFrequencyHz.toDouble(), config.maxFrequencyHz.toDouble(), "Frequency")
        // A pass filter has no gain: register 0 whatever the band stores, and its stored gain is not range-checked.
        if (!band.type.isPass) range(band.gainDb, config.minGainDb, config.maxGainDb, "Gain")
        require(band.q.isFinite() && band.q > 0) { "Invalid Q" }
        // Disabled filters retain shape/frequency/Q but write exactly zero gain. Validate all input.
        // Source splitUnsignedValue and fiioGainBytesFromValue use JS bitwise truncation;
        // Gains and pregain round DOWN here (tenthsDown), never toward zero or nearest; only Q uses Math.round. Compensation uses the realised native gain.
        val gain = if (band.enabled && !band.type.isPass) tenthsDown(band.gainDb) else 0 // was JS truncation toward zero: a cut got shallower
        val q = nativeQ(band.type, band.q, gain / 10.0)
        // Only an ULP-scale inverse-transform allowance, never clamp an unrepresentable Q.
        // This lets exact native boundary registers survive floating-point inverse/import.
        require(q.isFinite() && q >= config.minQ - 4 * Math.ulp(config.minQ) &&
            q <= config.maxQ + 4 * Math.ulp(config.maxQ)) {
            if (band.type.isShelf && config.shelfQScale != 1.0)
                "Shelf Q outside %.2f..%.2f: %.2f".format(java.util.Locale.ROOT, config.shelfQMin, config.shelfQMax, band.q)
            else if (band.type == FilterType.PEAK && config.peakQScale != 1.0)
                "Peak Q outside %.2f..%.2f: %.2f".format(java.util.Locale.ROOT, config.peakQMin, config.peakQMax, band.q)
            else "Compensated native Q out of range: $q"
        }
        return FiioRegisters(index, band.freqHz.toInt(), gain, quantize(q, 100), code).also { validateRegisters(it) }
    }
    private fun pair(v: Int) = listOf((v ushr 8) and 255, v and 255)
    private fun writeBand(r: FiioRegisters) = frame(BAND,
        listOf(r.index) + pair(r.gainTenths) + pair(r.frequencyHz) + pair(r.qHundredths) + listOf(r.typeCode), FiioEffect.WRITE_ACTIVE_BANK)

    /** All validation and compilation precede ANY returned mutation. Null preamp is not 0 or auto.
     * Writes exactly the explicit active count: lowering count disables the inactive tail; it does
     * not silently truncate the local profile. A later longer plan overwrites every new active index.
     * backupQueries are the destination read HEADER, not a complete backup: after COUNT use
     * activeBandQueries(actualCount) then closing querySlot, never assume max is active count.
     */
    fun planOnExplicitSend(profile: Profile, targetSlot: Int, explicitUserAction: Boolean): FiioWritePlan =
        planOnExplicitSend(profile.bands, profile.preampDb, targetSlot, explicitUserAction)

    fun planOnExplicitSend(bands: List<Band>, preampDb: Double?, targetSlot: Int, explicitUserAction: Boolean): FiioWritePlan {
        require(explicitUserAction) { "Mutation requires explicit user Send" }
        requireUserSlot(targetSlot)
        validateCount(bands.size)
        val preamp = requireNotNull(preampDb) { "Explicit pregain required; missing is not zero" }
        // Checked in the intended domain (register range minus the offset) so the message names what the user sets.
        range(preamp, config.preampMinDb, config.preampMaxDb, "Pregain")
        val registers = bands.mapIndexed { index, band -> compileBand(band, index) }
        val expected = FiioSnapshot(config.productName, targetSlot, bands.size, preampTenths(preamp) + offsetTenths, registers)
        val writes = listOf(frame(PREAMP, pair(expected.preampTenths), FiioEffect.WRITE_ACTIVE_BANK),
            frame(COUNT, listOf(expected.count), FiioEffect.WRITE_ACTIVE_BANK)) + registers.map { writeBand(it) }
        return FiioWritePlan(config.productName, targetSlot, expected, selectUserSlot(targetSlot), querySlot(),
            activeReadHeaderQueries(), writes, frame(config.saveCommand, listOf(targetSlot), FiioEffect.SAVE_USER_BANK),
            activeReadQueries(expected.count), if (config.disconnectOnSave) FiioSaveVerification.RECONNECT_AND_READ_REQUIRED else FiioSaveVerification.POST_SAVE_READ_REQUIRED,
            delayAfterSaveMs = config.afterSaveMs)
    }
    /** Integration barrier: hold parameter writes until both the explicit selection query and a
     * complete destination backup prove this user bank is active. Data acquisition stays in the
     * caller; this method performs no I/O and cannot attest freshness of caller-supplied replies.
     */
    fun writesAfterSelection(plan: FiioWritePlan, selectedSlotReply: ByteArray, destinationBackup: FiioSnapshot): List<FiioFrame> {
        require(plan.productName == config.productName)
        requireUserSlot(plan.targetSlot)
        require(parseSlot(selectedSlotReply) == plan.targetSlot) { "Selection not confirmed" }
        validateSnapshot(destinationBackup)
        require(destinationBackup.activeSlot == plan.targetSlot) { "Backup is not the selected destination" }
        return plan.writes
    }
    /** Devices that always keep [FiioConfig.maxFilters] filters get a neutral peaking tail (0 dB, 100 Hz, Q 1.00,
     * the padding the upstream web tool captured on the KA15). Zero gain makes the tail inaudible. */
    fun padToDeviceCount(bands: List<Band>): List<Band> {
        if (!config.fixedBandCount || bands.size >= config.maxFilters) return bands
        return bands + List(config.maxFilters - bands.size) { Band("pad-${bands.size + it}", FilterType.PEAK, 100.0, 0.0, 1.0, enabled = true) }
    }

    /** Raw native-register comparison; no float tolerance hiding loss or mismatched count/slot.
     * Only configs with [FiioConfig.qReadbackSlack] accept the device's small Q readback drift ([qSlack]), nothing else. */
    fun matches(expected: FiioSnapshot, actual: FiioSnapshot): Boolean {
        validateSnapshot(expected)
        validateSnapshot(actual)
        val e = expected.copy(bands = expected.bands.sortedBy { it.index })
        val a = actual.copy(bands = actual.bands.sortedBy { it.index })
        if (!config.qReadbackSlack) return e == a
        return e.copy(bands = emptyList()) == a.copy(bands = emptyList()) && e.bands.size == a.bands.size &&
            e.bands.zip(a.bands).all { (x, y) -> registersMatch(x, y) }
    }
    fun verification(plan: FiioWritePlan, postSave: FiioSnapshot?, reconnected: Boolean = false): FiioVerification {
        require(plan.productName == config.productName && plan.expected.activeSlot == plan.targetSlot)
        if (postSave == null) return FiioVerification.Pending("Complete post-save active read required")
        if (!matches(plan.expected, postSave)) return FiioVerification.Mismatch("Slot/count/pregain/native registers differ")
        if (plan.saveVerification == FiioSaveVerification.RECONNECT_AND_READ_REQUIRED && !reconnected)
            return FiioVerification.Pending("Disconnect-on-save requires reconnect and complete read")
        return FiioVerification.RegistersMatchPersistenceUnproven
    }
    /** Exact inverse import. Unknown type or unrepresentable shelf is rejected, never mapped to PK.
     * Recompilation must recover ALL registers before returning an editable local EQ. Shelf Q and pregain come back
     * in the intended domain ([FiioConfig.shelfQScale], [FiioConfig.peakQScale], [FiioConfig.preampOffsetDb]); registers stay native.
     * [FiioConfig.qReadbackSlack] configs: a Q read just past the edge (0.09, 10.03) comes back clamped into [minQ, maxQ] (0.10, 10.00), the band
     * at its edge, and "exact" then means the recompiled registers are within the same Q drift [matches] accepts.
     */
    fun importExact(snapshot: FiioSnapshot): FiioExactEq {
        validateSnapshot(snapshot)
        val bands = snapshot.bands.sortedBy { it.index }.map { r ->
            val type = config.typeCodes.entries.single { it.value == r.typeCode }.key
            // Contour keeps a pass filter at 0 dB, so a nonzero gain register cannot be loaded exactly: refused with its
            // band, type and gain (DacSnapshot.importExact turns the message into DacImport.Rejected).
            require(!type.isPass || r.gainTenths == 0) {
                "Band ${r.index + 1}: ${type.name.replace('_', ' ')} has gain ${"%+.1f".format(java.util.Locale.ROOT, r.gainTenths / 10.0)} dB on the DAC; Contour keeps pass filters at 0 dB"
            }
            val nativeQ = if (config.qReadbackSlack) r.qHundredths.coerceIn(minQHundredths, maxQHundredths) else r.qHundredths
            Band("fiio-${r.index}", type, r.frequencyHz.toDouble(), r.gainTenths / 10.0,
                intendedQ(type, nativeQ / 100.0, r.gainTenths / 10.0), enabled = true)
        }
        val registers = snapshot.bands.sortedBy { it.index }
        val compiled = bands.mapIndexed { i, b -> compileBand(b, i) }
        require(compiled.size == registers.size && compiled.zip(registers).all { (c, r) -> registersMatch(c, r) }) { "Import cannot reproduce exact native registers" }
        val preamp = intendedPreampDb(snapshot.preampTenths)
        // A [FiioConfig.preampWholeDb] DAC is sent whole dB only, but a 0.1 dB register it holds (KA15 set by another app, a stock preset) reads as it is: the next send rounds it down.
        require(config.preampWholeDb || preampTenths(preamp) + offsetTenths == snapshot.preampTenths) { "Import cannot reproduce exact native pregain" }
        return FiioExactEq(bands, preamp)
    }
}
