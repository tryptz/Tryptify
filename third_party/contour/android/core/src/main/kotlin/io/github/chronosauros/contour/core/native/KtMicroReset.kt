package io.github.chronosauros.contour.core.native

/**
 * Factory reset of the KT Micro DAC (Kiwi Ears Allegro Mini): the vendor tool's CLEAR (register/command `0x43`, "clear flash"; Kiwi Ears Equalizer, devicePEQ's `COMMAND_CLEAR`).
 * The frame is `00 00 00 00 43 00 00 00 00 00` (report 0x4B); the vendor tool waits for the DAC to echo it with byte 6 = 3 ([KtMicroCatalog.RegisterMap.writeAck]) and sends nothing
 * after it (devicePEQ's `pushClearToDevice`; the sequence has no SAVE, and a SAVE after a clear would persist whatever the DAC holds then). NOT hardware-seen: what CLEAR restores
 * (the factory 1/2/5/8/10 kHz bands or the code defaults 28/84/680/4300/14500 Hz) and whether the DAC leaves the USB bus after it are unknown (`scope-kt-walkplay.md` 1.4, 1.7).
 *
 * So a reset is never a plain write, as for the FiiO ([FiioReset]) and WalkPlay ([WalkPlayReset]) DACs: [KtMicroResetFlow] takes a complete [KtMicroResetBackup] (the EQ switch, the ten
 * band registers, the Global Gain) and the owner's consent first, and only its [KtMicroResetTicket] lets [KtMicroCodec.compileClear] build the frame (one ticket, one frame).
 * [KtMicroCodec] builds `0x43` nowhere else, and the bench's deny-list lets exactly that frame out of `reset-plan --owner-consent` only.
 *
 * The restore writes the backup's own register bytes back ([KtMicroCodec.compileRestore]), then SAVE: the DAC leaves the bus after it, so the read that proves the restore comes after
 * a reconnect. There is no app path (no flag): the app keeps the CLEAR out until the bench has run it on the unit.
 */
object KtMicroReset {
    /** The vendor's clear-flash command; the record holds it as `driver.clearCommand`. */
    const val COMMAND = 0x43

    /** How long a session waits after CLEAR and after a SAVE before it asks the DAC anything (it may restart or leave the bus; Contour's number, the vendor has none). */
    const val SETTLE_MS = 3000L

    val ERASE_NOTE = "CLEAR (the vendor's flash clear) erases the EQ the Allegro Mini saved and puts the manufacturer's bands back; what else it erases is unknown. " +
        "Contour reads the EQ switch, all five bands and the Global Gain first and writes them back afterwards."

    /** The registers a restore may write: the EQ switch and the band registers. Digital gain (0x66) and the channel flags (0x01) are read only. */
    internal fun restorable(model: KtMicroCatalog.Model): Set<Int> = setOf(model.registers.enableRegister) + model.registers.bands.flatMap { listOf(it.gainFrequency, it.qType) }

    private fun hex(v: List<Int>) = v.joinToString(" ") { "%02x".format(it) }

    /**
     * What changed between [before] (the backup) and [after] (the read after CLEAR), one line per register that differs and for the Global Gain. Empty = nothing differs
     * (CLEAR did not run, or erased nothing). Registers are named as the vendor tool names them.
     */
    fun diff(before: KtMicroResetBackup, after: KtMicroResetBackup): List<String> {
        val out = ArrayList<String>()
        val a = after.snapshot.registers.associateBy { it.register }
        for (r in before.snapshot.registers) {
            val now = a[r.register]
            if (now == null) out += "register 0x%02x (%s): not read after the reset".format(r.register, name(r.register))
            else if (now.value != r.value) out += "register 0x%02x (%s): %s -> %s".format(r.register, name(r.register), hex(r.value), hex(now.value))
        }
        val g0 = before.globalGain; val g1 = after.globalGain
        if (g0?.value != g1?.value) out += "register 0x3b (Global Gain): ${g0?.value?.let(::hex) ?: "no reply"} -> ${g1?.value?.let(::hex) ?: "no reply"}"
        return out
    }

    fun name(register: Int): String = when (register) {
        KtMicroCodec.EQ_ENABLE_REGISTER -> "EQ switch"
        in 0x26..0x2F -> "band ${(register - 0x26) / 2 + 1} " + if ((register - 0x26) % 2 == 0) "gain and frequency" else "Q and type"
        KtMicroCodec.DIGITAL_GAIN_REGISTER -> "digital DAC gain"
        KtMicroCodec.GLOBAL_GAIN_REGISTER -> "Global Gain"
        KtMicroCodec.CHANNEL_FLAGS_REGISTER -> "channel flags"
        else -> "register"
    }
}

/**
 * Everything Contour reads from the Allegro Mini before a CLEAR: the whole [snapshot] (EQ switch, the ten band registers, digital gain, channel flags) and the Global Gain (0x3B, [globalGain]).
 * [toText] is the copy for the owner's file (plain lines, register by register), so a reset that was interrupted can still be undone by hand.
 */
class KtMicroResetBackup(val takenAtMs: Long, val snapshot: KtMicroCodec.Snapshot, val globalGain: KtMicroCodec.RegisterValue?) {
    /**
     * Why a reset must not start with this backup (empty = it may): the Global Gain was not read as a plain step 1-15 (it could not be written back, and the layout of 0x3B is not the one
     * Contour knows), or a register the restore writes is missing.
     */
    fun problems(model: KtMicroCatalog.Model): List<String> {
        val out = ArrayList<String>()
        if (snapshot.model != model) out += "The backup is of another KT model"
        val have = snapshot.registers.map { it.register }.toSet()
        KtMicroReset.restorable(model).filterNot { it in have }.forEach { out += "Register 0x%02x (%s) was not read".format(it, KtMicroReset.name(it)) }
        if (globalGain == null) out += "The Global Gain (0x3B) gave no valid reply: it could not be written back"
        else if (KtMicroCodec.globalGainStep(globalGain) == null) out += "The Global Gain (0x3B) reads ${globalGain.value.joinToString(" ") { "%02x".format(it) }}, not a step 1-15: it could not be written back"
        return out
    }

    fun toText(): String = buildString {
        appendLine(HEADER)
        appendLine("model ${snapshot.model.productName}")
        appendLine("taken $takenAtMs")
        for (r in snapshot.registers.sortedBy { it.register }) appendLine("register 0x%02x %s".format(r.register, r.value.joinToString(" ") { "%02x".format(it) }))
        globalGain?.let { appendLine("register 0x%02x %s".format(it.register, it.value.joinToString(" ") { v -> "%02x".format(v) })) }
    }

    companion object { const val HEADER = "contour-ktmicro-reset-backup 1" }
}

/** The one right to build the CLEAR frame, issued by [KtMicroResetFlow.confirm] and spent by [KtMicroCodec.compileClear] (one frame per ticket). Its constructor is module-internal. */
class KtMicroResetTicket internal constructor(val backupTakenAtMs: Long) {
    private var spent = false
    internal fun spend() {
        require(!spent) { "A reset confirmation is good for one reset" }
        spent = true
    }
}

/** What [KtMicroSession.factoryClear] saw: [acknowledged] = the DAC echoed CLEAR with byte 6 = 3; [note] says what is unknown. Neither proves the reset: the read after it does. */
data class KtMicroResetSent(val acknowledged: Boolean, val note: String?)

/** What [KtMicroSession.restoreAfterReset] did, line by line; [ok] = everything that had to be written read back as saved. */
data class KtMicroRestoreResult(val lines: List<String>, val ok: Boolean)

/**
 * The state of one reset, in the order it must happen: a complete backup ([begin]), the owner's consent ([confirm], the only source of a [KtMicroResetTicket]), CLEAR sent ([sent]), the
 * read after it compared with the backup ([compared]), the restore ([restored]). Any step out of order is refused. No I/O: the bench tool drives [KtMicroSession] and tells the flow what happened.
 */
class KtMicroResetFlow(private val model: KtMicroCatalog.Model) {
    enum class Step { START, BACKED_UP, CONFIRMED, SENT, COMPARED, RESTORED }

    var step: Step = Step.START
        private set
    var backup: KtMicroResetBackup? = null
        private set
    var after: KtMicroResetBackup? = null
        private set
    /** What the reset changed (see [KtMicroReset.diff]); empty = nothing, which says the reset may not have run. */
    var changes: List<String> = emptyList()
        private set

    /** The backup is complete and kept: the reset may be asked for. */
    fun begin(taken: KtMicroResetBackup) {
        require(step == Step.START) { "A reset is already under way" }
        require(model in KtMicroCatalog.allModels) { "Unqualified KT model" }
        require(model.resetOffered) { "${model.productName} has no reset in Contour: CLEAR (0x43) is never sent to it" }
        val problems = taken.problems(model)
        require(problems.isEmpty()) { "Backup incomplete, nothing sent: " + problems.joinToString("; ") }
        backup = taken
        step = Step.BACKED_UP
    }

    /** The owner confirmed that the DAC may be erased ([ownerConsent] is the owner's yes, never a default). */
    fun confirm(ownerConsent: Boolean): KtMicroResetTicket {
        require(step == Step.BACKED_UP) { "Back the DAC up first" }
        require(ownerConsent) { "The owner has not confirmed the reset" }
        step = Step.CONFIRMED
        return KtMicroResetTicket(requireNotNull(backup).takenAtMs)
    }

    fun sent() {
        require(step == Step.CONFIRMED) { "No confirmed reset to send" }
        step = Step.SENT
    }

    /** The read after the reset: what it changed, as [KtMicroReset.diff] lists it. */
    fun compared(read: KtMicroResetBackup): List<String> {
        require(step == Step.SENT) { "Nothing was sent" }
        after = read
        changes = KtMicroReset.diff(requireNotNull(backup), read)
        step = Step.COMPARED
        return changes
    }

    fun restored() {
        require(step == Step.COMPARED) { "Compare the DAC with the backup first" }
        step = Step.RESTORED
    }
}
