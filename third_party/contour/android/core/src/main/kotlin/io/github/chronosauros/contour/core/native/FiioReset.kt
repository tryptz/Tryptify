package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Proven

/**
 * Factory reset of the SnowSky Melody and Tiny A and the FiiO KA15: FiiO command 0x21 (RESET_TO_FACTORY), the one place in Contour where that command can become a frame.
 *
 * Where it comes from: FiiO Control shows "restore factory settings" on the Melody and Tiny A (on the KA15 FiiO's own config hides it, `resetDefault.show:false`;
 * the owner decided on 21.10 to offer it there anyway, with the same flow and the same bench proof first); the payload (`21 01 00`: length 1, data 00) is the generic builder of FiiO's web app
 * (`Ye(170,10,33,[0])`-style helper in its bundle), NOT a capture from a Melody or Tiny A, and what the DAC erases is unknown (slots, names, settings,
 * perhaps all). So a reset is never a plain write: [FiioResetFlow] takes the owner's consent and a complete backup first, and only its
 * [FiioResetTicket] lets [FiioCodec.resetFrame] build the frame (one ticket, one frame). 0x21 stays impossible everywhere else: [FiioCodec.querySetting],
 * [FiioCodec.setSetting] ([DacWrite.NEVER_SEND]), [ProbePlan.FIIO_FORBIDDEN] and the save-command check refuse it.
 *
 * [VERIFIED] is the flag of the app, like [DacSetting.writeVerified]: empty until a reset has been run on the unit from the PC bench and the app's
 * restore brought the slots, names and settings back. The bench (`reset-plan`) and the tests build the frame regardless; the app shows the row dimmed.
 */
object FiioReset {
    /** FiiO's RESET_TO_FACTORY. */
    const val COMMAND = 0x21
    /** The one data byte `00`, so the frame carries `21 01 00` (web-app builder, not captured). */
    internal val DATA: List<Int> = listOf(0x00)
    /** The DAC may re-enumerate or stay silent after the frame: how long the session waits before anything else is asked. */
    const val SETTLE_MS = 3000L

    private val OFFERED: Set<String> = setOf(FiioCatalog.MELODY.productName, FiioCatalog.TINY_A.productName, FiioCatalog.KA15.productName)
    /** The product names whose reset passed on hardware (backup, reset, re-read, restore). Enabling a model later is this one line. */
    // KA15: runs/2026-10-22-fiio-ka15-05-phone (830153f perf, fw 10106): backup, 0x21, replug (the controller restarted), read after (6 changes), restore OK, full compare identical with the
    // backup, then 10 flash saves with no hang. Melody: PC runs/2026-10-22-snowsky-melody-03 (reset with backup and restore) and phone runs/2026-10-22-snowsky-melody-01-phone/run.md L118-133 (one 0x21 frame,
    // replug, the change list as on the PC, WRITE THE SAVED COPY BACK with USER1-3 VERIFIED). The Tiny A ignores the frame and is not in the app at all ([NOT_IN_APP]).
    val VERIFIED: Set<String> = setOf(FiioCatalog.KA15.productName, FiioCatalog.MELODY.productName)

    /**
     * The reset is offered for this DAC: the vendor software shows it (Melody, Tiny A) or the owner decided on 21.10 to offer it although the vendor hides it
     * (KA15). Not the K13 R2R, Micro, Dawn Pro 2, TRN. The KA15 answers HID only while an audio stream is open, so its reset runs inside the same keep-alive as its other commands.
     */
    fun offered(config: FiioConfig?): Boolean = config != null && config.productName in OFFERED
    /**
     * The SnowSky Tiny A ignores the reset frame 0x21 (PC bench and phone, fw 1.2.0: runs 2026-10-23-snowsky-tiny-a-02 and -02-phone), so the app has no FACTORY RESET row for it, in any build.
     * The bench keeps [offered] (`reset-plan tiny-a`, `devices`): this is the app's list only.
     */
    private val NOT_IN_APP: Set<String> = setOf(FiioCatalog.TINY_A.productName)
    /** The app shows and runs the reset for this DAC: [offered], except the Tiny A. */
    fun inApp(config: FiioConfig?): Boolean = offered(config) && config != null && config.productName !in NOT_IN_APP
    /** The app may run it: [offered], and verified on hardware ([VERIFIED]) or a review build ([Proven.on]). */
    fun enabled(config: FiioConfig?): Boolean = offered(config) && Proven.on(proven(config))
    /** [enabled] for the app: also [inApp]. */
    fun enabledInApp(config: FiioConfig?): Boolean = inApp(config) && enabled(config)
    /** The reset of this model passed on hardware ([VERIFIED]); an open reset that is not proven keeps its "not verified" wording. */
    fun proven(config: FiioConfig?): Boolean = config != null && config.productName in VERIFIED

    /**
     * After the reset frame the DAC is read and written again only once the owner has unplugged it and plugged it back in: the 0x21 does not restart the controller (its HID reply
     * counter ran straight on), a replug does, and a KA15 hung on the 4th flash save after a reset (Pixel, 22.10.2026). This is the test for "a real replug": a detach
     * broadcast since the frame ([detachesNow] > [detachesAtSend]) AND an attach broadcast since it or the DAC seen missing from the bus ([sawMissing]). A detach alone does not count: a KA15
     * can report one while it stays plugged in. The app's counters feed it; no I/O here.
     */
    fun replugSeen(detachesAtSend: Int, detachesNow: Int, attachesAtSend: Int, attachesNow: Int, sawMissing: Boolean): Boolean =
        detachesNow > detachesAtSend && (attachesNow > attachesAtSend || sawMissing)

    /** The order a restore writes settings in: the volume top follows VOLUME STEPS (120 steps may rescale the volume), so steps first, volume last. */
    fun restoreRank(id: String): Int = when (id) { "volume-steps" -> 0; "volume" -> 2; else -> 1 }

    /** The text a person must see before the reset: what may be erased, and that Contour keeps a copy and offers to write it back. */
    val ERASE_NOTE = "A factory reset may erase the USER slots with their names and the DAC's settings. Contour saves a copy of all of them first and offers to write them back afterwards."

    /**
     * What changed between [before] (the backup) and [after] (the read after the reset), one line per slot name, slot EQ and setting. Empty = nothing
     * differs (the reset did not run, or erased nothing). [labelOf] names a setting id, [valueOf] one of its values.
     */
    fun diff(config: FiioConfig, before: FiioResetBackup, after: FiioResetBackup,
             labelOf: (String) -> String = { it }, valueOf: (String, Int) -> String = { _, v -> v.toString() }): List<String> {
        val out = ArrayList<String>()
        val afterSlots = after.slots.associateBy { it.slot }
        for (b in before.slots.sortedBy { it.slot }) {
            val label = config.slotLabels[b.slot] ?: "Slot ${b.slot}"
            val a = afterSlots[b.slot]
            if (a == null) { out += "$label: not read after the reset"; continue }
            if (b.name != a.name) out += "$label name: ${b.name?.ifEmpty { null } ?: "(none)"} -> ${a.name?.ifEmpty { null } ?: "(none)"}"
            val x = b.snapshot.copy(activeSlot = 0)
            val y = a.snapshot.copy(activeSlot = 0)
            if (x != y) {
                val registers = x.bands.zip(y.bands).count { (p, q) -> p != q } + kotlin.math.abs(x.bands.size - y.bands.size)
                // the count of registers that differ is for the maintainer: a review build says it, a release does not
                val detail = if (Proven.channel == BuildChannel.PERF) " ($registers filter registers differ)" else ""
                out += "$label EQ: ${x.count} filters, preamp ${x.preampTenths / 10.0} dB -> ${y.count} filters, preamp ${y.preampTenths / 10.0} dB$detail"
            }
        }
        for (id in (before.settings.keys + after.settings.keys).toSortedSet()) {
            val p = before.settings[id]
            val q = after.settings[id]
            if (p != q) out += "${labelOf(id)}: ${p?.let { valueOf(id, it) } ?: "no reply"} -> ${q?.let { valueOf(id, it) } ?: "no reply"}"
        }
        return out
    }
}

/** One USER slot as the backup holds it: its [name] (null = the DAC did not give one) and the native registers of its EQ. */
data class FiioSlotBackup(val slot: Int, val name: String?, val snapshot: FiioSnapshot) {
    init { require(snapshot.activeSlot == slot) { "Slot $slot backup holds the registers of slot ${snapshot.activeSlot}" } }
}

/**
 * Everything Contour reads from a SnowSky DAC before a factory reset: its USER slots with names and native registers, every readable setting (by
 * [DacSetting.id]) and the slot the DAC was on. [unreadSettings] are the ids that did not answer (they are not restored). Plain text on disk
 * ([toText] / [parse]), so the phone keeps it as a file and a reset that was interrupted can still be undone.
 */
data class FiioResetBackup(
    val productName: String,
    val takenAtMs: Long,
    /** The slot number the DAC reported before the backup (a stock preset, a USER slot or an unknown value); null = it was not read. */
    val activeSlot: Int?,
    val slots: List<FiioSlotBackup>,
    val settings: Map<String, Int>,
    val unreadSettings: List<String> = emptyList(),
) {
    /**
     * Why a reset must not start with this backup (empty = it may): another model, a USER slot missing or without a name read, or a slot whose
     * EQ Contour could not write back register for register ([FiioCodec.importExact]).
     */
    fun problems(config: FiioConfig): List<String> {
        val out = ArrayList<String>()
        if (productName != config.productName) return listOf("The backup is of $productName, not ${config.productName}")
        val codec = FiioCodec(config)
        val bySlot = slots.groupBy { it.slot }
        for (slot in config.userSlots.sorted()) {
            val label = config.slotLabels[slot] ?: "Slot $slot"
            val s = bySlot[slot]?.singleOrNull()
            if (s == null) { out += "$label was not read"; continue }
            if (config.userSlotNames && s.name == null) out += "$label: the name was not read"
            runCatching { codec.importExact(s.snapshot) }.exceptionOrNull()?.let { out += "$label cannot be written back exactly: ${it.message}" }
        }
        (bySlot.keys - config.userSlots).forEach { out += "Slot $it is not a USER slot of ${config.productName}" }
        return out
    }

    fun toText(): String = buildString {
        appendLine(HEADER)
        appendLine("product $productName")
        appendLine("taken $takenAtMs")
        appendLine("active ${activeSlot ?: "-"}")
        for (s in slots.sortedBy { it.slot }) {
            appendLine("slot ${s.slot} name ${s.name ?: "-"} count ${s.snapshot.count} preamp ${s.snapshot.preampTenths}")
            for (b in s.snapshot.bands.sortedBy { it.index }) appendLine("band ${s.slot} ${b.index} ${b.frequencyHz} ${b.gainTenths} ${b.qHundredths} ${b.typeCode}")
        }
        for ((id, v) in settings.toSortedMap()) appendLine("setting $id $v")
        for (id in unreadSettings.sorted()) appendLine("nosetting $id")
    }

    companion object {
        const val HEADER = "contour-reset-backup 1"

        /** The inverse of [toText]; anything that does not read back exactly is refused with the line. */
        fun parse(text: String): FiioResetBackup {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(lines.firstOrNull() == HEADER) { "Not a Contour reset backup" }
            var product: String? = null
            var taken: Long? = null
            var active: Int? = null
            var haveActive = false
            class Slot(val slot: Int, val name: String?, val count: Int, val preamp: Int, val bands: ArrayList<FiioRegisters> = ArrayList())
            val slots = LinkedHashMap<Int, Slot>()
            val settings = HashMap<String, Int>()
            val unread = ArrayList<String>()
            fun num(s: String, line: String) = s.toIntOrNull() ?: throw IllegalArgumentException("Bad number in \"$line\"")
            for (line in lines.drop(1)) {
                val p = line.split(' ')
                when (p[0]) {
                    "product" -> product = line.removePrefix("product ").trim()
                    "taken" -> taken = p.getOrNull(1)?.toLongOrNull() ?: throw IllegalArgumentException("Bad line \"$line\"")
                    "active" -> { haveActive = true; active = p.getOrNull(1)?.takeIf { it != "-" }?.let { num(it, line) } }
                    "slot" -> {
                        // "slot 160 name MEGA5EST count 10 preamp 0"
                        require(p.size == 8 && p[2] == "name" && p[4] == "count" && p[6] == "preamp") { "Bad line \"$line\"" }
                        val slot = num(p[1], line)
                        require(slots.put(slot, Slot(slot, p[3].takeIf { it != "-" }, num(p[5], line), num(p[7], line))) == null) { "Slot $slot twice" }
                    }
                    "band" -> {
                        require(p.size == 7) { "Bad line \"$line\"" }
                        val s = slots[num(p[1], line)] ?: throw IllegalArgumentException("Band before its slot: \"$line\"")
                        s.bands += FiioRegisters(num(p[2], line), num(p[3], line), num(p[4], line), num(p[5], line), num(p[6], line))
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
            require(product != null && taken != null && haveActive) { "The backup lacks its product, time or active slot" }
            val product0 = product
            return FiioResetBackup(product0, requireNotNull(taken), active, slots.values.map { s ->
                require(s.bands.size == s.count) { "Slot ${s.slot}: ${s.bands.size} bands for count ${s.count}" }
                FiioSlotBackup(s.slot, s.name, FiioSnapshot(product0, s.slot, s.count, s.preamp, s.bands.sortedBy { it.index }))
            }, settings, unread)
        }
    }
}

/**
 * The one right to build the reset frame, issued by [FiioResetFlow.confirm] and spent by [FiioCodec.resetFrame] (one frame per ticket). Its constructor
 * is module-internal: no read, write or probe path of the codec takes one.
 */
class FiioResetTicket internal constructor(val productName: String, val backupTakenAtMs: Long) {
    private var spent = false
    internal fun spend(config: FiioConfig) {
        require(config.productName == productName) { "The reset confirmation is for $productName, not ${config.productName}" }
        require(!spent) { "A reset confirmation is good for one reset" }
        spent = true
    }
}

/** What [NativeSession.factoryReset] saw: [echoed] = the DAC answered the frame; [note] says what is unknown (no answer, or the session ended). Neither proves the reset: the read after it does. */
data class FiioResetSent(val echoed: Boolean, val note: String?)

/** What [NativeSession.restoreAfterReset] did, line by line; [ok] = every slot and setting that had to be written read back as saved. */
data class FiioRestoreResult(val lines: List<String>, val ok: Boolean)

/**
 * The state of one reset, in the order it must happen: a complete backup ([begin]), the owner's consent ([confirm], the only source of a
 * [FiioResetTicket]), the frame sent ([sent]), the read after it compared with the backup ([compared]), the restore ([restored]). Any step out of order
 * is refused. No I/O: the app and the bench tool drive [NativeSession] and tell the flow what happened.
 */
class FiioResetFlow(private val config: FiioConfig) {
    enum class Step { START, BACKED_UP, CONFIRMED, SENT, COMPARED, RESTORED }

    var step: Step = Step.START
        private set
    var backup: FiioResetBackup? = null
        private set
    var after: FiioResetBackup? = null
        private set
    /** What the reset changed (see [FiioReset.diff]); empty = nothing, which says the reset may not have run. */
    var changes: List<String> = emptyList()
        private set

    /** The backup is complete and kept: the reset may be asked for. */
    fun begin(taken: FiioResetBackup) {
        require(step == Step.START) { "A reset is already under way" }
        require(FiioReset.offered(config)) { "${config.productName} has no factory reset in Contour" }
        val problems = taken.problems(config)
        require(problems.isEmpty()) { "Backup incomplete, nothing sent: " + problems.joinToString("; ") }
        backup = taken
        step = Step.BACKED_UP
    }

    /** The owner confirmed that the DAC may be erased ([ownerConsent] is the confirmation sheet's button, never a default). */
    fun confirm(ownerConsent: Boolean): FiioResetTicket {
        require(step == Step.BACKED_UP) { "Back the DAC up first" }
        require(ownerConsent) { "The owner has not confirmed the reset" }
        step = Step.CONFIRMED
        return FiioResetTicket(config.productName, requireNotNull(backup).takenAtMs)
    }

    fun sent() {
        require(step == Step.CONFIRMED) { "No confirmed reset to send" }
        step = Step.SENT
    }

    /** The read after the reset: what it changed, as [FiioReset.diff] lists it. */
    fun compared(read: FiioResetBackup, labelOf: (String) -> String = { it }, valueOf: (String, Int) -> String = { _, v -> v.toString() }): List<String> {
        require(step == Step.SENT) { "Nothing was sent" }
        require(read.productName == config.productName) { "The read is of ${read.productName}" }
        after = read
        changes = FiioReset.diff(config, requireNotNull(backup), read, labelOf, valueOf)
        step = Step.COMPARED
        return changes
    }

    fun restored() {
        require(step == Step.COMPARED) { "Compare the DAC with the backup first" }
        step = Step.RESTORED
    }
}
