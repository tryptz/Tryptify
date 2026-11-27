package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.WalkPlay

/**
 * Factory reset of the WalkPlay DACs (Protocol Micro, Moondrop Echo-A, TRN Black Pearl): the "RESET" button of the WalkPlay web tool, decoded
 * (`s2.resetDevice`, walkplay-plan.md item 4, rank KOD). It is NOT hardware-seen. The sequence, report id 0x4B, in this order and never in part:
 *
 *  1. `01 03 02 00 00` - the preamp ("Volume") to 0 dB;
 *  2. eight band frames, i = 0..7, 36 bytes each: `01 09 18 00 i 00 00` + biquad (20 B) + freq + Q + gain + `02` + `00` + `01`; the vendor's "PURE" values:
 *     50 / 200 / 500 / 1000 / 2500 / 5000 / 10000 / 15000 Hz, Q 0.75 (register `C0 00`), 0 dB, PK; the length byte is 0x18 and the last byte before the end is 1;
 *  3. `01 0A 04 00 00 FF FF` - the vendor's "refereshToMemery" (the TEMP_WRITE of devicePEQ);
 *  4. `01 01 00` - "refereshToFlash";
 *  5. `01 05 01`, `01 05 02`, `01 05 03`, `01 05 04` - meaning unknown;
 *  6. `01 17 00` - meaning unknown (devicePEQ uses it as a commit step).
 *
 * What the DAC erases beyond the EQ is unknown (steps 5 and 6 are not understood), so a reset is never a plain write: [WalkPlayResetFlow] takes a complete
 * [WalkPlayResetBackup] (all bands, the preamp, every readable setting) and the owner's consent first, and only its [WalkPlayResetTicket] lets [sequence]
 * build the frames (one ticket, one sequence). [WalkPlayGuard] refuses `01 05 01..04` in every other context, and `01 17 00` and `01 05 00` on the TRN outside it,
 * and `05 01 02 00 FF` and `02 00 00 01 FF` (the reset candidates of other classes) in all of them.
 *
 * The TRN has 10 bands and the vendor's reset writes bands 0-7 only, with the length byte 0x18, while Contour's own TRN write sends 0x21 for 10 bands ([WalkPlay.TRN_BAND_LEN]).
 * Contour's normal TRN write is NOT changed. After a TRN reset the bench MUST read bands 8 and 9: the reset leaves them as they were (or the unit erases them: unknown),
 * and the backup holds them, so [WalkPlaySession.restoreAfterReset] writes the whole backup back through the ordinary TRN sequence.
 *
 * [VERIFIED] is the flag of the app, like [FiioReset.VERIFIED]: empty until a reset has been run on the unit from the PC bench and the restore brought everything back.
 * The bench (`reset-plan`) and the tests build the sequence regardless. The app reaches it from the DAC Settings page's FACTORY RESET row only through [enabled] ([Proven]):
 * a release build has no FACTORY RESET row (its settings page exists for the TRN only, where the DAC filter row is `writeVerified`; the Micro and the Echo-A get none), a review build runs it.
 */
object WalkPlayReset {
    /** The profile ids that have a reset in Contour. The Protocol Max is not studied. */
    val OFFERED: Set<String> = setOf("micro", "echo-a", "trn")
    /** The profile ids whose reset passed on hardware (backup, reset, re-read, restore). Enabling one later is this one line. */
    val VERIFIED: Set<String> = emptySet()

    fun offered(profile: WalkPlayProfile): Boolean = profile.id in OFFERED
    /** The app may run it: [offered], and verified on hardware ([VERIFIED]) or a review build ([Proven.on]). */
    fun enabled(profile: WalkPlayProfile): Boolean = offered(profile) && Proven.on(proven(profile))
    /** The reset of this DAC passed on hardware ([VERIFIED]); an open reset that is not proven keeps its "not verified" wording. */
    fun proven(profile: WalkPlayProfile): Boolean = profile.id in VERIFIED

    /** The vendor's "PURE" bands: the frequencies (Hz), the Q, the PEAK type, the tag byte the frames carry and the length byte. */
    val PURE_HZ: List<Int> = listOf(50, 200, 500, 1000, 2500, 5000, 10000, 15000)
    const val PURE_Q = 0.75
    const val PURE_TAG = 1
    const val PURE_BAND_LEN = WalkPlay.BAND_LEN

    /** Contour's pacing of the sequence (the vendor's code has none): bands as the Micro's write, then a pause after each of the unknown steps. */
    private const val BAND_GAP_MS = 20L
    private const val STEP_GAP_MS = 50L
    private const val FLASH_GAP_MS = 200L

    /** The DAC may restart after the sequence: how long a session waits before it asks anything. */
    const val SETTLE_MS = 3000L

    val ERASE_NOTE: String get() = "A factory reset puts the EQ back to the manufacturer's flat bands and 0 dB, and may erase more" +
        (if (Proven.channel == BuildChannel.PERF) " (two of its steps are not understood). " else ". ") +
        "Contour saves a copy of all bands, the preamp and every setting it can read first and offers to write them back afterwards."

    /** The eight "PURE" band frames the vendor sends, as a Contour band write sends them (without the end byte, which is 0 anyway). */
    internal fun pureBands(): List<ByteArray> = PURE_HZ.mapIndexed { i, hz ->
        WalkPlay.bandWriteReport(WalkPlay.BandWrite(i, hz.toDouble(), 0.0, PURE_Q, WalkPlay.TYPE_PK), PURE_TAG, PURE_BAND_LEN)
    }

    /**
     * The whole reset sequence for [profile], built from [ticket] only (it is spent here). Refuses a profile that has no reset and a ticket of another DAC.
     * The frames are the plan's six steps in its order; the pauses are Contour's.
     */
    internal fun sequence(profile: WalkPlayProfile, ticket: WalkPlayResetTicket): List<WalkPlay.Step> {
        require(offered(profile)) { "${profile.name} has no factory reset in Contour" }
        ticket.spend(profile)
        val steps = ArrayList<WalkPlay.Step>()
        steps += WalkPlay.Step(WalkPlay.preampWriteReport(0.0), STEP_GAP_MS)
        pureBands().forEachIndexed { i, r -> steps += WalkPlay.Step(r, if (i == PURE_HZ.lastIndex) 120 else BAND_GAP_MS) }
        steps += WalkPlay.Step(WalkPlay.report(WalkPlay.WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF), STEP_GAP_MS)
        steps += WalkPlay.Step(WalkPlay.report(WalkPlay.WRITE, 0x01, WalkPlay.END), FLASH_GAP_MS)
        for (n in 1..4) steps += WalkPlay.Step(WalkPlay.report(WalkPlay.WRITE, 0x05, n), BAND_GAP_MS)
        steps += WalkPlay.Step(WalkPlay.report(WalkPlay.WRITE, 0x17, WalkPlay.END), FLASH_GAP_MS)
        return steps
    }

    /**
     * What changed between [before] (the backup) and [after] (the read after the reset), one line per band, the preamp, the slot byte and each setting.
     * Empty = nothing differs (the reset did not run, or erased nothing). [labelOf] names a setting id, [valueOf] one of its values.
     */
    fun diff(before: WalkPlayResetBackup, after: WalkPlayResetBackup, labelOf: (String) -> String = { it }, valueOf: (String, Int) -> String = { _, v -> v.toString() }): List<String> {
        val out = ArrayList<String>()
        val a = after.bands.associateBy { it.index }
        for (b in before.bands.sortedBy { it.index }) {
            val r = a[b.index]
            if (r == null) { out += "band ${b.index + 1}: not read after the reset"; continue }
            if (r != b) out += if (Proven.channel == BuildChannel.PERF) "band ${b.index + 1}: ${bandText(b)} -> ${bandText(r)}" else "band ${b.index + 1}: changed"
        }
        if (before.preampDb != after.preampDb) out += "preamp: ${before.preampDb} dB -> ${after.preampDb} dB"
        if (before.slot != after.slot) out += "slot byte: ${before.slot} -> ${after.slot}"
        for (id in (before.settings.keys + after.settings.keys).toSortedSet()) {
            val p = before.settings[id]
            val q = after.settings[id]
            if (p != q) out += "${labelOf(id)}: ${p?.let { valueOf(id, it) } ?: "no reply"} -> ${q?.let { valueOf(id, it) } ?: "no reply"}"
        }
        return out
    }

    private fun bandText(r: WalkPlay.Registers) = "type ${r.typeCode} ${r.freq} Hz Q ${r.q256}/256 gain ${r.gain256}/256"
}

/**
 * Everything Contour reads from a WalkPlay DAC before a factory reset: the registers of every band, the preamp, the slot byte and every readable setting
 * (by [DacSetting.id]); [unreadSettings] are the ids that did not answer (they are not restored). Plain text on disk ([toText] / [parse]), so a reset that
 * was interrupted can still be undone.
 */
data class WalkPlayResetBackup(
    val profileId: String,
    val takenAtMs: Long,
    val firmware: String,
    val slot: Int,
    val preampDb: Int,
    val bands: List<WalkPlay.Registers>,
    val settings: Map<String, Int>,
    val unreadSettings: List<String> = emptyList(),
) {
    /**
     * The bands as writes: what the restore sends (Contour recomputes the biquad bytes from the registers, as in every write, for [rateHz]: the DSP rate of the DAC,
     * `DeviceCapabilities.coefficientRateHz`). The Q register goes back as it was read, so no Q scale applies here.
     */
    fun writes(rateHz: Double = WalkPlay.DESIGN_FS): List<WalkPlay.BandWrite> = bands.sortedBy { it.index }.map { r ->
        WalkPlay.BandWrite(r.index, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0, r.typeCode, rateHz = rateHz)
    }

    /**
     * Why a reset must not start with this backup (empty = it may): another DAC, a missing band, a preamp the protocol cannot write back, or a band that
     * Contour cannot write back register for register (a type outside 1..5, a biquad that does not fit signed Q30, an erased slot).
     */
    fun problems(profile: WalkPlayProfile): List<String> {
        if (profileId != profile.id) return listOf("The backup is of $profileId, not ${profile.id}")
        val out = ArrayList<String>()
        if (bands.size != profile.bands || bands.map { it.index }.sorted() != (0 until profile.bands).toList()) out += "Expected all ${profile.bands} bands, the backup holds ${bands.size}"
        if (preampDb !in Byte.MIN_VALUE..Byte.MAX_VALUE) out += "The preamp ($preampDb dB) does not fit one byte"
        else if (preampDb > profile.preampMaxDb) out += "The DAC holds a preamp of $preampDb dB, above the ${profile.preampMaxDb} dB Contour writes: it could not be written back"
        val len = if (profile.protocol == DeviceProtocol.TRN) WalkPlay.TRN_BAND_LEN else WalkPlay.BAND_LEN
        for (w in runCatching { writes(profile.caps.coefficientRateHz) }.getOrDefault(emptyList())) {
            val n = w.index + 1
            if (w.typeCode !in WalkPlay.TYPE_LSQ..WalkPlay.TYPE_HP) { out += "Band $n: type ${w.typeCode} cannot be written back"; continue }
            runCatching { WalkPlay.bandWriteReport(w, slot, len) }.exceptionOrNull()?.let { out += "Band $n cannot be written back exactly: ${it.message}" }
        }
        return out
    }

    fun toText(): String = buildString {
        appendLine(HEADER)
        appendLine("profile $profileId")
        appendLine("taken $takenAtMs")
        appendLine("firmware ${firmware.ifEmpty { "-" }}")
        appendLine("slot $slot")
        appendLine("preamp $preampDb")
        for (b in bands.sortedBy { it.index }) appendLine("band ${b.index} ${b.freq} ${b.q256} ${b.gain256} ${b.typeCode}")
        for ((id, v) in settings.toSortedMap()) appendLine("setting $id $v")
        for (id in unreadSettings.sorted()) appendLine("nosetting $id")
    }

    companion object {
        const val HEADER = "contour-walkplay-reset-backup 1"

        /** The inverse of [toText]; anything that does not read back exactly is refused with the line. */
        fun parse(text: String): WalkPlayResetBackup {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(lines.firstOrNull() == HEADER) { "Not a Contour WalkPlay reset backup" }
            var profile: String? = null
            var taken: Long? = null
            var firmware: String? = null
            var slot: Int? = null
            var preamp: Int? = null
            val bands = ArrayList<WalkPlay.Registers>()
            val settings = HashMap<String, Int>()
            val unread = ArrayList<String>()
            fun num(s: String, line: String) = s.toIntOrNull() ?: throw IllegalArgumentException("Bad number in \"$line\"")
            for (line in lines.drop(1)) {
                val p = line.split(' ')
                when (p[0]) {
                    "profile" -> profile = p.getOrNull(1) ?: throw IllegalArgumentException("Bad line \"$line\"")
                    "taken" -> taken = p.getOrNull(1)?.toLongOrNull() ?: throw IllegalArgumentException("Bad line \"$line\"")
                    "firmware" -> firmware = (p.getOrNull(1) ?: throw IllegalArgumentException("Bad line \"$line\"")).let { if (it == "-") "" else it }
                    "slot" -> slot = num(p.getOrNull(1) ?: "", line)
                    "preamp" -> preamp = num(p.getOrNull(1) ?: "", line)
                    "band" -> {
                        require(p.size == 6) { "Bad line \"$line\"" }
                        bands += WalkPlay.Registers(num(p[1], line), num(p[2], line), num(p[3], line), num(p[4], line), num(p[5], line))
                    }
                    "setting" -> {
                        require(p.size == 3) { "Bad line \"$line\"" }
                        require(settings.put(p[1], num(p[2], line)) == null) { "Setting ${p[1]} twice" }
                    }
                    "nosetting" -> {
                        require(p.size == 2) { "Bad line \"$line\"" }
                        unread += p[1]
                    }
                    else -> throw IllegalArgumentException("Unknown line \"$line\"")
                }
            }
            require(profile != null && taken != null && firmware != null && slot != null && preamp != null) { "The backup lacks its profile, time, firmware, slot or preamp" }
            return WalkPlayResetBackup(profile, taken, firmware, slot, preamp, bands.sortedBy { it.index }, settings, unread)
        }
    }
}

/**
 * The one right to build the reset sequence, issued by [WalkPlayResetFlow.confirm] and spent by [WalkPlayReset.sequence] (one sequence per ticket). Its
 * constructor is module-internal: no read, write or probe path of the session takes one.
 */
class WalkPlayResetTicket internal constructor(val profileId: String, val backupTakenAtMs: Long) {
    private var spent = false
    internal fun spend(profile: WalkPlayProfile) {
        require(profile.id == profileId) { "The reset confirmation is for $profileId, not ${profile.id}" }
        require(!spent) { "A reset confirmation is good for one reset" }
        spent = true
    }
}

/** What [WalkPlaySession.factoryReset] saw: [steps] frames went out; [note] says what is unknown (the session ended). Neither proves the reset: the read after it does. */
data class WalkPlayResetSent(val steps: Int, val note: String?)

/** What [WalkPlaySession.restoreAfterReset] did, line by line; [ok] = every band, the preamp and every setting that had to be written read back as saved. */
data class WalkPlayRestoreResult(val lines: List<String>, val ok: Boolean)

/**
 * The state of one reset, in the order it must happen: a complete backup ([begin]), the owner's consent ([confirm], the only source of a
 * [WalkPlayResetTicket]), the sequence sent ([sent]), the read after it compared with the backup ([compared]), the restore ([restored]). Any step out of order
 * is refused. No I/O: the bench tool drives [WalkPlaySession] and tells the flow what happened.
 */
class WalkPlayResetFlow(private val profile: WalkPlayProfile) {
    enum class Step { START, BACKED_UP, CONFIRMED, SENT, COMPARED, RESTORED }

    var step: Step = Step.START
        private set
    var backup: WalkPlayResetBackup? = null
        private set
    var after: WalkPlayResetBackup? = null
        private set
    /** What the reset changed (see [WalkPlayReset.diff]); empty = nothing, which says the reset may not have run. */
    var changes: List<String> = emptyList()
        private set

    /** The backup is complete and kept: the reset may be asked for. */
    fun begin(taken: WalkPlayResetBackup) {
        require(step == Step.START) { "A reset is already under way" }
        require(WalkPlayReset.offered(profile)) { "${profile.name} has no factory reset in Contour" }
        val problems = taken.problems(profile)
        require(problems.isEmpty()) { "Backup incomplete, nothing sent: " + problems.joinToString("; ") }
        backup = taken
        step = Step.BACKED_UP
    }

    /** The owner confirmed that the DAC may be erased ([ownerConsent] is the confirmation sheet's button, never a default). */
    fun confirm(ownerConsent: Boolean): WalkPlayResetTicket {
        require(step == Step.BACKED_UP) { "Back the DAC up first" }
        require(ownerConsent) { "The owner has not confirmed the reset" }
        step = Step.CONFIRMED
        return WalkPlayResetTicket(profile.id, requireNotNull(backup).takenAtMs)
    }

    fun sent() {
        require(step == Step.CONFIRMED) { "No confirmed reset to send" }
        step = Step.SENT
    }

    /** The read after the reset: what it changed, as [WalkPlayReset.diff] lists it. */
    fun compared(read: WalkPlayResetBackup, labelOf: (String) -> String = { it }, valueOf: (String, Int) -> String = { _, v -> v.toString() }): List<String> {
        require(step == Step.SENT) { "Nothing was sent" }
        require(read.profileId == profile.id) { "The read is of ${read.profileId}" }
        after = read
        changes = WalkPlayReset.diff(requireNotNull(backup), read, labelOf, valueOf)
        step = Step.COMPARED
        return changes
    }

    fun restored() {
        require(step == Step.COMPARED) { "Compare the DAC with the backup first" }
        step = Step.RESTORED
    }
}
